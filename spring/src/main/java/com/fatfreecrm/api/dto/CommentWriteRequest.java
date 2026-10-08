package com.fatfreecrm.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Rails {@code CommentsController#comment_params} body: {@code {"comment": {...}}}. Ordered map so
 * only provided keys are assigned on update (Rails permits user_id/commentable_* too — see ADR).
 */
public record CommentWriteRequest(Map<String, JsonNode> comment) {
}
