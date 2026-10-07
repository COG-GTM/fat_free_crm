package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record Difference(String pointer, Kind kind, JsonNode railsValue, JsonNode springValue, List<String> allowedBy) {
    public enum Kind {
        STATUS,
        CONTENT_TYPE,
        VALUE,
        TYPE,
        MISSING_IN_SPRING,
        EXTRA_IN_SPRING
    }

    public Difference {
        allowedBy = List.copyOf(allowedBy);
    }

    public boolean allowed() {
        return !allowedBy.isEmpty();
    }
}
