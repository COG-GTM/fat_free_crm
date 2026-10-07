package com.fatfreecrm.spike.customfields;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Jackson-backed converter for the {@code custom_fields} JSONB column.
 * NOTE: the converted type is {@link String}, so without a cast the value is
 * bound as varchar and PostgreSQL rejects it ("column is of type jsonb but
 * expression is of type character varying"). The entity fixes this with
 * {@code @ColumnTransformer(write = "?::jsonb")}.
 */
@Converter(autoApply = false)
public class CustomFieldsJsonConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        try {
            return MAPPER.readValue(dbData, new TypeReference<Map<String, Object>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
