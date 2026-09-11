package com.fatfreecrm.api.dto;

import java.util.List;

/**
 * List envelope for every collection endpoint (docs/migration/target-architecture.md §2.5):
 * {@code { "items": [...], "page": 1, "perPage": 20, "totalCount": 123 }}. The Rails JSON index
 * responses are bare arrays; the envelope is a deliberate, documented delta.
 *
 * @param items      the rows of the requested page
 * @param page       1-based page number that was served
 * @param perPage    effective page size (after clamping)
 * @param totalCount total number of rows matching the filter across all pages
 * @param <T>        item DTO type
 */
public record PageResponse<T>(List<T> items, int page, int perPage, long totalCount) {
}
