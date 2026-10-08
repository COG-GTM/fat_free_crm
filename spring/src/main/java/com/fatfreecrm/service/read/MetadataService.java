package com.fatfreecrm.service.read;

import com.fatfreecrm.api.dto.CustomFieldDefinitionDto;
import com.fatfreecrm.api.dto.CustomFieldDefinitionMapper;
import com.fatfreecrm.api.dto.EntityMetadataResponse;
import com.fatfreecrm.customfields.CustomFieldRegistry;
import com.fatfreecrm.domain.support.RailsModelType;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MetadataService {

    private static final Map<String, RailsModelType> MODEL_TYPES = Map.of(
        "accounts", RailsModelType.ACCOUNT,
        "campaigns", RailsModelType.CAMPAIGN,
        "contacts", RailsModelType.CONTACT,
        "leads", RailsModelType.LEAD,
        "opportunities", RailsModelType.OPPORTUNITY,
        "tasks", RailsModelType.TASK
    );

    private final CustomFieldRegistry customFieldRegistry;

    public MetadataService(CustomFieldRegistry customFieldRegistry) {
        this.customFieldRegistry = customFieldRegistry;
    }

    @Transactional(readOnly = true)
    public EntityMetadataResponse metadata(String entity) {
        RailsModelType type = MODEL_TYPES.get(entity);
        if (type == null) {
            throw new EntityNotFoundException("Metadata for " + entity + " was not found");
        }
        List<CustomFieldDefinitionDto> fields = customFieldRegistry.definitionsFor(type).stream()
            .map(CustomFieldDefinitionMapper::toDto)
            .toList();
        return new EntityMetadataResponse(entity, fields);
    }
}
