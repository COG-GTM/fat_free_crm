package com.fatfreecrm.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Admin write body: Rails reads only the `tag` root (other root keys are ignored). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdminTagWriteRequest(@JsonProperty("tag") Map<String, JsonNode> tag) {
}
