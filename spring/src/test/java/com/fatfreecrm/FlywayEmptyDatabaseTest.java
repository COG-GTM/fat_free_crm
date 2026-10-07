package com.fatfreecrm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import db.migration.V3__custom_fields_gin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class FlywayEmptyDatabaseTest extends AbstractPostgresIntegrationTest {

    private static final Map<String, String> CUSTOM_FIELD_INDEX_TABLES = Map.of(
        "index_accounts_on_custom_fields", "accounts",
        "index_campaigns_on_custom_fields", "campaigns",
        "index_contacts_on_custom_fields", "contacts",
        "index_leads_on_custom_fields", "leads",
        "index_opportunities_on_custom_fields", "opportunities",
        "index_tasks_on_custom_fields", "tasks");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void appliesAndValidatesTheBaselineAndCustomFieldMigrations() {
        var applied = flyway.info().applied();
        assertThat(applied).hasSize(3);
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getType().name()).isEqualTo("SQL");
        assertThat(applied[0].getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(applied[1].getVersion().getVersion()).isEqualTo("2");
        assertThat(applied[1].getType().name()).isEqualTo("SQL");
        assertThat(applied[2].getVersion().getVersion()).isEqualTo("3");
        assertThat(applied[2].getType().name()).isEqualTo("JDBC");
        assertCustomFieldIndexesValid(customFieldIndexStates());
        ValidateResult validation = flyway.validateWithResult();
        assertThat(validation.validationSuccessful)
            .withFailMessage("Flyway validation failed: %s", validation.getAllErrorMessages())
            .isTrue();
    }

    @Test
    void baselineContainsRailsTablesAndMigrations() throws IOException {
        var tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String.class
        );
        assertThat(tables).contains(
            "accounts", "contacts", "leads", "opportunities", "campaigns", "users",
            "tasks", "comments", "fields", "settings", "versions",
            "schema_migrations", "ar_internal_metadata"
        );

        int migrationFileCount;
        try (var migrationFiles = Files.list(Path.of("../db/migrate"))) {
            migrationFileCount = Math.toIntExact(
                migrationFiles.filter(path -> path.toString().endsWith(".rb")).count()
            );
        }
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM schema_migrations WHERE version = '20260413041448'",
            Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM schema_migrations", Integer.class))
            .isEqualTo(migrationFileCount);
    }

    @Test
    void retryRebuildsOnlyInvalidIndexAndPreservesValidIndexOids() throws SQLException {
        Map<String, IndexState> originalIndexes = customFieldIndexStates();
        assertCustomFieldIndexesValid(originalIndexes);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class)).isTrue();
        try {
            assertThat(jdbcTemplate.update(
                "UPDATE pg_index SET indisvalid = false "
                    + "WHERE indexrelid = 'index_tasks_on_custom_fields'::regclass")).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT NOT index_info.indisvalid FROM pg_class index_class "
                    + "JOIN pg_namespace index_schema ON index_schema.oid = index_class.relnamespace "
                    + "JOIN pg_index index_info ON index_info.indexrelid = index_class.oid "
                    + "WHERE index_schema.nspname = current_schema() "
                    + "AND index_class.relname = 'index_tasks_on_custom_fields'",
                Boolean.class)).isTrue();

            repairIndexes();

            Map<String, IndexState> retriedIndexes = customFieldIndexStates();
            assertCustomFieldIndexesValid(retriedIndexes);
            for (String indexName : CUSTOM_FIELD_INDEX_TABLES.keySet()) {
                if ("index_tasks_on_custom_fields".equals(indexName)) {
                    assertThat(retriedIndexes.get(indexName).oid())
                        .isNotEqualTo(originalIndexes.get(indexName).oid());
                } else {
                    assertThat(retriedIndexes.get(indexName).oid())
                        .isEqualTo(originalIndexes.get(indexName).oid());
                }
            }
        } finally {
            if (!areCustomFieldIndexesValid(customFieldIndexStates())) {
                repairIndexes();
            }
        }
    }

    private Map<String, IndexState> customFieldIndexStates() {
        return jdbcTemplate.query(
            "SELECT index_class.relname, table_class.relname, access_method.amname, "
                + "index_info.indisvalid, pg_get_indexdef(index_class.oid), index_class.oid::bigint "
                + "FROM pg_class index_class "
                + "JOIN pg_namespace index_schema ON index_schema.oid = index_class.relnamespace "
                + "JOIN pg_index index_info ON index_info.indexrelid = index_class.oid "
                + "JOIN pg_class table_class ON table_class.oid = index_info.indrelid "
                + "JOIN pg_am access_method ON access_method.oid = index_class.relam "
                + "WHERE index_schema.nspname = current_schema() "
                + "AND index_class.relname IN ('index_accounts_on_custom_fields', "
                + "'index_campaigns_on_custom_fields', 'index_contacts_on_custom_fields', "
                + "'index_leads_on_custom_fields', 'index_opportunities_on_custom_fields', "
                + "'index_tasks_on_custom_fields')",
            rs -> {
                Map<String, IndexState> indexes = new LinkedHashMap<>();
                while (rs.next()) {
                    indexes.put(rs.getString(1), new IndexState(
                        rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5), rs.getLong(6)));
                }
                return indexes;
            });
    }

    private static void assertCustomFieldIndexesValid(Map<String, IndexState> indexes) {
        assertThat(indexes.keySet()).containsExactlyInAnyOrderElementsOf(CUSTOM_FIELD_INDEX_TABLES.keySet());
        CUSTOM_FIELD_INDEX_TABLES.forEach((indexName, tableName) -> {
            IndexState index = indexes.get(indexName);
            assertThat(index.tableName()).isEqualTo(tableName);
            assertThat(index.accessMethod()).isEqualTo("gin");
            assertThat(index.valid()).isTrue();
            assertThat(index.definition()).contains("USING gin (custom_fields jsonb_path_ops)");
        });
    }

    private static boolean areCustomFieldIndexesValid(Map<String, IndexState> indexes) {
        if (!indexes.keySet().equals(CUSTOM_FIELD_INDEX_TABLES.keySet())) {
            return false;
        }
        return CUSTOM_FIELD_INDEX_TABLES.entrySet().stream().allMatch(entry -> {
            IndexState index = indexes.get(entry.getKey());
            return index.valid()
                && index.accessMethod().equals("gin")
                && index.tableName().equals(entry.getValue())
                && index.definition().contains("USING gin (custom_fields jsonb_path_ops)");
        });
    }

    private void repairIndexes() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getAutoCommit()).isTrue();
            V3__custom_fields_gin.repairIndexes(connection);
        }
    }

    private record IndexState(String tableName, String accessMethod, boolean valid, String definition, long oid) {
    }
}
