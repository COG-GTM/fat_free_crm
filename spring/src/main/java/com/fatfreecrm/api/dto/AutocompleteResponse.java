package com.fatfreecrm.api.dto;

import com.fatfreecrm.service.read.AutocompleteResult;
import java.util.List;

public record AutocompleteResponse(List<AutocompleteResult.Item> results) {

    public AutocompleteResponse {
        results = List.copyOf(results);
    }
}
