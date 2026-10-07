package com.fatfreecrm.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.customfields.CustomFieldDefinition;
import com.fatfreecrm.domain.support.RailsModelType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class CustomFieldDefinitionMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesTheFrozenOpenApiFieldPropertiesWithAllowedJsonTypes() throws IOException {
        Map<String, Object> contract = new Yaml().load(Files.readString(Path.of("../docs/migration/openapi.yaml")));
        Map<?, ?> components = (Map<?, ?>) contract.get("components");
        Map<?, ?> schemas = (Map<?, ?>) components.get("schemas");
        Map<?, ?> fieldSchema = (Map<?, ?>) schemas.get("Field");
        Map<?, ?> properties = (Map<?, ?>) fieldSchema.get("properties");

        CustomFieldDefinition definition = new CustomFieldDefinition(
            17L, "CustomField", 3L, RailsModelType.ACCOUNT, 1, 2, "cf_demo", "Demo", null, null,
            "string", List.of("a", "b"), false, true, 12, 2, null, Map.of("example", "value"));
        JsonNode serialized = objectMapper.valueToTree(CustomFieldDefinitionMapper.toDto(definition));

        Set<String> serializedProperties = new HashSet<>();
        serialized.fieldNames().forEachRemaining(serializedProperties::add);
        assertThat(serializedProperties).containsExactlyInAnyOrderElementsOf(
            properties.keySet().stream().map(String::valueOf).toList());
        properties.forEach((name, schema) -> {
            Map<?, ?> propertySchema = (Map<?, ?>) schema;
            Object declaredType = propertySchema.get("type");
            List<?> allowedTypes = declaredType instanceof List<?> types ? types : List.of(declaredType);
            assertThat(allowedTypes.stream().map(String::valueOf).toList())
                .contains(jsonType(serialized.get(String.valueOf(name))));
        });
    }

    private static String jsonType(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isIntegralNumber()) {
            return "integer";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isTextual()) {
            return "string";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isArray()) {
            return "array";
        }
        return "object";
    }
}
