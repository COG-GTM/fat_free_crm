package com.fatfreecrm.service;

import java.util.Locale;
import org.springframework.data.domain.Sort;

/**
 * The sort orders Rails allows for accounts ({@code sortable by:} in
 * app/models/entities/account.rb): {@code name ASC}, {@code rating DESC}, {@code created_at DESC},
 * {@code updated_at DESC}; default {@code created_at DESC}. Each order is fixed to its Rails
 * direction — the direction suffix may be omitted and the column may be spelled
 * {@code snake_case} or {@code camelCase}; matching is case-insensitive. {@code id} is appended as
 * a tiebreaker (same direction) so pages are stable when the sort key is not unique.
 */
public enum AccountSort {

    NAME_ASC("name ASC", "name", Sort.Direction.ASC),
    RATING_DESC("rating DESC", "rating", Sort.Direction.DESC),
    CREATED_AT_DESC("created_at DESC", "createdAt", Sort.Direction.DESC),
    UPDATED_AT_DESC("updated_at DESC", "updatedAt", Sort.Direction.DESC);

    public static final AccountSort DEFAULT = CREATED_AT_DESC;

    /**
     * Regex accepting every spelling {@link #parse} understands (plus blank = default), for use in
     * {@code @Pattern} on request parameters so bad values fail Bean Validation with 400.
     */
    public static final String PATTERN =
            "(?i)^\\s*(name(\\s+asc)?|rating(\\s+desc)?|created_?at(\\s+desc)?|updated_?at(\\s+desc)?)?\\s*$";

    /** Bean Validation message for {@link #PATTERN} violations. */
    public static final String PATTERN_MESSAGE =
            "must be one of: name ASC, rating DESC, created_at DESC, updated_at DESC";

    private final String railsValue;
    private final String property;
    private final Sort.Direction direction;

    AccountSort(String railsValue, String property, Sort.Direction direction) {
        this.railsValue = railsValue;
        this.property = property;
        this.direction = direction;
    }

    /** The exact string Rails uses, e.g. {@code "created_at DESC"}. */
    public String railsValue() {
        return railsValue;
    }

    public Sort toSort() {
        return Sort.by(direction, property, "id");
    }

    /**
     * @param value request value; {@code null}/blank → {@link #DEFAULT}
     * @throws IllegalArgumentException for anything {@link #PATTERN} does not accept
     */
    public static AccountSort parse(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String[] parts = value.strip().split("\\s+");
        String column = parts[0].toLowerCase(Locale.ROOT).replace("_", "");
        String requestedDirection = parts.length > 1 ? parts[1].toUpperCase(Locale.ROOT) : null;
        if (parts.length > 2) {
            throw unknown(value);
        }
        AccountSort sort = switch (column) {
            case "name" -> NAME_ASC;
            case "rating" -> RATING_DESC;
            case "createdat" -> CREATED_AT_DESC;
            case "updatedat" -> UPDATED_AT_DESC;
            default -> throw unknown(value);
        };
        if (requestedDirection != null && !requestedDirection.equals(sort.direction.name())) {
            throw unknown(value);
        }
        return sort;
    }

    private static IllegalArgumentException unknown(String value) {
        return new IllegalArgumentException("sortBy '" + value + "' " + PATTERN_MESSAGE);
    }
}
