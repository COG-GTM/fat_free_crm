package com.fatfreecrm.service.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.MultiValueMap;

/**
 * Raw list-query parameters exactly as they arrive from the request.
 * All values are raw strings; parsing and validation happen in {@link CrmQueryService}.
 *
 * @param preferredPerPage per-user preference hook consumed by AB-270 (not part of the OpenAPI contract)
 * @param preferredSortBy  per-user preference hook consumed by AB-270 (not part of the OpenAPI contract)
 */
public record ListQuery(
    String page,
    String perPage,
    String query,
    String sortBy,
    Map<String, Object> q,
    Integer preferredPerPage,
    String preferredSortBy
) {

    public ListQuery {
        q = q == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(q));
    }

    @Override
    public Map<String, Object> q() {
        return new LinkedHashMap<>(q);
    }

    public static ListQuery fromParameters(MultiValueMap<String, String> params) {
        Map<String, Object> q = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            String key = entry.getKey();
            List<String> segments = bracketSegments(key);
            if (segments == null) {
                continue;
            }
            insert(q, segments, entry.getValue());
        }
        return new ListQuery(
            params.getFirst("page"),
            params.getFirst("per_page"),
            params.getFirst("query"),
            params.getFirst("sort_by"),
            q,
            null,
            null
        );
    }

    /** Copies this query with per-user preferences supplied by a caller (AB-270). */
    public ListQuery withPreferences(Integer preferredPerPageValue, String preferredSortByValue) {
        return new ListQuery(
            page, perPage, query, sortBy, q, preferredPerPageValue, preferredSortByValue);
    }

    private static List<String> bracketSegments(String key) {
        if (!key.startsWith("q[")) {
            return null;
        }
        String inner = key.substring(1);
        List<String> segments = new ArrayList<>();
        int position = 0;
        while (position < inner.length()) {
            if (inner.charAt(position) != '[') {
                return null;
            }
            int close = inner.indexOf(']', position);
            if (close < 0) {
                return null;
            }
            segments.add(inner.substring(position + 1, close));
            position = close + 1;
        }
        return segments.isEmpty() ? null : segments;
    }

    @SuppressWarnings("unchecked")
    private static void insert(Map<String, Object> tree, List<String> segments, List<String> values) {
        if (segments.isEmpty()) {
            return;
        }
        String head = segments.getFirst();
        if (segments.size() == 1) {
            putValue(tree, head, values);
            return;
        }
        if (head.isEmpty()) {
            return;
        }
        if ("".equals(segments.get(1))) {
            putValue(tree, head, values);
            return;
        }
        Object child = tree.get(head);
        if (!(child instanceof Map)) {
            child = new LinkedHashMap<String, Object>();
            tree.put(head, child);
        }
        insert((Map<String, Object>) child, segments.subList(1, segments.size()), values);
    }

    @SuppressWarnings("unchecked")
    private static void putValue(Map<String, Object> tree, String key, List<String> values) {
        Object existing = tree.get(key);
        if (existing instanceof List) {
            ((List<Object>) existing).addAll(values);
        } else if (existing != null) {
            List<Object> merged = new ArrayList<>();
            merged.add(existing);
            merged.addAll(values);
            tree.put(key, merged);
        } else if (values.size() > 1) {
            tree.put(key, new ArrayList<>(values));
        } else {
            tree.put(key, values.isEmpty() ? "" : values.get(0));
        }
    }
}
