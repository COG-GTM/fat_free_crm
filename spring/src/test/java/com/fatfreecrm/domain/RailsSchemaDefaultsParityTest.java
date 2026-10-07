package com.fatfreecrm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rails never sends a column it did not touch, so {@code Account.new.rating} is whatever the
 * schema default says. Hibernate inserts every mapped column, so a freshly constructed entity must
 * already carry those schema defaults or a Spring-created row would differ from a Rails-created one.
 */
class RailsSchemaDefaultsParityTest extends AbstractPostgresIntegrationTest {

    private static final Set<Class<?>> MAPPED_BEFORE_AB_265 = Set.of(User.class, Group.class);

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void freshEntitiesCarryEveryRailsColumnDefault() throws ReflectiveOperationException {
        List<String> checkedColumns = new ArrayList<>();
        for (EntityType<?> entityType : entityManagerFactory.getMetamodel().getEntities()) {
            Class<?> entityClass = entityType.getJavaType();
            if (MAPPED_BEFORE_AB_265.contains(entityClass)) {
                continue;
            }
            String table = entityClass.getAnnotation(Table.class).name();
            Map<String, String> defaults = columnDefaults(table);
            Object fresh = construct(entityClass);
            for (Attribute<?, ?> attribute : entityType.getAttributes()) {
                if (attribute.isCollection() || !(attribute.getJavaMember() instanceof Field field)) {
                    continue;
                }
                String column = columnName(field);
                if (column == null) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(fresh);
                if (field.isAnnotationPresent(Convert.class) && value instanceof List<?> list && list.isEmpty()) {
                    value = null;
                }
                assertThat(defaults).as("%s.%s exists", table, column).containsKey(column);
                assertThat(value == null ? null : String.valueOf(value))
                    .as("fresh %s.%s must equal the Rails schema default", table, column)
                    .isEqualTo(defaults.get(column));
                checkedColumns.add(table + "." + column);
            }
        }
        assertThat(checkedColumns)
            .contains("accounts.access", "accounts.rating", "accounts.contacts_count", "activities.action",
                "activities.private", "comments.state", "contacts.do_not_call", "fields.minlength",
                "leads.first_name", "research_tools.enabled", "tags.taggings_count", "tasks.name",
                "tasks.subscribed_users", "opportunities.subscribed_users");
    }

    private static Object construct(Class<?> entityClass) throws ReflectiveOperationException {
        Constructor<?> constructor = entityClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static String columnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null) {
            return column.name().replace("\"", "");
        }
        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
        return joinColumn == null ? null : joinColumn.name().replace("\"", "");
    }

    private Map<String, String> columnDefaults(String table) {
        Map<String, String> defaults = new HashMap<>();
        jdbcTemplate.query(
            "SELECT column_name, column_default FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ?",
            resultSet -> {
                defaults.put(resultSet.getString(1), railsDefault(resultSet.getString(2)));
            },
            table
        );
        return defaults;
    }

    private static String railsDefault(String columnDefault) {
        if (columnDefault == null || columnDefault.startsWith("nextval(")) {
            return null;
        }
        String literal = columnDefault;
        int cast = literal.indexOf("::");
        if (cast >= 0) {
            literal = literal.substring(0, cast);
        }
        if (literal.startsWith("'") && literal.endsWith("'")) {
            literal = literal.substring(1, literal.length() - 1).replace("''", "'");
        }
        return literal;
    }
}
