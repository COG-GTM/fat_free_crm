package com.fatfreecrm.service.write;

/**
 * Rails {@code params.require(:key)} raising {@code ActionController::ParameterMissing} (missing or
 * empty root hash) → HTTP 400.
 */
public class RailsParameterMissing extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RailsParameterMissing(String key) {
        super("param is missing or the value is empty or invalid: " + key);
    }
}
