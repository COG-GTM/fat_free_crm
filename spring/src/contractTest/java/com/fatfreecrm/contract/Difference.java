package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record Difference(
    String pointer,
    Kind kind,
    JsonNode railsValue,
    JsonNode springValue,
    List<String> allowedBy,
    String side
) {
    public enum Kind {
        STATUS,
        CONTENT_TYPE,
        INVALID_JSON,
        MISSING_KEY,
        EXPECTATION,
        VALUE,
        TYPE,
        MISSING_IN_SPRING,
        EXTRA_IN_SPRING,
        TEXT_BODY,
        DB
    }

    public Difference(String pointer, Kind kind, JsonNode railsValue, JsonNode springValue, List<String> allowedBy) {
        this(pointer, kind, railsValue, springValue, allowedBy, null);
    }

    public Difference {
        allowedBy = List.copyOf(allowedBy);
    }

    public static Difference expectation(String side, String pointer, JsonNode actual, JsonNode expected) {
        return new Difference(pointer, Kind.EXPECTATION, actual, expected, List.of(), side);
    }

    public boolean allowed() {
        return !allowedBy.isEmpty();
    }
}
