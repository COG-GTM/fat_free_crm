package com.fatfreecrm.service.query;

/** Raised when the {@code page} parameter parses below 1 (404 problem+json, like Rails WillPaginate). */
public class InvalidPageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidPageException(String detail) {
        super(detail);
    }
}
