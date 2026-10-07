package com.fatfreecrm.spike.customfields;

import java.util.List;

/**
 * Minimal mirror of the Rails {@code fields} row for a custom field.
 * {@code name} is the cf_-prefixed column/key name; {@code as} is the field type
 * (Rails BASE_FIELD_TYPES keys plus "date_pair"/"datetime_pair" half-fields);
 * {@code pairId} links the second half of a pair to the first half's {@code id}.
 */
public record FieldDefinition(
    String name,
    String label,
    String as,
    boolean required,
    Integer minlength,
    Integer maxlength,
    List<String> collection,
    Long id,
    Long pairId) {
}
