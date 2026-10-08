package com.fatfreecrm.service.history;

import com.fatfreecrm.domain.support.BaseEntity;
import com.fatfreecrm.domain.support.SubscribedUsersConverter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
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

    public RailsRowAttributes(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
        requireTable(table);
        Map<String, Object> defaults = new LinkedHashMap<>();
        jdbcTemplate.query(
            """
                SELECT column_name, data_type, column_default
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ?
                ORDER BY ordinal_position
                """,
            resultSet -> {
                while (resultSet.next()) {
                    String name = resultSet.getString("column_name");
                    if (!name.equals("custom_fields")) {
                        Object value = defaultValue(resultSet.getString("data_type"),
                            resultSet.getString("column_default"));
                        if (value != null) {
                            defaults.put(name, value);
                        }
                    }
                }
            },
            table);
        return defaults;
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

    private static Object defaultValue(String dataType, String expression) {
        if (expression == null) {
            return null;
        }
        String value = expression.trim();
        while (value.startsWith("(") && value.endsWith(")")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        int cast = value.indexOf("::");
        String literal = cast < 0 ? value : value.substring(0, cast).trim();
        return switch (dataType) {
            case "boolean" -> switch (literal.toLowerCase()) {
                case "true" -> true;
                case "false" -> false;
                default -> null;
            };
            case "smallint", "integer" -> parseInteger(literal);
            case "bigint" -> parseLong(literal);
            case "numeric", "decimal" -> parseDecimal(literal);
            case "real" -> parseFloat(literal);
            case "double precision" -> parseDouble(literal);
            case "character varying", "character", "text" -> parseString(literal);
            default -> null;
        };
    }

    private static Object parseInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Object parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Float parseFloat(String value) {
        try {
            return Float.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String parseString(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return null;
    }

    private static void requireTable(String table) {
        if (!TABLES.containsValue(table)) {
            throw new IllegalArgumentException("Unsupported Rails table " + table);
        }
    }
}
