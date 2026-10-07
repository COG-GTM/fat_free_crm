package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

class JsonNormalizerMergeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void mergeAppendsCaseOptionsAfterGlobalOptionsPerKey() throws Exception {
        JsonNode global = JSON.readTree("""
            {"ignore":["/generatedAt"],"timestamps":["/created_at"]}
            """);
        JsonNode local = JSON.readTree("""
            {"ignore":["/id"],"unorderedArrays":[{"path":"","key":"id"}]}
            """);

        JsonNode merged = JsonNormalizer.merge(global, local);

        assertEquals(JSON.readTree("""
            {"ignore":["/generatedAt","/id"],"timestamps":["/created_at"],"unorderedArrays":[{"path":"","key":"id"}]}
            """), merged);
    }

    @Test
    void mergeCopiesValuesAndLeavesTheInputsUntouched() throws Exception {
        JsonNode global = JSON.readTree("{\"ignore\":[\"/a\"]}");
        JsonNode local = JSON.readTree("{\"ignore\":[\"/b\"]}");

        JsonNode merged = JsonNormalizer.merge(global, local);
        ((ArrayNode) merged.get("ignore")).add("/c");

        assertEquals(JSON.readTree("{\"ignore\":[\"/a\"]}"), global);
        assertEquals(JSON.readTree("{\"ignore\":[\"/b\"]}"), local);
        assertEquals(3, merged.get("ignore").size());
    }

    @Test
    void mergeToleratesMissingOrNullOptionSets() throws Exception {
        JsonNode local = JSON.readTree("{\"ignore\":[\"/b\"]}");

        assertEquals(local, JsonNormalizer.merge(NullNode.getInstance(), local));
        assertEquals(local, JsonNormalizer.merge(local, MissingNode.getInstance()));
        assertTrue(JsonNormalizer.merge(JSON.createObjectNode(), JSON.createObjectNode()).isEmpty());
    }

    @Test
    void mergedIgnoreRulesFromBothLevelsApplyToNormalisation() throws Exception {
        JsonNode options = JsonNormalizer.merge(JSON.readTree("{\"ignore\":[\"/generatedAt\"]}"),
            JSON.readTree("{\"ignore\":[\"/items/*/id\"]}"));
        JsonNode normalized = JsonNormalizer.normalize(JSON.readTree("""
            {"generatedAt":"now","items":[{"id":1,"name":"a"}],"name":"keep"}
            """), options, "rails");

        assertEquals(JSON.readTree("{\"items\":[{\"name\":\"a\"}],\"name\":\"keep\"}"), normalized);
        assertTrue(JsonNormalizer.ignored("/generatedAt", options));
        assertTrue(JsonNormalizer.ignored("/items/3/id", options));
        assertFalse(JsonNormalizer.ignored("/name", options));
    }

    @Test
    void renamesAndIgnoresResolveEscapedPointerSegments() throws Exception {
        JsonNode options = JSON.readTree("""
            {"rename":[{"side":"spring","from":"/a~1b","to":"/ab"}],"ignore":["/x~0y"]}
            """);
        JsonNode normalized = JsonNormalizer.normalize(JSON.readTree("""
            {"a/b":1,"x~y":2,"plain":3}
            """), options, "spring");

        assertEquals(JSON.readTree("{\"ab\":1,\"plain\":3}"), normalized);
    }
}
