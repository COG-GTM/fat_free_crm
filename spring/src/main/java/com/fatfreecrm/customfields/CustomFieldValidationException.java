package com.fatfreecrm.customfields;

import java.util.List;
import java.util.Map;

public class CustomFieldValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final transient Map<String, List<String>> errors;

    public CustomFieldValidationException(Map<String, List<String>> errors) {
        super("Custom field validation failed");
        this.errors = Map.copyOf(errors);
    }

    public Map<String, List<String>> errors() {
        return errors;
    }
}
