package com.fatfreecrm.customfields;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.support.BaseEntity;
import com.fatfreecrm.domain.support.HasCustomFields;
import com.fatfreecrm.domain.support.RailsModelType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are intentionally retained by this service."
)
public class CustomFieldWriteService {

    private final CustomFieldRegistry registry;
    private final CustomFieldReadService readService;
    private final CustomFieldTypeValidator validator;
    private final CheckBoxesYamlCodec yamlCodec;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public CustomFieldWriteService(
        CustomFieldRegistry registry,
        CustomFieldReadService readService,
        CustomFieldTypeValidator validator,
        CheckBoxesYamlCodec yamlCodec,
        JdbcTemplate jdbcTemplate,
        EntityManager entityManager,
        ObjectMapper objectMapper
    ) {
        this.registry = registry;
        this.readService = readService;
        this.validator = validator;
        this.yamlCodec = yamlCodec;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> write(HasCustomFields entity, Map<String, Object> input) {
        RailsModelType type = modelType(entity);
        List<CustomFieldDefinition> applicable = applicableDefinitions(entity, type);
        Map<String, Object> merged = new LinkedHashMap<>();
        Map<String, Object> current = readService.valuesFor(entity);
        for (CustomFieldDefinition definition : applicable) {
            if (current.containsKey(definition.name())) {
                merged.put(definition.name(), current.get(definition.name()));
            }
        }
        merged.putAll(input);
        ValidationResult validation = validator.validate(applicable, merged, CustomFieldTypeValidator.Mode.WRITE);
        if (!validation.ok()) {
            throw new CustomFieldValidationException(validation.errors());
        }

        Set<String> physicalColumns = registry.physicalColumns(type);
        Map<String, String> columnTypes = registry.physicalColumnTypes(type);
        List<Object> columnParameters = new ArrayList<>();
        List<Object> jsonParameters = new ArrayList<>();
        List<String> assignments = new ArrayList<>();
        Map<String, Object> jsonOnlyValues = new LinkedHashMap<>();
        for (String name : input.keySet()) {
            CustomFieldDefinition definition = registry.find(type, name)
                .orElseThrow(() -> new IllegalStateException("Unregistered custom field " + name));
            Object normalized = validation.normalized().get(name);
            if (Objects.deepEquals(current.get(name), normalized)) {
                continue;
            }
            if (physicalColumns.contains(name)) {
                if (!name.matches("cf_[a-z0-9_]+")) {
                    throw new IllegalStateException("Invalid custom-field column " + name);
                }
                assignments.add(name + " = ?");
                columnParameters.add(columnValue(definition, normalized, columnTypes.get(name)));
            } else {
                jsonOnlyValues.put(name, normalized);
            }
        }

        String jsonExpression = "coalesce(custom_fields, '{}'::jsonb)";
        if (!jsonOnlyValues.isEmpty()) {
            for (Map.Entry<String, Object> entry : jsonOnlyValues.entrySet()) {
                String key = entry.getKey();
                if (!key.matches("cf_[a-z0-9_]+")) {
                    throw new IllegalStateException("Invalid custom-field key " + key);
                }
                if (entry.getValue() == null) {
                    jsonExpression = "(" + jsonExpression + " - '" + key + "')";
                } else {
                    jsonExpression = "jsonb_set(" + jsonExpression + ", '{" + key + "}', ?::jsonb, true)";
                    jsonParameters.add(toJson(entry.getValue()));
                }
            }
            assignments.add("custom_fields = " + jsonExpression);
        }

        if (!assignments.isEmpty()) {
            assignments.add("updated_at = (now() AT TIME ZONE 'utc')");
            columnParameters.addAll(jsonParameters);
            columnParameters.add(((BaseEntity) entity).getId());
            String sql = "UPDATE " + CustomFieldRegistry.tableName(type) + " SET "
                + String.join(", ", assignments) + " WHERE id = ?";
            jdbcTemplate.update(sql, columnParameters.toArray());
            if (entityManager.contains(entity)) {
                entityManager.refresh(entity);
            } else {
                entity = (HasCustomFields) entityManager.find(type.entityClass(), ((BaseEntity) entity).getId());
            }
        }
        return readService.valuesFor(entity);
    }

    private List<CustomFieldDefinition> applicableDefinitions(HasCustomFields entity, RailsModelType type) {
        Long id = ((BaseEntity) entity).getId();
        Set<Long> taggedGroupIds = new LinkedHashSet<>(jdbcTemplate.query(
            "SELECT DISTINCT fg.id FROM field_groups fg "
                + "LEFT JOIN taggings t ON t.taggable_id = ? AND t.taggable_type = ? "
                + "AND t.context = 'tags' "
                + "WHERE fg.klass_name = ? AND (fg.tag_id IS NULL OR fg.tag_id = t.tag_id)",
            (rs, rowNum) -> rs.getLong(1),
            id, type.railsName(), type.railsName()));
        return registry.definitionsFor(type).stream()
            .filter(definition -> definition.fieldGroupId() != null
                && taggedGroupIds.contains(definition.fieldGroupId()))
            .toList();
    }

    private Object columnValue(CustomFieldDefinition definition, Object value, String dataType) {
        if (value == null) {
            return null;
        }
        return switch (definition.as()) {
            case "check_boxes" -> yamlCodec.encode(((List<?>) value).stream().map(String::valueOf).toList());
            case "date", "date_pair" -> LocalDate.parse(value.toString());
            case "datetime", "datetime_pair" -> Timestamp.valueOf(
                LocalDateTime.parse(value.toString().substring(0, value.toString().length() - 1)));
            case "decimal" -> new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP);
            case "integer" -> {
                if ("bigint".equals(dataType)) {
                    yield Long.valueOf(value.toString());
                }
                yield Integer.valueOf(value.toString());
            }
            case "float" -> Double.valueOf(value.toString());
            case "boolean" -> value;
            default -> value.toString();
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Custom-field value cannot be represented as JSON", exception);
        }
    }

    private static RailsModelType modelType(HasCustomFields entity) {
        for (RailsModelType type : RailsModelType.values()) {
            if (type.entityClass().isInstance(entity)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported custom-fields entity " + entity.getClass());
    }
}
