package com.fatfreecrm.customfields;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the lossless set-based backfill expression for {@code custom_fields},
 * preserving Java-only keys and undecodable check_boxes YAML for Java resolution.
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
        if (cfColumnTypes.isEmpty()) {
            return "coalesce(custom_fields, '{}'::jsonb)";
        }
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
            return "CASE WHEN " + column + " IS NULL THEN NULL "
                + "WHEN " + column + " !~ '^\\s*(---|\\[)' THEN to_jsonb(" + column + ") "
                + "ELSE coalesce(ffcrm_yaml_string_array(" + column + "), "
                + "CASE WHEN jsonb_typeof(custom_fields -> '" + column + "') = 'array' "
                + "THEN custom_fields -> '" + column + "' "
                + "ELSE jsonb_build_object('$yaml', " + column + ") END) END";
        }
        return "to_jsonb(" + column + ")";
    }
}
