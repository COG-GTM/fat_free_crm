package com.fatfreecrm.customfields;

import com.fatfreecrm.domain.support.BaseEntity;
import com.fatfreecrm.domain.support.HasCustomFields;
import com.fatfreecrm.domain.support.RailsModelType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CustomFieldReadService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomFieldReadService.class);
    private static final DateTimeFormatter RAILS_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private final CustomFieldRegistry registry;
    private final CustomFieldTypeValidator validator;
    private final CheckBoxesYamlCodec yamlCodec;

    public CustomFieldReadService(
        CustomFieldRegistry registry,
        CustomFieldTypeValidator validator,
        CheckBoxesYamlCodec yamlCodec
    ) {
        this.registry = registry;
        this.validator = validator;
        this.yamlCodec = yamlCodec;
    }

    public Map<String, Object> valuesFor(HasCustomFields entity) {
        RailsModelType type = modelType(entity);
        Map<String, Object> knownValues = new LinkedHashMap<>();
        for (CustomFieldDefinition definition : registry.definitionsFor(type)) {
            Object value = entity.getCustomFields().get(definition.name());
            if (value == null) {
                continue;
            }
            if (value instanceof Map<?, ?> marker && marker.size() == 1 && marker.containsKey("$yaml")) {
                String rawYaml = String.valueOf(marker.get("$yaml"));
                try {
                    value = yamlCodec.decode(rawYaml);
                } catch (RuntimeException exception) {
                    LOGGER.warn(
                        "Could not decode custom-field YAML for model {} id {} field {}; returning raw YAML",
                        type.railsName(),
                        entityId(entity),
                        definition.name());
                    value = rawYaml;
                }
            }
            knownValues.put(definition.name(), value);
        }
        return validator.validate(registry.definitionsFor(type), knownValues, CustomFieldTypeValidator.Mode.READ)
            .normalized();
    }

    public Map<String, Object> railsJsonValues(HasCustomFields entity) {
        RailsModelType type = modelType(entity);
        Map<String, Object> values = valuesFor(entity);
        Map<String, Object> railsValues = new LinkedHashMap<>();
        for (CustomFieldDefinition definition : registry.definitionsFor(type)) {
            if (!registry.physicalColumns(type).contains(definition.name())) {
                continue;
            }
            Object value = values.get(definition.name());
            railsValues.put(definition.name(), toRailsJsonValue(definition.as(), value));
        }
        return railsValues;
    }

    private static Object toRailsJsonValue(String as, Object value) {
        if (value == null) {
            return as.equals("check_boxes") ? java.util.List.of() : null;
        }
        String kind = switch (as) {
            case "date_pair" -> "date";
            case "datetime_pair" -> "datetime";
            default -> as;
        };
        return switch (kind) {
            case "decimal" -> decimalToRails(value);
            case "datetime" -> datetimeToRails(value);
            case "date" -> dateToRails(value);
            default -> value;
        };
    }

    private static String decimalToRails(Object value) {
        BigDecimal decimal = value instanceof BigDecimal bd
            ? bd : new BigDecimal(value.toString());
        BigDecimal normalized = decimal.stripTrailingZeros();
        if (normalized.scale() <= 0) {
            normalized = normalized.setScale(1);
        }
        return normalized.toPlainString();
    }

    private static String dateToRails(Object value) {
        return value instanceof LocalDate date ? date.toString() : LocalDate.parse(value.toString()).toString();
    }

    private static String datetimeToRails(Object value) {
        Instant instant;
        String text = value.toString();
        if (text.endsWith("Z") || text.matches(".*[+-]\\d{2}:?\\d{2}$")) {
            instant = OffsetDateTime.parse(text).toInstant();
        } else {
            instant = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        }
        return RAILS_DATETIME.format(instant.truncatedTo(ChronoUnit.MILLIS).atOffset(ZoneOffset.UTC));
    }

    private static RailsModelType modelType(HasCustomFields entity) {
        for (RailsModelType type : RailsModelType.values()) {
            if (type.entityClass().isInstance(entity)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported custom-fields entity " + entity.getClass());
    }

    private static Long entityId(HasCustomFields entity) {
        return entity instanceof BaseEntity baseEntity ? baseEntity.getId() : null;
    }
}
