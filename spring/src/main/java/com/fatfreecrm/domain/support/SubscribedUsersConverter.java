package com.fatfreecrm.domain.support;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

@Converter
public class SubscribedUsersConverter implements AttributeConverter<List<Long>, String> {

    @Override
    public String convertToDatabaseColumn(List<Long> users) {
        if (users == null || users.isEmpty()) {
            return null;
        }
        StringBuilder yaml = new StringBuilder("---\n");
        for (Long userId : users) {
            yaml.append("- ").append(userId).append('\n');
        }
        return yaml.toString();
    }

    @Override
    public List<Long> convertToEntityAttribute(String value) {
        if (value == null || value.equals("--- []\n")) {
            return new ArrayList<>();
        }
        Object parsed = RailsYaml.read(value);
        if (!(parsed instanceof List<?> items)) {
            throw new IllegalArgumentException("Expected subscribed_users YAML sequence");
        }
        List<Long> result = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Byte || item instanceof Short || item instanceof Integer || item instanceof Long) {
                result.add(((Number) item).longValue());
            } else if (item instanceof BigInteger integer && integer.bitLength() < Long.SIZE) {
                result.add(integer.longValue());
            } else {
                throw new IllegalArgumentException("Invalid subscribed user id: " + item);
            }
        }
        return result;
    }
}
