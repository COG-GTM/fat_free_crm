package com.fatfreecrm.spike.customfields;

/** A single validation failure for a custom field, keyed by field name. */
public record CustomFieldViolation(String field, String message) {
}
