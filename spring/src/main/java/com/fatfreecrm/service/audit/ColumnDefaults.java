package com.fatfreecrm.service.audit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.hibernate.proxy.HibernateProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "The injected JdbcTemplate is retained by this Spring component."
)
public class ColumnDefaults {

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
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public ColumnDefaults(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> defaults(Object entity) {
        Class<?> entityClass = entity instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : entity.getClass();
        String table = TABLES.get(entityClass.getSimpleName());
        if (table == null) {
            throw new IllegalArgumentException("No Rails table for " + entity.getClass().getName());
        }
        return defaults(table);
    }

    public Map<String, Object> defaults(String table) {
        if (!TABLES.containsValue(table)) {
            throw new IllegalArgumentException("Unsupported Rails table " + table);
        }
        return cache.computeIfAbsent(table, this::loadDefaults);
    }

    private Map<String, Object> loadDefaults(String table) {
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
                        Object value = parseDefault(
                            resultSet.getString("data_type"), resultSet.getString("column_default"));
                        if (value != null) {
                            defaults.put(name, value);
                        }
                    }
                }
            },
            table);
        return Map.copyOf(defaults);
    }

    static Object parseDefault(String dataType, String expression) {
        if (expression == null) {
            return null;
        }
        String value = expression.trim();
        int cast = value.indexOf("::");
        String literal = cast < 0 ? value : value.substring(0, cast).trim();
        while (literal.startsWith("(") && literal.endsWith(")")) {
            literal = literal.substring(1, literal.length() - 1).trim();
        }
        try {
            return switch (dataType) {
                case "boolean" -> switch (literal.toLowerCase()) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> null;
                };
                case "smallint", "integer" -> Integer.valueOf(literal);
                case "bigint" -> Long.valueOf(literal);
                case "numeric", "decimal" -> new BigDecimal(literal);
                case "real" -> Float.valueOf(literal);
                case "double precision" -> Double.valueOf(literal);
                case "character varying", "character", "text" -> stringLiteral(literal);
                case "date" -> LocalDate.parse(stringLiteral(literal));
                case "timestamp without time zone" -> LocalDateTime.parse(stringLiteral(literal).replace(' ', 'T'));
                case "timestamp with time zone" -> timestampLiteral(stringLiteral(literal));
                default -> null;
            };
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String stringLiteral(String literal) {
        if (literal.length() >= 2 && literal.startsWith("'") && literal.endsWith("'")) {
            return literal.substring(1, literal.length() - 1).replace("''", "'");
        }
        return null;
    }

    private static Instant timestampLiteral(String value) {
        return OffsetDateTime.parse(value.replace(' ', 'T')).toInstant();
    }
}
