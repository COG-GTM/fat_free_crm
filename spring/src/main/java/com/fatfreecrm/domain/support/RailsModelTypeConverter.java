package com.fatfreecrm.domain.support;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class RailsModelTypeConverter implements AttributeConverter<RailsModelType, String> {

    @Override
    public String convertToDatabaseColumn(RailsModelType type) {
        return type == null ? null : type.railsName();
    }

    @Override
    public RailsModelType convertToEntityAttribute(String value) {
        return value == null ? null : RailsModelType.fromRailsName(value);
    }
}
