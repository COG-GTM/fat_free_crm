package com.fatfreecrm.service.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RailsParamsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode node(Object value) throws Exception {
        return JSON.readTree(JSON.writeValueAsString(value));
    }

    private static JsonNode textNode(String value) throws Exception {
        return node(value);
    }

    @Test
    void asBooleanFollowsActiveModelFalseValues() throws Exception {
        for (String falseValue : new String[] {"0", "f", "F", "false", "FALSE", "off", "OFF"}) {
            assertEquals(Boolean.FALSE, RailsParams.asBoolean(textNode(falseValue)), falseValue);
        }
        for (String trueValue : new String[] {"1", "t", "T", "true", "TRUE", "on", "ON",
            "arbitrary text", "yes", "no"}) {
            assertEquals(Boolean.TRUE, RailsParams.asBoolean(textNode(trueValue)), trueValue);
        }
    }

    @Test
    void asBooleanNullAndEmptyStringCastToNil() throws Exception {
        assertNull(RailsParams.asBoolean(null));
        assertNull(RailsParams.asBoolean(JSON.nullNode()));
        assertNull(RailsParams.asBoolean(textNode("")));
    }

    @Test
    void asBooleanNativeTypes() throws Exception {
        assertEquals(Boolean.TRUE, RailsParams.asBoolean(node(true)));
        assertEquals(Boolean.FALSE, RailsParams.asBoolean(node(false)));
        assertEquals(Boolean.FALSE, RailsParams.asBoolean(node(0)));
        assertEquals(Boolean.TRUE, RailsParams.asBoolean(node(2)));
    }

    @Test
    void asInstantAcceptsNumericOffsets() throws Exception {
        assertEquals(Instant.parse("2026-01-01T08:00:00Z"),
            RailsParams.asInstant(textNode("2026-01-01T10:00:00+02:00")));
        assertEquals(Instant.parse("2026-01-01T15:00:00Z"),
            RailsParams.asInstant(textNode("2026-01-01T10:00:00-05:00")));
        assertEquals(Instant.parse("2026-01-01T10:00:00Z"),
            RailsParams.asInstant(textNode("2026-01-01T10:00:00Z")));
    }

    @Test
    void asInstantNaiveAndBlankKeepExistingBehaviour() throws Exception {
        assertEquals(Instant.parse("2026-01-01T10:00:00Z"),
            RailsParams.asInstant(textNode("2026-01-01 10:00:00")));
        assertNull(RailsParams.asInstant(textNode("")));
        assertNull(RailsParams.asInstant(textNode("not a date")));
        assertNull(RailsParams.asInstant(null));
    }
}
