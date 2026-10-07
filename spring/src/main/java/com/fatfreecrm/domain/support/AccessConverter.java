package com.fatfreecrm.domain.support;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class AccessConverter implements AttributeConverter<Access, String> {

    @Override
    public String convertToDatabaseColumn(Access access) {
        return access == null ? null : access.railsValue();
    }

    @Override
    public Access convertToEntityAttribute(String value) {
        return value == null ? null : Access.fromRailsValue(value);
    }
}
