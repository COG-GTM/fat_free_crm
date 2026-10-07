package com.fatfreecrm.spike.customfields;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the lossless set-based backfill expression for {@code custom_fields}.
 * Unlike the trigger-shaped plain assignment, the merge form preserves
 * Java-only keys (no cf_ column) and falls back to the {@code {"$yaml": raw}}
 * marker for check_boxes YAML the SQL decoder cannot handle - matching
 * {@code spike_sync_custom_fields()} in dual_read_trigger.sql exactly.
 */
public final class CustomFieldsBackfill {

    private CustomFieldsBackfill() {
    }

    /**
     * Returns the right-hand side of {@code SET custom_fields = ...}:
     * {@code (coalesce(custom_fields,'{}') - <every cf_ column>) || <built object>}.
     *
     * @param cfColumnTypes ordered map of cf_ column name to {@code fields."as"}
     */
    public static String setBasedExpression(Map<String, String> cfColumnTypes) {
        String columns = cfColumnTypes.keySet().stream()
            .map(c -> "'" + c + "'")
            .collect(Collectors.joining(", ", "ARRAY[", "]::text[]"));
        String object = cfColumnTypes.entrySet().stream()
            .map(e -> "'" + e.getKey() + "', " + valueExpression(e.getKey(), e.getValue()))
            .collect(Collectors.joining(", ", "jsonb_strip_nulls(jsonb_build_object(", "))"));
        return "(coalesce(custom_fields, '{}'::jsonb) - " + columns + ") || " + object;
    }

    private static String valueExpression(String column, String as) {
        if ("check_boxes".equals(as)) {
            // same marker form as spike_sync_custom_fields()
            return "CASE WHEN " + column + " IS NULL THEN NULL"
                + " ELSE coalesce(spike_yaml_string_array(" + column + "),"
                + " jsonb_build_object('$yaml', " + column + ")) END";
        }
        // bare column: jsonb_build_object renders date/timestamp exactly like
        // the trigger's to_jsonb(NEW), fractional seconds included
        return column;
    }
}
