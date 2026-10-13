package com.fatfreecrm.service.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure helpers of {@link EntityWriteService} that port Rails parameter semantics:
 * {@code user_ids=} / {@code group_ids=} ({@code lib/fat_free_crm/permissions.rb}:
 * {@code Array(ids).reject(&:blank?).map(&:to_i).uniq}) and the ordered params hash.
 */
class EntityWriteServiceParamsTest {

    @Test
    void normalizeIdsDropsBlanksDeduplicatesAndKeepsFirstSeenOrder() {
        List<Object> raw = new ArrayList<>(Arrays.asList("3", "", "  ", null, 3, "1", 3L, "1"));
        assertThat(EntityWriteService.normalizeIds(raw)).containsExactly(3L, 1L);
    }

    @Test
    void normalizeIdsAppliesRubyStringToIntegerSemantics() {
        List<Object> raw = Arrays.asList(" 42abc", "-7", "+5", "1_000", "abc", "0x1A", "9.9");
        assertThat(EntityWriteService.normalizeIds(raw)).containsExactly(42L, -7L, 5L, 1000L, 0L, 9L);
    }

    @Test
    void normalizeIdsOfNothingIsEmpty() {
        assertThat(EntityWriteService.normalizeIds(List.of())).isEmpty();
        assertThat(EntityWriteService.normalizeIds(Arrays.asList("", null))).isEmpty();
    }

    @Test
    void toMapPreservesParameterOrderAndToleratesMissingOrScalarNodes() throws Exception {
        JsonNode node = JsonMapper.builder().build()
            .readTree("{\"access\":\"Shared\",\"name\":\"Acme\",\"user_ids\":[1],\"rating\":null}");
        Map<String, JsonNode> params = EntityWriteService.toMap(node);
        assertThat(params.keySet()).containsExactly("access", "name", "user_ids", "rating");
        assertThat(params.get("name").asText()).isEqualTo("Acme");
        assertThat(params.get("rating").isNull()).isTrue();
        assertThat(EntityWriteService.toMap(null)).isEmpty();
        assertThat(EntityWriteService.toMap(JsonMapper.builder().build().readTree("\"scalar\""))).isEmpty();
        assertThat(EntityWriteService.toMap(JsonMapper.builder().build().readTree("[1,2]"))).isEmpty();
    }
}
