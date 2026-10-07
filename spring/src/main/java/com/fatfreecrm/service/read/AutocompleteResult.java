package com.fatfreecrm.service.read;

import java.util.List;

public record AutocompleteResult(List<Item> results) {

    public AutocompleteResult {
        results = List.copyOf(results);
    }

    public record Item(Long id, String text) {
    }
}
