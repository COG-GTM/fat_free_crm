package com.fatfreecrm.spike.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Edge cases of the {@link CustomFieldsDualReader} precedence rules that mirror
 * {@code spike_sync_custom_fields()}: marker handling, null jsonb values, and
 * READ-mode normalisation of legacy column values.
 */
class CustomFieldsDualReaderEdgeCasesTest {

    private static FieldDefinition field(String name, String as) {
        return new FieldDefinition(name, name, as, false, null, null, null, null, null);
    }

    private static final List<FieldDefinition> META = List.of(
        field("cf_segment", "string"),
        field("cf_interests", "check_boxes"),
        field("cf_amount", "decimal"),
        field("cf_score", "integer"),
        field("cf_since", "date"));

    @Test
    void nullJsonbValueIsSkipped() {
        Map<String, Object> jsonb = Collections.singletonMap("cf_segment", null);
        assertThat(CustomFieldsDualReader.read(Map.of(), jsonb, META)).doesNotContainKey("cf_segment");
    }

    @Test
    void columnWinsOverYamlMarkerInJsonb() {
        Map<String, Object> cols = Map.of("cf_interests", "---\n- fresh\n");
        Map<String, Object> jsonb = Map.of("cf_interests", Map.of("$yaml", "---\n- stale\n"));
        assertThat(CustomFieldsDualReader.read(cols, jsonb, META).get("cf_interests"))
            .isEqualTo(List.of("fresh"));
    }

    @Test
    void markerWithExtraKeysIsNotTreatedAsMarker() {
        // only a single-key {"$yaml": ...} object is the trigger's marker; anything
        // else is a plain stored value and stays as-is (READ keeps raw on failure)
        Map<String, Object> notMarker = Map.of("$yaml", "---\n- x\n", "other", 1);
        Map<String, Object> jsonb = Map.of("cf_interests", notMarker);
        assertThat(CustomFieldsDualReader.read(Map.of(), jsonb, META).get("cf_interests"))
            .isEqualTo(notMarker);
    }

    @Test
    void emptyYamlColumnDecodesToEmptyList() {
        Map<String, Object> cols = Map.of("cf_interests", "--- []\n");
        assertThat(CustomFieldsDualReader.read(cols, Map.of(), META).get("cf_interests"))
            .isEqualTo(List.of());
    }

    @Test
    void checkBoxesColumnWithBlankYamlDecodesToEmptyList() {
        Map<String, Object> cols = Map.of("cf_interests", "");
        assertThat(CustomFieldsDualReader.read(cols, Map.of(), META).get("cf_interests"))
            .isEqualTo(List.of());
    }

    @Test
    void jsonbArrayForCheckBoxesIsNormalisedNotDecoded() {
        // trigger-decoded arrays carry blanks/duplicates through READ normalisation
        Map<String, Object> jsonb = Map.of("cf_interests", List.of("A", "", "A", "B"));
        assertThat(CustomFieldsDualReader.read(Map.of(), jsonb, META).get("cf_interests"))
            .isEqualTo(List.of("A", "B"));
    }

    @Test
    void legacyNumericAndTextTimestampColumnsAreCoercedInReadMode() {
        // JDBC BigDecimal / Long and a text-typed legacy timestamp are normalised like
        // the jsonb path. Real java.sql.Date / LocalDate objects are NOT covered: the
        // validator only parses CharSequence dates and keeps other objects raw on READ,
        // which is reported as an open gap in the PR description rather than pinned.
        Map<String, Object> cols = Map.of(
            "cf_amount", new BigDecimal("1234.5"),
            "cf_score", 7L,
            "cf_since", "2024-01-31T00:00:00");
        Map<String, Object> out = CustomFieldsDualReader.read(cols, Map.of(), META);
        assertThat(out.get("cf_amount")).isEqualTo(new BigDecimal("1234.50"));
        assertThat(out.get("cf_score")).isEqualTo(7);
        assertThat(out.get("cf_since")).isEqualTo("2024-01-31");
    }

    @Test
    void jsonbNumbersKeepRawWhenNotCoercible() {
        Map<String, Object> jsonb = Map.of("cf_score", "not-a-number");
        assertThat(CustomFieldsDualReader.read(Map.of(), jsonb, META).get("cf_score"))
            .isEqualTo("not-a-number");
    }

    @Test
    void outputFollowsMetadataOrderNotInputOrder() {
        Map<String, Object> jsonb = Map.of("cf_since", "2024-01-31", "cf_segment", "s");
        assertThat(CustomFieldsDualReader.read(Map.of(), jsonb, META).keySet())
            .containsExactly("cf_segment", "cf_since");
    }

    @Test
    void emptyMetadataYieldsEmptyResult() {
        Map<String, Object> cols = Map.of("cf_segment", "x");
        Map<String, Object> jsonb = Map.of("cf_segment", "y");
        assertThat(CustomFieldsDualReader.read(cols, jsonb, List.of())).isEmpty();
    }
}
