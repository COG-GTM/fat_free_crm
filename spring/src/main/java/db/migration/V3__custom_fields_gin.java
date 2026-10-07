package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V3__custom_fields_gin extends BaseJavaMigration {

    private static final String EXPECTED_INDEX_DEFINITION = "USING gin (custom_fields jsonb_path_ops)";
    private static final List<IndexSpec> EXPECTED_INDEXES = List.of(
        new IndexSpec("accounts", "index_accounts_on_custom_fields"),
        new IndexSpec("campaigns", "index_campaigns_on_custom_fields"),
        new IndexSpec("contacts", "index_contacts_on_custom_fields"),
        new IndexSpec("leads", "index_leads_on_custom_fields"),
        new IndexSpec("opportunities", "index_opportunities_on_custom_fields"),
        new IndexSpec("tasks", "index_tasks_on_custom_fields"));

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        repairIndexes(context.getConnection());
    }

    public static void repairIndexes(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new FlywayException("V3 custom-field indexes require an autocommit connection");
        }
        String schema = currentSchema(connection);
        for (IndexSpec index : EXPECTED_INDEXES) {
            if (hasExpectedDefinition(connection, schema, index)) {
                continue;
            }
            if (indexExists(connection, schema, index.name())) {
                execute(connection, "DROP INDEX CONCURRENTLY " + qualifiedName(schema, index.name()));
            }
            execute(
                connection,
                "CREATE INDEX CONCURRENTLY IF NOT EXISTS " + quoteIdentifier(index.name())
                    + " ON " + qualifiedName(schema, index.table())
                    + " USING gin (custom_fields jsonb_path_ops)");
        }

        List<String> invalidIndexes = new ArrayList<>();
        for (IndexSpec index : EXPECTED_INDEXES) {
            if (!hasExpectedDefinition(connection, schema, index)) {
                invalidIndexes.add(index.name());
            }
        }
        if (!invalidIndexes.isEmpty()) {
            throw new FlywayException(
                "Custom-field GIN indexes are missing or invalid: " + String.join(", ", invalidIndexes));
        }
    }

    private static String currentSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT current_schema()")) {
            if (resultSet.next()) {
                String schema = resultSet.getString(1);
                if (schema != null) {
                    return schema;
                }
            }
        }
        throw new FlywayException("V3 could not determine the current schema");
    }

    private static boolean indexExists(Connection connection, String schema, String indexName) throws SQLException {
        String sql = "SELECT EXISTS ("
            + "SELECT 1 FROM pg_class index_class "
            + "JOIN pg_namespace index_schema ON index_schema.oid = index_class.relnamespace "
            + "JOIN pg_index index_info ON index_info.indexrelid = index_class.oid "
            + "WHERE index_schema.nspname = ? AND index_class.relname = ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    private static boolean hasExpectedDefinition(
        Connection connection,
        String schema,
        IndexSpec expected
    ) throws SQLException {
        String sql = "SELECT index_info.indisvalid, index_info.indnatts, index_info.indnkeyatts, "
            + "index_info.indpred IS NULL, index_info.indexprs IS NULL, table_class.relname, "
            + "table_schema.nspname, access_method.amname, pg_get_indexdef(index_class.oid) "
            + "FROM pg_class index_class "
            + "JOIN pg_namespace index_schema ON index_schema.oid = index_class.relnamespace "
            + "JOIN pg_index index_info ON index_info.indexrelid = index_class.oid "
            + "JOIN pg_class table_class ON table_class.oid = index_info.indrelid "
            + "JOIN pg_namespace table_schema ON table_schema.oid = table_class.relnamespace "
            + "JOIN pg_am access_method ON access_method.oid = index_class.relam "
            + "WHERE index_schema.nspname = ? AND index_class.relname = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, expected.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                    && resultSet.getBoolean(1)
                    && resultSet.getInt(2) == 1
                    && resultSet.getInt(3) == 1
                    && resultSet.getBoolean(4)
                    && resultSet.getBoolean(5)
                    && expected.table().equals(resultSet.getString(6))
                    && schema.equals(resultSet.getString(7))
                    && "gin".equals(resultSet.getString(8))
                    && resultSet.getString(9).contains(EXPECTED_INDEX_DEFINITION);
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String qualifiedName(String schema, String name) {
        return quoteIdentifier(schema) + "." + quoteIdentifier(name);
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private record IndexSpec(String table, String name) {
    }
}
