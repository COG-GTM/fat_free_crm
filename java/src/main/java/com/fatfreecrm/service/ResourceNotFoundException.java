package com.fatfreecrm.service;

/**
 * Thrown by services when a record does not exist, is soft-deleted, or is not visible to the
 * current user (Rails also answers 404 for records filtered out by {@code Model.my(user)}).
 * Rendered as a 404 problem+json by {@code ApiExceptionHandler}.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String resource, Long id) {
        super(resource + " with id " + id + " not found");
    }
}
