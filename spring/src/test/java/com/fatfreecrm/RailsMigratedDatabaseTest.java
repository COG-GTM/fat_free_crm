package com.fatfreecrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@SpringBootTest
@AutoConfigureMockMvc
class RailsMigratedDatabaseTest {

    @ServiceConnection
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("fat_free_crm_rails")
        .withUsername("postgres")
        .withPassword("postgres");

    static {
        POSTGRES.start();
        try {
            POSTGRES.copyFileToContainer(
                MountableFile.forClasspathResource("db/rails/rails_schema.sql"),
                "/tmp/rails_schema.sql"
            );
            var load = POSTGRES.execInContainer(
                "psql", "-U", "postgres", "-d", "fat_free_crm_rails",
                "-v", "ON_ERROR_STOP=1", "-f", "/tmp/rails_schema.sql"
            );
            if (load.getExitCode() != 0) {
                fail("Loading the Rails schema fixture failed: %s", load.getStderr());
            }
            var sentinel = POSTGRES.execInContainer(
                "psql", "-U", "postgres", "-d", "fat_free_crm_rails",
                "-v", "ON_ERROR_STOP=1", "-c",
                "INSERT INTO settings (name, value) VALUES ('ab263-sentinel', 'preserved')"
            );
            if (sentinel.getExitCode() != 0) {
                fail("Inserting the Rails sentinel row failed: %s", sentinel.getStderr());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExceptionInInitializerError(exception);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void baselinesExistingRailsSchemaAndAppliesCustomFieldMigrations() {
        var applied = flyway.info().applied();
        assertThat(applied).hasSize(3);
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getType().name()).isEqualTo("BASELINE");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE type IN ('SQL', 'JDBC')",
            Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name IN "
                + "('accounts', 'campaigns', 'contacts', 'leads', 'opportunities', 'tasks') "
                + "AND column_name = 'custom_fields'",
            Integer.class
        )).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' "
                + "AND indexname LIKE 'index_%_on_custom_fields'",
            Integer.class
        )).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM (VALUES "
                + "('index_accounts_on_custom_fields', 'accounts'), "
                + "('index_campaigns_on_custom_fields', 'campaigns'), "
                + "('index_contacts_on_custom_fields', 'contacts'), "
                + "('index_leads_on_custom_fields', 'leads'), "
                + "('index_opportunities_on_custom_fields', 'opportunities'), "
                + "('index_tasks_on_custom_fields', 'tasks')) "
                + "AS expected(index_name, table_name) "
                + "JOIN pg_class index_class ON index_class.relname = expected.index_name "
                + "JOIN pg_namespace index_schema ON index_schema.oid = index_class.relnamespace "
                + "JOIN pg_index index_info ON index_info.indexrelid = index_class.oid "
                + "JOIN pg_class table_class ON table_class.oid = index_info.indrelid "
                + "JOIN pg_namespace table_schema ON table_schema.oid = table_class.relnamespace "
                + "JOIN pg_am access_method ON access_method.oid = index_class.relam "
                + "WHERE index_schema.nspname = 'public' AND table_schema.nspname = 'public' "
                + "AND table_class.relname = expected.table_name AND index_info.indisvalid "
                + "AND access_method.amname = 'gin' "
                + "AND position('USING gin (custom_fields jsonb_path_ops)' "
                + "IN pg_get_indexdef(index_class.oid)) > 0",
            Integer.class
        )).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND type = 'SQL'",
            Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND type = 'BASELINE'",
            Integer.class
        )).isEqualTo(1);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void preservesRailsDataAndMigrationHistory() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT value FROM settings WHERE name = 'ab263-sentinel'",
            String.class
        )).isEqualTo("preserved");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM accounts WHERE custom_fields IS NOT NULL",
            Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM schema_migrations", Integer.class))
            .isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM schema_migrations WHERE version = '20260413041448'",
            Integer.class
        )).isEqualTo(1);
    }
}
