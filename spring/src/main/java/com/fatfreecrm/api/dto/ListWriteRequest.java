package com.fatfreecrm.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Rails {@code ListsController#list_params} body: {@code {"list": {...}, "is_global": "1"}}. */
public record ListWriteRequest(Map<String, JsonNode> list, String is_global) {
}
