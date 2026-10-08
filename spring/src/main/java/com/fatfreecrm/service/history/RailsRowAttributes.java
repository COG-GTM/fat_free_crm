package com.fatfreecrm.service.history;

import com.fatfreecrm.domain.support.BaseEntity;
import com.fatfreecrm.domain.support.SubscribedUsersConverter;
import com.fatfreecrm.service.audit.ColumnDefaults;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.proxy.HibernateProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "The injected JdbcTemplate is retained by this Spring component."
)
public class RailsRowAttributes {

    private static final Map<String, String> TABLES = Map.ofEntries(
        Map.entry("Account", "accounts"),
        Map.entry("Campaign", "campaigns"),
        Map.entry("Opportunity", "opportunities"),
        Map.entry("Lead", "leads"),
        Map.entry("Contact", "contacts"),
        Map.entry("AccountContact", "account_contacts"),
        Map.entry("AccountOpportunity", "account_opportunities"),
        Map.entry("Address", "addresses"),
        Map.entry("Comment", "comments"),
        Map.entry("Email", "emails"),
        Map.entry("Task", "tasks"),
        Map.entry("User", "users")
    );

    private final JdbcTemplate jdbcTemplate;
    private final ColumnDefaults columnDefaults;

    public RailsRowAttributes(
        JdbcTemplate jdbcTemplate,
        ColumnDefaults columnDefaults
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.columnDefaults = columnDefaults;
    }

    public Map<String, Object> read(Object entity) {
        String table = TABLES.get(entityClass(entity).getSimpleName());
        if (table == null) {
            throw new IllegalArgumentException("No Rails table for " + entity.getClass().getName());
        }
        return read(table, ((BaseEntity) entity).getId());
    }

    public Map<String, Object> read(String table, long id) {
        requireTable(table);
        return jdbcTemplate.query("SELECT * FROM " + table + " WHERE id = ?", resultSet -> {
            if (!resultSet.next()) {
                throw new IllegalArgumentException("No persisted row in " + table + " for id " + id);
            }
            return attributes(resultSet);
        }, id);
    }

    public Map<String, Object> defaults(String table) {
        return columnDefaults.defaults(table);
    }

    public Map<String, Object> defaults(Object entity) {
        String table = TABLES.get(entityClass(entity).getSimpleName());
        if (table == null) {
            throw new IllegalArgumentException("No Rails table for " + entity.getClass().getName());
        }
        return defaults(table);
    }

    private static Map<String, Object> attributes(ResultSet resultSet) throws SQLException {
        Map<String, Object> attributes = new LinkedHashMap<>();
        var metadata = resultSet.getMetaData();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String name = metadata.getColumnLabel(index);
            if (!name.equals("custom_fields")) {
                Object value = resultSet.getObject(index);
                if (name.equals("subscribed_users")) {
                    value = subscribedUsers(value);
                }
                attributes.put(name, railsValue(value));
            }
        }
        return attributes;
    }

    private static Object subscribedUsers(Object value) {
        if (!(value instanceof String serialized)) {
            return value;
        }
        var users = new SubscribedUsersConverter().convertToEntityAttribute(serialized);
        return users.isEmpty() ? null : users;
    }

    private static Class<?> entityClass(Object entity) {
        return entity instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : entity.getClass();
    }

    private static Object railsValue(Object value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case Timestamp timestamp -> timestamp.toInstant();
            case OffsetDateTime dateTime -> dateTime.toInstant();
            case java.sql.Date date -> date.toLocalDate();
            case java.sql.Array array -> arrayValues(array);
            default -> value;
        };
    }

    private static Object arrayValues(java.sql.Array array) {
        try {
            Object raw = array.getArray();
            return raw instanceof Object[] values ? java.util.Arrays.asList(values) : raw;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read PostgreSQL array value", exception);
        }
    }

    private static void requireTable(String table) {
        if (!TABLES.containsValue(table)) {
            throw new IllegalArgumentException("Unsupported Rails table " + table);
        }
    }
}
