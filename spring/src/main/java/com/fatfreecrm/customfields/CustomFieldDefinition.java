package com.fatfreecrm.customfields;

import com.fatfreecrm.domain.support.RailsModelType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CustomFieldDefinition(
    Long id,
    String type,
    Long fieldGroupId,
    RailsModelType klass,
    Integer groupPosition,
    Integer position,
    String name,
    String label,
    String hint,
    String placeholder,
    String as,
    List<String> collection,
    Boolean disabled,
    Boolean required,
    Integer minlength,
    Integer maxlength,
    Long pairId,
    Map<String, Object> settings
) {

    public CustomFieldDefinition {
        disabled = Boolean.TRUE.equals(disabled);
        required = Boolean.TRUE.equals(required);
        collection = collection == null ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(collection));
        settings = settings == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    public CustomFieldDefinition(
        String name,
        String label,
        String as,
        boolean required,
        Integer minlength,
        Integer maxlength,
        List<String> collection,
        Long id,
        Long pairId
    ) {
        this(id, "CustomField", null, RailsModelType.ACCOUNT, null, null, name, label, null, null, as,
            collection, false, required, minlength, maxlength, pairId, Map.of());
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
