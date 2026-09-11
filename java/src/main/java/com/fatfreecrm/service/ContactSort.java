package com.fatfreecrm.service;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Sort;

/**
 * The sort orders a contact list accepts — exactly the Rails
 * {@code sortable by: ["first_name ASC", "last_name ASC", "created_at DESC", "updated_at DESC"]}
 * list in app/models/entities/contact.rb, plus the bare column names as shorthand for the
 * same direction. Default is {@code created_at DESC}. Ties are broken by {@code id} so pages
 * are stable.
 */
public enum ContactSort {

    FIRST_NAME_ASC("first_name ASC", "first_name", Sort.by(Sort.Direction.ASC, "firstName")),
    LAST_NAME_ASC("last_name ASC", "last_name", Sort.by(Sort.Direction.ASC, "lastName")),
    CREATED_AT_DESC("created_at DESC", "created_at", Sort.by(Sort.Direction.DESC, "createdAt")),
    UPDATED_AT_DESC("updated_at DESC", "updated_at", Sort.by(Sort.Direction.DESC, "updatedAt"));

    public static final ContactSort DEFAULT = CREATED_AT_DESC;

    /**
     * Regular expression matching every value {@link #parse(String)} accepts; for
     * {@code @Pattern} on request parameters so unknown values fail Bean Validation (400).
     */
    public static final String PATTERN = "(first_name|last_name)( ASC)?|(created_at|updated_at)( DESC)?";

    private final String railsValue;
    private final String shortValue;
    private final Sort sort;

    ContactSort(String railsValue, String shortValue, Sort sort) {
        this.railsValue = railsValue;
        this.shortValue = shortValue;
        this.sort = sort;
    }

    public String railsValue() {
        return railsValue;
    }

    public String shortValue() {
        return shortValue;
    }

    public Sort sort() {
        return sort.and(Sort.by(Sort.Direction.ASC, "id"));
    }

    /**
     * @param value request value, e.g. {@code "last_name ASC"} or {@code "last_name"}; blank
     *              or {@code null} selects {@link #DEFAULT}
     * @throws IllegalArgumentException for any other value
     */
    public static ContactSort parse(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String wanted = value.strip();
        return Arrays.stream(values())
                .filter(s -> s.railsValue.equals(wanted) || s.shortValue.equals(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown sortBy '" + value + "'; expected one of " + acceptedValues()));
    }

    public static String acceptedValues() {
        return Arrays.stream(values())
                .flatMap(s -> Stream.of(s.railsValue, s.shortValue))
                .collect(Collectors.joining(", "));
    }
}
