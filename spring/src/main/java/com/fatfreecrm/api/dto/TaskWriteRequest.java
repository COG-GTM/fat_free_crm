package com.fatfreecrm.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Rails {@code TasksController#task_params} body: {@code {"task": {...}, "bucket": ..., "view": ...}}.
 * The {@code task} object is kept as an ordered map so only provided keys are assigned (partial
 * update); Rails accepts both string and number values. {@code bucket}/{@code view} are UI hints
 * Rails ignores on the server.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record TaskWriteRequest(Map<String, JsonNode> task, String bucket, String view) {
}
