package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomFieldsBackfillTest {

    @Test
    void withoutCustomColumnsTheExpressionOnlyNormalizesNullDocuments() {
        assertThat(CustomFieldsBackfill.setBasedExpression(Map.of()))
            .isEqualTo("coalesce(custom_fields, '{}'::jsonb)");
    }

    @Test
    void scalarColumnsAreRemovedFromTheDocumentThenRebuiltWithNullsStripped() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("cf_a", "string");
        columns.put("cf_b", "integer");

        String expression = CustomFieldsBackfill.setBasedExpression(columns);

        assertThat(expression)
            .startsWith("(coalesce(custom_fields, '{}'::jsonb) - ARRAY['cf_a', 'cf_b']::text[]) || ")
            .contains("jsonb_strip_nulls(jsonb_build_object('cf_a', to_jsonb(cf_a), 'cf_b', to_jsonb(cf_b)))")
            .doesNotContain("ffcrm_yaml_string_array");
    }

    @Test
    void checkBoxColumnsDecodeYamlAndFallBackToTheExistingArrayOrAYamlMarker() {
        String expression = CustomFieldsBackfill.setBasedExpression(Map.of("cf_boxes", "check_boxes"));

        assertThat(expression)
            .contains("'cf_boxes', CASE WHEN cf_boxes IS NULL THEN NULL")
            .contains("to_jsonb(cf_boxes)")
            .contains("coalesce(ffcrm_yaml_string_array(cf_boxes), ")
            .contains("jsonb_typeof(custom_fields -> 'cf_boxes') = 'array'")
            .contains("jsonb_build_object('$yaml', cf_boxes)");
    }
}
