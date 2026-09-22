package com.fatfreecrm.api.dto;

import java.util.List;

/**
 * List envelope for every paginated endpoint (docs/migration/target-architecture.md §2.5):
 * <pre>{ "items": [...], "page": 1, "perPage": 20, "totalCount": 123 }</pre>
 * Rails returns a bare array today; the envelope is the deliberate contract change of the
 * rewrite.
 *
 * @param items      the rows of the requested page
 * @param page       1-based page number that was served
 * @param perPage    effective page size after clamping
 * @param totalCount total number of rows matching the filters (all pages)
 * @param <T>        item DTO type
 */
public record PageResponse<T>(List<T> items, int page, int perPage, long totalCount) {

    public PageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
