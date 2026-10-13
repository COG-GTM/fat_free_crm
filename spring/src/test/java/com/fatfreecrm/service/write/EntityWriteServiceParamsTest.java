package com.fatfreecrm.service.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure helpers of {@link EntityWriteService}: the Ruby {@code Integer} cast applied to
 * {@code user_ids=}/{@code group_ids=} values and the ordered param-map conversion that drives
 * PaperTrail's attribute order.
 */
class EntityWriteServiceParamsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void normalizeIdsCastsLikeRubyToIAndDeduplicatesInOrder() {
        List<Object> raw = Arrays.asList(3, "7", " 12abc", "abc", "", "   ", null, 3L, "7",
            "1_000", "-4", "+5", 2.9d);
        assertThat(EntityWriteService.normalizeIds(raw))
            .containsExactly(3L, 7L, 12L, 0L, 1000L, -4L, 5L, 2L);
    }

    @Test
    void normalizeIdsOfEmptyInputIsEmpty() {
        assertThat(EntityWriteService.normalizeIds(List.of())).isEmpty();
        assertThat(EntityWriteService.normalizeIds(Arrays.asList("", null))).isEmpty();
    }

    @Test
    void toMapPreservesPayloadKeyOrderAndTreatsNonObjectsAsEmpty() throws Exception {
        JsonNode node = JSON.readTree("{\"name\":\"Acme\",\"access\":\"Shared\",\"user_ids\":[1]}");
        Map<String, JsonNode> map = EntityWriteService.toMap(node);
        assertThat(map.keySet()).containsExactly("name", "access", "user_ids");
        assertThat(map.get("name").asText()).isEqualTo("Acme");

        assertThat(EntityWriteService.toMap(null)).isEmpty();
        assertThat(EntityWriteService.toMap(JSON.readTree("null"))).isEmpty();
        assertThat(EntityWriteService.toMap(JSON.readTree("\"account\""))).isEmpty();
        assertThat(EntityWriteService.toMap(JSON.readTree("[1,2]"))).isEmpty();
    }

    @Test
    void fieldReadsSiblingKeysAndToleratesAMissingBody() throws Exception {
        JsonNode request = JSON.readTree("{\"account\":{\"name\":\"x\"},\"comment_body\":\"hi\"}");
        assertThat(EntityWriteService.field(request, "comment_body").asText()).isEqualTo("hi");
        assertThat(EntityWriteService.field(request, "account").isObject()).isTrue();
        assertThat(EntityWriteService.field(request, "opportunity")).isNull();
        assertThat(EntityWriteService.field(null, "account")).isNull();
    }
}
