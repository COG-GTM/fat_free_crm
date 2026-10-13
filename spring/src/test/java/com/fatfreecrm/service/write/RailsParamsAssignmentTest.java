package com.fatfreecrm.service.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Rails {@code assign_attributes(permitted_params)} partial-update and cast semantics. */
class RailsParamsAssignmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static RailsParams params(String json) throws Exception {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        JSON.readTree(json).properties()
            .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
        return RailsParams.of(values);
    }

    @Test
    void absentKeysAreNeverAssignedWhileExplicitNullAssignsNil() throws Exception {
        RailsParams params = params("{\"name\":null,\"bucket\":\"due_today\"}");
        AtomicReference<String> name = new AtomicReference<>("untouched");
        AtomicReference<String> category = new AtomicReference<>("untouched");
        AtomicReference<String> bucket = new AtomicReference<>();

        params.assignString("name", name::set);
        params.assignString("category", category::set);
        params.assignString("bucket", bucket::set);

        assertNull(name.get());
        assertEquals("untouched", category.get());
        assertEquals("due_today", bucket.get());
        assertTrue(params.provided("name"));
        assertFalse(params.provided("category"));
        assertEquals(List.of("name", "bucket"), params.keys());
    }

    @Test
    void nullMapBehavesAsEmptyParams() {
        RailsParams params = RailsParams.of(null);
        AtomicReference<String> name = new AtomicReference<>("untouched");
        params.assignString("name", name::set);
        assertEquals("untouched", name.get());
        assertEquals(List.of(), params.keys());
        assertTrue(params.get("name").isEmpty());
    }

    @Test
    void asIntegerMirrorsActiveRecordIntegerCast() throws Exception {
        RailsParams params = params(
            "{\"a\":3,\"b\":\"3\",\"c\":\"abc\",\"d\":3.7,\"e\":\"\",\"f\":null,\"g\":\" 7 \"}");
        assertEquals(3, RailsParams.asInteger(params.get("a").orElseThrow()));
        assertEquals(3, RailsParams.asInteger(params.get("b").orElseThrow()));
        assertNull(RailsParams.asInteger(params.get("c").orElseThrow()));
        assertEquals(3, RailsParams.asInteger(params.get("d").orElseThrow()));
        assertNull(RailsParams.asInteger(params.get("e").orElseThrow()));
        assertNull(RailsParams.asInteger(params.get("f").orElseThrow()));
        assertEquals(7, RailsParams.asInteger(params.get("g").orElseThrow()));
        assertNull(RailsParams.asInteger(null));
    }

    @Test
    void asStringTakesTextForScalarsAndJsonForStructures() throws Exception {
        RailsParams params = params("{\"t\":\"hi\",\"n\":12,\"b\":true,\"o\":{\"k\":1},\"z\":null}");
        assertEquals("hi", RailsParams.asString(params.get("t").orElseThrow()));
        assertEquals("12", RailsParams.asString(params.get("n").orElseThrow()));
        assertEquals("true", RailsParams.asString(params.get("b").orElseThrow()));
        assertEquals("{\"k\":1}", RailsParams.asString(params.get("o").orElseThrow()));
        assertNull(RailsParams.asString(params.get("z").orElseThrow()));
        assertNull(RailsParams.asString(null));
    }

    @Test
    void typedAssignersCastBeforeCallingTheSetter() throws Exception {
        RailsParams params = params("{\"asset_id\":\"42\",\"private\":\"0\",\"due_at\":\"2030-01-02T03:04:05Z\","
            + "\"completed_at\":\"\"}");
        AtomicReference<Integer> assetId = new AtomicReference<>();
        AtomicReference<Boolean> privateFlag = new AtomicReference<>();
        AtomicReference<Instant> dueAt = new AtomicReference<>();
        AtomicReference<Instant> completedAt = new AtomicReference<>(Instant.EPOCH);

        params.assignInteger("asset_id", assetId::set);
        params.assignBoolean("private", privateFlag::set);
        params.assignInstant("due_at", dueAt::set);
        params.assignInstant("completed_at", completedAt::set);

        assertEquals(42, assetId.get());
        assertEquals(Boolean.FALSE, privateFlag.get());
        assertEquals(Instant.parse("2030-01-02T03:04:05Z"), dueAt.get());
        assertNull(completedAt.get());
    }
}
