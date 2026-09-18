package com.fatfreecrm.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Pagination defaults mirroring the Rails {@code per_page} handling: default 20, clamped to
 * {@code 1..200} (see {@code perPage} parameter in openapi.yaml).
 *
 * @param defaultPageSize page size when the client sends no {@code per_page}
 * @param maxPageSize     upper clamp for {@code per_page}
 */
@Validated
@ConfigurationProperties(prefix = "fatfreecrm.pagination")
public record PaginationProperties(
        @Min(1) @Max(200) int defaultPageSize,
        @Min(1) @Max(200) int maxPageSize) {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    public PaginationProperties {
        if (defaultPageSize == 0) {
            defaultPageSize = DEFAULT_PAGE_SIZE;
        }
        if (maxPageSize == 0) {
            maxPageSize = MAX_PAGE_SIZE;
        }
    }

    /** Clamps a requested page size into {@code 1..maxPageSize}, falling back to the default. */
    public int clampPageSize(Integer requested) {
        if (requested == null) {
            return defaultPageSize;
        }
        return Math.max(1, Math.min(requested, maxPageSize));
    }
}
