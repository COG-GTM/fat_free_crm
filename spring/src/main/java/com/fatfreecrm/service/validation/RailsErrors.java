package com.fatfreecrm.service.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered attribute → messages map mirroring {@code record.errors.as_json}: values are the bare
 * messages ActiveModel collects in validator order (caret messages stay verbatim), rendered by the
 * exception handler as {@code {"errors": {attr: [msg, ...]}}}.
 */
public final class RailsErrors {

    private final Map<String, List<String>> errors = new LinkedHashMap<>();

    public RailsErrors add(String attribute, String message) {
        errors.computeIfAbsent(attribute, ignored -> new ArrayList<>()).add(message);
        return this;
    }

    /** ActiveModel message lookup via {@link ActiveModelMessages#generateMessage}. */
    public RailsErrors add(
        ActiveModelMessages messages,
        String model,
        String attribute,
        String key
    ) {
        return add(attribute, messages.generateMessage(model, attribute, key, null));
    }

    public boolean isEmpty() {
        return errors.isEmpty();
    }

    public Map<String, List<String>> asMap() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        errors.forEach((attribute, messages) -> copy.put(attribute, List.copyOf(messages)));
        return java.util.Collections.unmodifiableMap(copy);
    }
}
