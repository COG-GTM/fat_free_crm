package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;


/**
 * {@code dbAssert} capture + normalization for write cases. Each entry is
 * {@code {table, where, orderBy, columns, exclude, volatile, yamlVolatile: {column: [keys]}}} —
 * captured rows after each side's request are normalized (volatile columns compared as
 * null/non-null, yamlVolatile masking those top-level keys inside YAML text), then diffed like a
 * JSON body: differences surface as un-allowlistable contract differences.
 */
public final class DbAssert {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public DbAssert(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    public static DbAssert fromProperties() {
        String url = prop("contract.dbUrl", "CONTRACT_DB_URL", "jdbc:postgresql://127.0.0.1:5433/ffcrm_contract");
        if (!url.startsWith("jdbc:")) {
            java.net.URI uri = java.net.URI.create(url);
            url = "jdbc:postgresql://" + uri.getHost()
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getPath();
        }
        return new DbAssert(url,
            prop("contract.dbUser", "CONTRACT_DB_USER", "postgres"),
            prop("contract.dbPassword", "CONTRACT_DB_PASSWORD", "postgres"));
    }

    private static String prop(String name, String env, String fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Parse + validate the case's dbAssert list. */
    public static List<Assert> parse(JsonNode dbAssert) {
        List<Assert> asserts = new ArrayList<>();
        if (dbAssert == null || dbAssert.isNull()) {
            return asserts;
        }
        if (!dbAssert.isArray()) {
            throw new IllegalArgumentException("dbAssert must be a list");
        }
        for (JsonNode node : dbAssert) {
            String table = node.path("table").asText("");
            if (!table.matches("[a-z][a-z0-9_]*") && !table.equals("information_schema.columns")) {
                throw new IllegalArgumentException("dbAssert table must be a safe table name: " + table);
            }
            String where = node.path("where").asText("");
            String orderBy = node.path("orderBy").asText("id");
            List<String> columns = strings(node.get("columns"));
            List<String> exclude = strings(node.get("exclude"));
            List<String> volatileColumns = strings(node.get("volatile"));
            ObjectNode yamlVolatile = node.get("yamlVolatile") instanceof ObjectNode object
                ? object : NODES.objectNode();
            asserts.add(new Assert(table, where, orderBy, columns, exclude, volatileColumns,
                yamlVolatile));
        }
        return asserts;
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    /** Capture rows for one assertion as normalized JSON. */
    public JsonNode capture(Assert assertion) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT ");
        if (assertion.columns().isEmpty()) {
            sql.append("*");
        } else {
            sql.append(String.join(", ", assertion.columns()));
        }
        sql.append(" FROM ").append(assertion.table());
        if (!assertion.where().isBlank()) {
            sql.append(" WHERE ").append(assertion.where());
        }
        if (!assertion.orderBy().isBlank()) {
            sql.append(" ORDER BY ").append(assertion.orderBy());
        }
        ArrayNode rows = NODES.arrayNode();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             PreparedStatement statement = connection.prepareStatement(sql.toString());
             ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            while (resultSet.next()) {
                ObjectNode row = NODES.objectNode();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    String column = metadata.getColumnLabel(index);
                    if (assertion.exclude().contains(column)) {
                        continue;
                    }
                    Object value = resultSet.getObject(index);
                    if (assertion.volatile_().contains(column)) {
                        row.put(column, value != null ? "__volatile__" : null);
                    } else if (assertion.yamlVolatile().has(column) && value != null) {
                        row.put(column, maskYaml(value.toString(),
                            strings(assertion.yamlVolatile().get(column))));
                    } else {
                        row.put(column, value == null ? null : value.toString());
                    }
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Replace the top-level {@code key: value} scalar entries named in {@code keys} inside a
     * Psych-style YAML document (versions.object/object_changes) with {@code __volatile__}. Handles
     * both {@code key: <scalar>} lines and {@code key:\n- a\n- b} sequence entries (masks each
     * scalar element).
     */
    static String maskYaml(String yaml, List<String> keys) {
        if (keys.isEmpty()) {
            return yaml;
        }
        StringBuilder result = new StringBuilder();
        String[] lines = yaml.split("\n", -1);
        boolean masking = false; // inside a masked top-level key's value block
        for (String line : lines) {
            String trimmed = line.trim();
            boolean topLevel = !line.startsWith(" ") && !line.startsWith("-");
            int colon = trimmed.indexOf(':');
            String name = colon > 0 ? trimmed.substring(0, colon) : null;
            if (topLevel && name != null && keys.contains(name)) {
                masking = true;
                String rest = trimmed.substring(colon + 1).trim();
                if (rest.isEmpty() || rest.startsWith("!")) {
                    result.append(trimmed).append('\n');
                } else {
                    result.append(name).append(": __volatile__\n");
                }
                continue;
            }
            if (topLevel) {
                masking = false;
            }
            if (masking) {
                if (trimmed.startsWith("- ") && !trimmed.startsWith("- !")) {
                    result.append("- __volatile__\n");
                    continue;
                }
                if (trimmed.startsWith("utc:") || trimmed.startsWith("time:")) {
                    // Mask only the timestamp literal: keep &N anchors and *N aliases so a
                    // shared-Time anchor emitted as `utc: *1` still diffs against `utc: &N <ts>`.
                    String indent = line.substring(0, line.indexOf(trimmed));
                    String key = trimmed.substring(0, trimmed.indexOf(':') + 1);
                    String rest = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                    if (rest.startsWith("*")) {
                        result.append(indent).append(key).append(' ').append(rest)
                            .append('\n');
                    } else if (rest.startsWith("&")) {
                        int space = rest.indexOf(' ');
                        String anchor = space < 0 ? rest : rest.substring(0, space);
                        result.append(indent).append(key).append(' ').append(anchor)
                            .append(" __volatile__\n");
                    } else {
                        result.append(indent).append(key).append(" __volatile__\n");
                    }
                    continue;
                }
            }
            result.append(line).append('\n');
        }
        return result.toString();
    }

    public record Assert(
        String table,
        String where,
        String orderBy,
        List<String> columns,
        List<String> exclude,
        List<String> volatile_,
        ObjectNode yamlVolatile
    ) {
    }
}
