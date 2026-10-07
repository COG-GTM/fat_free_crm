package com.fatfreecrm.api.dto;

import com.fatfreecrm.customfields.CustomFieldDefinition;

public final class CustomFieldDefinitionMapper {

    private CustomFieldDefinitionMapper() {
    }

    public static CustomFieldDefinitionDto toDto(CustomFieldDefinition definition) {
        return new CustomFieldDefinitionDto(
            definition.id(),
            definition.type(),
            definition.fieldGroupId(),
            definition.position(),
            definition.pairId(),
            definition.name(),
            definition.label(),
            definition.hint(),
            definition.placeholder(),
            definition.as(),
            definition.collection(),
            definition.disabled(),
            definition.required(),
            definition.maxlength(),
            definition.minlength(),
            definition.settings());
    }
}
