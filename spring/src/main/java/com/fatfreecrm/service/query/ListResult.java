package com.fatfreecrm.service.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One page of list results plus pagination metadata and per-entity sidebar facets.
 * Serializes as {@code {items, page, perPage, totalCount, totalPages, facets}}.
 */
public record ListResult<T>(
    List<T> items,
    int page,
    int perPage,
    long totalCount,
    int totalPages,
    Map<String, Map<String, Long>> facets
) {

    public ListResult {
        items = List.copyOf(items);
        Map<String, Map<String, Long>> copy = new LinkedHashMap<>();
        facets.forEach((key, value) -> copy.put(key, java.util.Collections.unmodifiableMap(
            new LinkedHashMap<>(value))));
        facets = java.util.Collections.unmodifiableMap(copy);
    }

    public <R> ListResult<R> map(Function<T, R> mapper) {
        List<R> mapped = new ArrayList<>(items.size());
        for (T item : items) {
            mapped.add(mapper.apply(item));
        }
        return new ListResult<>(mapped, page, perPage, totalCount, totalPages, facets);
    }
}
