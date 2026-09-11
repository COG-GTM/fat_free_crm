package com.fatfreecrm.api.dto;

import java.util.List;

/**
 * Autocomplete payload, identical to the Rails {@code auto_complete} JSON
 * ({@code AutoCompleteResults} in openapi.yaml): {@code { "results": [ { "id", "text" } ] }}.
 * {@code text} is the record's display name ({@code name} for accounts, {@code full_name} for
 * people).
 */
public record AutoCompleteResponse(List<Item> results) {

    public record Item(long id, String text) {
    }
}
