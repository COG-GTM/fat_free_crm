package com.fatfreecrm.service.write;

/**
 * Emulates a Rails-side runtime defect we mirror for parity (e.g. {@code Time.parse(nil)} raising
 * {@code TypeError} during {@code specific_time} validation) → HTTP 500 problem+json.
 */
public class RailsInternalError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RailsInternalError(String message) {
        super(message);
    }
}
