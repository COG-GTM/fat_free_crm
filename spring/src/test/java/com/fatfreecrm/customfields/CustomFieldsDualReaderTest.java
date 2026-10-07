package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.support.RailsModelType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomFieldsDualReaderTest {

    private static CustomFieldDefinition field(String name, String as) {
        return new CustomFieldDefinition(name, name, as, false, null, null, null, null, null);
    }

    private static final List<CustomFieldDefinition> META = List.of(
        field("cf_segment", "string"),
        field("cf_interests", "check_boxes"),
        field("cf_seen_at", "datetime"));

    private static Map<String, Object> read(Map<String, Object> columns, Map<String, Object> jsonb) {
        return CustomFieldsDualReader.read(columns, jsonb, META, RailsModelType.ACCOUNT, 990300L);
    }

    @Test
    void columnWinsOverStaleJsonb() {
        Map<String, Object> cols = Map.of("cf_segment", "fresh");
        Map<String, Object> jsonb = Map.of("cf_segment", "stale");
        assertThat(read(cols, jsonb).get("cf_segment"))
            .isEqualTo("fresh");
    }

    @Test
    void nullColumnWinsAndRemovesKey() {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("cf_segment", null); // column exists but is NULL
        Map<String, Object> jsonb = Map.of("cf_segment", "stale");
        assertThat(read(cols, jsonb)).doesNotContainKey("cf_segment");
    }

    @Test
    void jsonbOnlyFieldIsReadFromDocument() {
        Map<String, Object> jsonb = Map.of("cf_segment", "jsonb-value");
        assertThat(read(Map.of(), jsonb).get("cf_segment"))
            .isEqualTo("jsonb-value");
    }

    @Test
    void checkBoxesColumnIsYamlDecoded() {
        Map<String, Object> cols = Map.of("cf_interests", "---\n- A\n- B\n");
        assertThat(read(cols, Map.of()).get("cf_interests"))
            .isEqualTo(List.of("A", "B"));
    }

    @Test
    void yamlMarkerInJsonbIsDecoded() {
        Map<String, Object> jsonb = Map.of("cf_interests", Map.of("$yaml", "---\n- X\n"));
        assertThat(read(Map.of(), jsonb).get("cf_interests"))
            .isEqualTo(List.of("X"));
    }

    @Test
    void orphanKeysNotInMetadataAreDropped() {
        Map<String, Object> cols = Map.of("cf_orphan", "zzz");
        Map<String, Object> jsonb = Map.of("cf_other", "yyy");
        assertThat(read(cols, jsonb)).isEmpty();
    }

    @Test
    void valuesAreNormalizedInReadMode() {
        Map<String, Object> jsonb = Map.of("cf_seen_at", "2024-01-31T10:00:00");
        assertThat(read(Map.of(), jsonb).get("cf_seen_at"))
            .isEqualTo("2024-01-31T10:00:00Z");
    }

    @Test
    void malformedYamlReturnsTheRawStringFromColumnsAndMarkers() {
        String raw = "---\n- \"\\uZZZZ\"\n";

        assertThat(read(Map.of("cf_interests", raw), Map.of()).get("cf_interests")).isEqualTo(raw);
        assertThat(read(Map.of(), Map.of("cf_interests", Map.of("$yaml", raw))).get("cf_interests"))
            .isEqualTo(raw);
    }
}
