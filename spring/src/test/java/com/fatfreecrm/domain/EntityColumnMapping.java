package com.fatfreecrm.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Test-side view of how an entity class maps onto its Rails table, derived purely from the JPA annotations so the
 * fidelity tests can compare every mapped attribute against the raw JDBC row without going through Hibernate.
 */
final class EntityColumnMapping {

    enum Kind { ID, BASIC, CONVERTED, MANY_TO_ONE, REF_TYPE, REF_ID }

    record MappedColumn(String column, Field field, Kind kind, AttributeConverter<Object, Object> converter) {

        Object databaseValue(Object entity) {
            Object value = read(field, entity);
            return switch (kind) {
                case ID, BASIC -> value;
                case CONVERTED -> converter.convertToDatabaseColumn(value);
                case MANY_TO_ONE -> value == null ? null : ((BaseEntity) value).getId();
                case REF_TYPE -> value == null ? null : ((PolymorphicRef) value).getType();
                case REF_ID -> value == null ? null : ((PolymorphicRef) value).getId();
            };
        }
    }

    private final Class<?> entityClass;
    private final String table;
    private final List<MappedColumn> columns = new ArrayList<>();
    private final List<Field> copiedFields = new ArrayList<>();

    EntityColumnMapping(Class<?> entityClass) {
        this.entityClass = entityClass;
        this.table = entityClass.getAnnotation(Table.class).name();
        for (Class<?> type = entityClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Transient.class)) {
                    continue;
                }
                field.setAccessible(true);
                map(field);
            }
        }
    }

    private void map(Field field) {
        if (field.isAnnotationPresent(OneToMany.class) || field.isAnnotationPresent(ManyToMany.class)) {
            return;
        }
        copiedFields.add(field);
        if (field.isAnnotationPresent(Id.class)) {
            columns.add(new MappedColumn("id", field, Kind.ID, null));
        } else if (field.isAnnotationPresent(ManyToOne.class)) {
            columns.add(new MappedColumn(field.getAnnotation(JoinColumn.class).name(), field, Kind.MANY_TO_ONE, null));
        } else if (field.isAnnotationPresent(Embedded.class)) {
            for (AttributeOverride override : field.getAnnotationsByType(AttributeOverride.class)) {
                var kind = override.name().equals("type") ? Kind.REF_TYPE : Kind.REF_ID;
                columns.add(new MappedColumn(override.column().name(), field, kind, null));
            }
        } else if (field.isAnnotationPresent(Convert.class)) {
            columns.add(new MappedColumn(columnName(field), field, Kind.CONVERTED, converter(field)));
        } else {
            columns.add(new MappedColumn(columnName(field), field, Kind.BASIC, null));
        }
    }

    private static String columnName(Field field) {
        var column = field.getAnnotation(Column.class);
        var name = column == null ? field.getName() : column.name();
        return name.replace("\"", "").toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static AttributeConverter<Object, Object> converter(Field field) {
        try {
            return (AttributeConverter<Object, Object>) field.getAnnotation(Convert.class).converter()
                .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    Class<?> entityClass() {
        return entityClass;
    }

    String table() {
        return table;
    }

    List<MappedColumn> columns() {
        return columns;
    }

    /** A new, unsaved instance carrying every mapped scalar/association value of {@code source} except its id. */
    Object copyWithoutId(Object source) {
        try {
            var constructor = entityClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object copy = constructor.newInstance();
            for (Field field : copiedFields) {
                if (!field.isAnnotationPresent(Id.class)) {
                    field.set(copy, field.get(source));
                }
            }
            return copy;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
