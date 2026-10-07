package com.fatfreecrm.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CustomFieldDefinitionDto(
    Long id,
    String type,
    @JsonProperty("field_group_id") Long fieldGroupId,
    Integer position,
    @JsonProperty("pair_id") Long pairId,
    String name,
    String label,
    String hint,
    String placeholder,
    @JsonProperty("as") String as,
    List<String> collection,
    Boolean disabled,
    Boolean required,
    Integer maxlength,
    Integer minlength,
    Map<String, Object> settings
) {

    public CustomFieldDefinitionDto {
        collection = collection == null ? List.of() : new ArrayList<>(collection);
        settings = settings == null ? Map.of() : new LinkedHashMap<>(settings);
    }

    @Override
    public List<String> collection() {
        return Collections.unmodifiableList(new ArrayList<>(collection));
    }

    @Override
    public Map<String, Object> settings() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }
}
