package com.fatfreecrm.api.dto;

import java.util.List;

/**
 * Autocomplete envelope, identical to the Rails {@code ApplicationController#auto_complete}
 * JSON ({@code AutoCompleteResults} in openapi.yaml): {@code { "results": [ { "id", "text" } ] }}.
 * {@code text} is the record's display name ({@code name}, or {@code full_name} for people).
 */
public record AutoCompleteResponse(List<Item> results) {

    public AutoCompleteResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public record Item(long id, String text) {
    }
}
