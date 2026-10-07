package com.fatfreecrm.customfields;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of {@link CustomFieldTypeValidator#validate}: the normalized value map
 * (JSON-ready Java values) plus per-field error lists matching the Rails 422
 * shape {@code {"field": ["message"]}}.
 */
public record ValidationResult(
    Map<String, Object> normalized,
    Map<String, List<String>> errors) {

    public ValidationResult {
        Map<String, Object> normalizedCopy = new LinkedHashMap<>();
        normalized.forEach((key, value) ->
            normalizedCopy.put(key, value instanceof List<?> list ? List.copyOf(list) : value));
        normalized = normalizedCopy;
        Map<String, List<String>> errorsCopy = new LinkedHashMap<>();
        errors.forEach((key, value) -> errorsCopy.put(key, new ArrayList<>(value)));
        errors = errorsCopy;
    }

    @Override
    public Map<String, Object> normalized() {
        Map<String, Object> copy = new LinkedHashMap<>();
        normalized.forEach((key, value) -> copy.put(
            key, value instanceof List<?> list ? List.copyOf(list) : value));
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public Map<String, List<String>> errors() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        errors.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }

    public boolean ok() {
        return errors.isEmpty();
    }
}
