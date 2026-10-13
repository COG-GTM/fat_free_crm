package com.fatfreecrm.service.validation;

import java.util.List;
import java.util.Map;

/** Rails {@code record.save} validation failure → 422 {@code {"errors": {attr: [messages]}}}. */
public class RailsValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final transient Map<String, List<String>> errors;

    public RailsValidationException(Map<String, List<String>> errors) {
        super("Rails-style validation failed");
        this.errors = errors;
    }

    public RailsValidationException(RailsErrors errors) {
        this(errors.asMap());
    }

    public Map<String, List<String>> errors() {
        return errors;
    }
}
