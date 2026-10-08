package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The backfill SQL must be lossless: it removes exactly the known {@code cf_} keys before re-adding them
 * (so Java-only keys survive), strips nulls like the trigger does, and never lets undecodable
 * check_boxes YAML silently become a plain string.
 */
class CustomFieldsBackfillTest {

    @Test
    void withoutCustomColumnsOnlyCoalescesTheDocument() {
        assertThat(CustomFieldsBackfill.setBasedExpression(Map.of()))
            .isEqualTo("coalesce(custom_fields, '{}'::jsonb)");
    }

    @Test
    void scalarColumnsAreSubtractedThenRebuiltInDeclarationOrder() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("cf_b_text", "string");
        columns.put("cf_a_amount", "decimal");

        String sql = CustomFieldsBackfill.setBasedExpression(columns);

        assertThat(sql).startsWith(
            "(coalesce(custom_fields, '{}'::jsonb) - ARRAY['cf_b_text', 'cf_a_amount']::text[]) || ");
        assertThat(sql).endsWith(
            "jsonb_strip_nulls(jsonb_build_object('cf_b_text', to_jsonb(cf_b_text), "
                + "'cf_a_amount', to_jsonb(cf_a_amount)))");
    }

    @Test
    void checkBoxesDecodeYamlAndKeepUndecodableInputAsAYamlMarker() {
        String sql = CustomFieldsBackfill.setBasedExpression(Map.of("cf_boxes", "check_boxes"));

        assertThat(sql).contains("ARRAY['cf_boxes']::text[]");
        assertThat(sql).contains("CASE WHEN cf_boxes IS NULL THEN NULL");
        assertThat(sql)
            .as("non-YAML text (legacy plain values) is kept verbatim as a JSON string")
            .contains("WHEN cf_boxes !~ '^\\s*(---|\\[)' THEN to_jsonb(cf_boxes)");
        assertThat(sql).contains("coalesce(ffcrm_yaml_string_array(cf_boxes), ");
        assertThat(sql)
            .as("an already-resolved JSONB array from a previous run wins over re-decoding")
            .contains("WHEN jsonb_typeof(custom_fields -> 'cf_boxes') = 'array' THEN custom_fields -> 'cf_boxes'");
        assertThat(sql)
            .as("YAML that SQL cannot decode is deferred to Java via the $yaml marker, never dropped")
            .contains("ELSE jsonb_build_object('$yaml', cf_boxes) END");
        assertThat(sql).doesNotContain("to_jsonb(cf_boxes))");
    }

    @Test
    void onlyCheckBoxesUseTheYamlBranch() {
        for (String as : new String[] {"string", "text", "select", "radio_buttons", "boolean", "date",
            "datetime", "decimal", "integer", "float", "email", "url", "tel", "date_pair", "datetime_pair"}) {
            String sql = CustomFieldsBackfill.setBasedExpression(Map.of("cf_col", as));
            assertThat(sql).as(as).contains("'cf_col', to_jsonb(cf_col)").doesNotContain("$yaml");
        }
    }
}
