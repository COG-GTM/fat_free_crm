package com.fatfreecrm.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wrapped {@code params[model]} maps plus the sibling keys the Rails entity controllers read
 * ({@code account}, {@code opportunity}, {@code campaign}, {@code contact}, {@code access},
 * {@code comment_body}) and ignored UI-hint keys so Rails-valid payloads never 400.
 * Model keys are raw {@link JsonNode}s: a scalar id in one controller's payload can be a nested
 * object in another's (e.g. {@code opportunity} in contacts#create vs leads#promote).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record EntityWriteRequest(
    JsonNode account,
    JsonNode campaign,
    JsonNode contact,
    JsonNode lead,
    JsonNode opportunity,
    JsonNode access,
    JsonNode comment_body,
    JsonNode user_ids,
    JsonNode group_ids,
    JsonNode tag_list,
    JsonNode assets,
    JsonNode asset_id,
    JsonNode attachment,
    JsonNode attachment_id,
    JsonNode view,
    JsonNode bucket,
    JsonNode naming,
    JsonNode cancel,
    JsonNode related,
    JsonNode tag,
    JsonNode previous,
    JsonNode query,
    JsonNode filter,
    JsonNode sort_by,
    JsonNode per_page,
    JsonNode page,
    JsonNode stage,
    JsonNode status,
    JsonNode category,
    JsonNode q,
    JsonNode id
) {
}
