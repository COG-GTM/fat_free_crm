package com.fatfreecrm.api.dto;

import java.util.List;

public record EntityMetadataResponse(String entity, List<CustomFieldDefinitionDto> fields) {

    public EntityMetadataResponse {
        fields = List.copyOf(fields);
    }
}
