package com.fatfreecrm.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Admin field write body: {@code field} plus the {@code pair} hash used by *_pair types. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdminFieldWriteRequest(Map<String, JsonNode> field, Map<String, JsonNode> pair) {
}
