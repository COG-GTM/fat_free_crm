package com.fatfreecrm.spike.customfields;

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

    public boolean ok() {
        return errors.isEmpty();
    }
}
