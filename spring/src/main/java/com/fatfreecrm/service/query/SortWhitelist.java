package com.fatfreecrm.service.query;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-entity sort whitelist mirroring the Rails {@code sortable} declaration.
 * The sort direction is fixed by the whitelist; clients can only pick the key.
 */
public final class SortWhitelist {

    private record SortKey(String key, String path, boolean descending) {
    }

    private final List<SortKey> keys;
    private final Map<String, SortKey> byKey;
    private final SortKey defaultKey;

    private SortWhitelist(List<SortKey> keys, SortKey defaultKey) {
        this.keys = List.copyOf(keys);
        this.byKey = new LinkedHashMap<>();
        for (SortKey key : keys) {
            byKey.put(key.key(), key);
        }
        this.defaultKey = defaultKey;
    }

    /** Builds a whitelist from Rails clause strings such as {@code "name ASC"} or {@code "amount*probability DESC"}. */
    public static SortWhitelist of(Class<?> entityType, String defaultClause, String... clauses) {
        List<SortKey> keys = new ArrayList<>();
        for (String clause : clauses) {
            String[] parts = clause.trim().split("\\s+");
            String path = parts[0];
            boolean descending = parts.length > 1 && parts[1].equalsIgnoreCase("desc");
            RansackAttributes.Attribute attribute = RansackAttributes.catalog(entityType).get(path);
            String field = attribute == null ? path : attribute.field();
            keys.add(new SortKey(path, field, descending));
        }
        String[] defaultParts = defaultClause.trim().split("\\s+");
        SortKey defaultKey = null;
        for (SortKey key : keys) {
            if (key.key().equals(defaultParts[0])) {
                defaultKey = key;
            }
        }
        if (defaultKey == null) {
            throw new IllegalArgumentException("Default sort " + defaultClause + " is not in the whitelist");
        }
        return new SortWhitelist(keys, defaultKey);
    }

    /**
     * Resolves a client-supplied sort selector to the whitelisted key.
     * Accepts the bare key ({@code name}, {@code amount*probability}) or the full Rails
     * preference form ({@code accounts.name ASC}, case-insensitive direction).
     * Anything else resolves to {@code null}.
     */
    public SortKey resolve(String selector) {
        if (selector == null || selector.isBlank()) {
            return null;
        }
        String candidate = selector.trim();
        SortKey direct = byKey.get(candidate);
        if (direct != null) {
            return direct;
        }
        String[] parts = candidate.split("\\s+");
        if (parts.length == 2 && (parts[1].equalsIgnoreCase("asc") || parts[1].equalsIgnoreCase("desc"))) {
            String qualified = parts[0];
            int dot = qualified.indexOf('.');
            String attribute = dot >= 0 ? qualified.substring(dot + 1) : qualified;
            SortKey match = byKey.get(attribute);
            return match != null && match.descending() == parts[1].equalsIgnoreCase("desc")
                ? match
                : null;
        }
        return null;
    }

    public List<Order> defaultOrder(Root<?> root, CriteriaBuilder cb) {
        return List.of(order(root, cb, defaultKey));
    }

    public Order order(Root<?> root, CriteriaBuilder cb, SortKey key) {
        return key.descending()
            ? cb.desc(expression(root, cb, key.path()))
            : cb.asc(expression(root, cb, key.path()));
    }

    public List<Order> resolveOrder(String selector, Root<?> root, CriteriaBuilder cb) {
        SortKey key = resolve(selector);
        return key == null ? defaultOrder(root, cb) : List.of(order(root, cb, key));
    }

    private Expression<?> expression(Root<?> root, CriteriaBuilder cb, String path) {
        if (path.contains("*")) {
            String[] parts = path.split("\\*");
            List<Expression<Number>> factors = new ArrayList<>();
            for (String part : parts) {
                factors.add(root.<Number>get(part.trim()));
            }
            Expression<Number> product = factors.get(0);
            for (int index = 1; index < factors.size(); index++) {
                product = cb.prod(product, factors.get(index));
            }
            return product;
        }
        return root.get(path);
    }
}
