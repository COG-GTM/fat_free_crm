package com.fatfreecrm.spike.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the SQL text {@link CustomFieldsBackfill} emits so that it keeps matching
 * {@code spike_sync_custom_fields()} in dual_read_trigger.sql (the integration
 * test {@code CustomFieldsSyncTriggerTest#setBasedBackfillIsLossless} proves the
 * two agree on real rows; this test guards the expression shape itself).
 */
class CustomFieldsBackfillTest {

    @Test
    void plainColumnsAreRemovedThenRebuiltFromBareColumns() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("cf_segment", "string");
        types.put("cf_amount", "decimal");
        assertThat(CustomFieldsBackfill.setBasedExpression(types)).isEqualTo(
            "(coalesce(custom_fields, '{}'::jsonb) - ARRAY['cf_segment', 'cf_amount']::text[])"
                + " || jsonb_strip_nulls(jsonb_build_object('cf_segment', cf_segment, 'cf_amount', cf_amount))");
    }

    @Test
    void checkBoxesColumnsUseYamlDecoderWithMarkerFallback() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("cf_interests", "check_boxes");
        String expr = CustomFieldsBackfill.setBasedExpression(types);
        assertThat(expr).contains(
            "'cf_interests', CASE WHEN cf_interests IS NULL THEN NULL"
                + " ELSE coalesce(spike_yaml_string_array(cf_interests),"
                + " jsonb_build_object('$yaml', cf_interests)) END");
        assertThat(expr).startsWith("(coalesce(custom_fields, '{}'::jsonb) - ARRAY['cf_interests']::text[]) || ");
    }

    @Test
    void columnOrderIsPreserved() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("cf_z", "string");
        types.put("cf_a", "integer");
        types.put("cf_m", "date");
        String expr = CustomFieldsBackfill.setBasedExpression(types);
        assertThat(expr).contains("ARRAY['cf_z', 'cf_a', 'cf_m']::text[]");
        assertThat(expr.indexOf("'cf_z', cf_z")).isLessThan(expr.indexOf("'cf_a', cf_a"));
        assertThat(expr.indexOf("'cf_a', cf_a")).isLessThan(expr.indexOf("'cf_m', cf_m"));
    }

    @Test
    void emptyColumnMapYieldsIdentityMerge() {
        assertThat(CustomFieldsBackfill.setBasedExpression(new LinkedHashMap<>())).isEqualTo(
            "(coalesce(custom_fields, '{}'::jsonb) - ARRAY[]::text[])"
                + " || jsonb_strip_nulls(jsonb_build_object())");
    }
}
