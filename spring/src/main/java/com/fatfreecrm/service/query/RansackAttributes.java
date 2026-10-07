package com.fatfreecrm.service.query;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Transient;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ransack attribute catalog derived from the JPA mappings: every mapped DB column is
 * ransackable (like ransack_ui exposing all columns). {@code @ManyToOne} fields resolve
 * through their {@code @JoinColumn} name and behave as the FK scalar (typed Long).
 */
public final class RansackAttributes {

    /** Kind of coercion a predicate applies to its raw string values. */
    public enum Kind {
        STRING,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        DATE,
        DATETIME
    }

    public record Attribute(String field, Kind kind, boolean foreignKey) {
    }

    private static final Map<Class<?>, Map<String, Attribute>> CACHE = new ConcurrentHashMap<>();

    private RansackAttributes() {
    }

    /** Column name to attribute mapping for an entity, keyed by DB column name (includes {@code id}). */
    public static Map<String, Attribute> catalog(Class<?> entityClass) {
        return CACHE.computeIfAbsent(entityClass, RansackAttributes::build);
    }

    private static Map<String, Attribute> build(Class<?> entityClass) {
        Map<String, Attribute> catalog = new LinkedHashMap<>();
        catalog.put("id", new Attribute("id", Kind.INTEGER, false));
        for (Class<?> type = entityClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()
                    || field.getAnnotation(Transient.class) != null) {
                    continue;
                }
                JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                if (field.getAnnotation(ManyToOne.class) != null && joinColumn != null) {
                    catalog.putIfAbsent(
                        joinColumn.name(), new Attribute(field.getName(), Kind.INTEGER, true));
                    continue;
                }
                Column column = field.getAnnotation(Column.class);
                if (column == null) {
                    continue;
                }
                if (field.getAnnotation(Convert.class) != null
                    || Collection.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                Kind kind = kindOf(field.getType());
                if (kind == null) {
                    continue;
                }
                String name = column.name().isBlank() ? snakeCase(field.getName()) : column.name();
                catalog.putIfAbsent(name, new Attribute(field.getName(), kind, false));
            }
        }
        return catalog;
    }

    private static Kind kindOf(Class<?> type) {
        if (type == String.class) {
            return Kind.STRING;
        }
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class) {
            return Kind.INTEGER;
        }
        if (type == BigDecimal.class || type == Double.class || type == double.class
            || type == Float.class || type == float.class) {
            return Kind.DECIMAL;
        }
        if (type == Boolean.class || type == boolean.class) {
            return Kind.BOOLEAN;
        }
        if (type == LocalDate.class) {
            return Kind.DATE;
        }
        if (type == Instant.class || type == LocalDateTime.class || type == OffsetDateTime.class) {
            return Kind.DATETIME;
        }
        return null;
    }

    private static String snakeCase(String name) {
        StringBuilder result = new StringBuilder();
        for (char character : name.toCharArray()) {
            if (Character.isUpperCase(character)) {
                result.append('_').append(Character.toLowerCase(character));
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }
}
