package com.fatfreecrm.service.query;

import java.util.ArrayList;
import java.util.List;

/** Raised when a {@code q[...]} search parameter is malformed or rejected (400 problem+json). */
public class InvalidSearchQueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ArrayList<String> invalidParameters;

    public InvalidSearchQueryException(String detail, List<String> invalidParameters) {
        super(detail);
        this.invalidParameters = new java.util.ArrayList<>(invalidParameters);
    }

    public List<String> invalidParameters() {
        return new ArrayList<>(invalidParameters);
    }
}
