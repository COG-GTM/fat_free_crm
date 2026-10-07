package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;

public record AllowlistEntry(
    String id,
    String method,
    String path,
    String caseId,
    String kind,
    String reason,
    String reference,
    JsonNode definition
) {
}
