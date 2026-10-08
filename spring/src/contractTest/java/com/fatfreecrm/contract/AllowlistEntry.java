package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;

public record AllowlistEntry(
    String id,
    String method,
    String path,
    String caseId,
    Boolean authenticated,
    String kind,
    String reason,
    String reference,
    JsonNode definition
) {
    public String pointer() {
        return definition.path("pointer").asText();
    }

    public String keyPattern() {
        return definition.path("keyPattern").asText(null);
    }
}
