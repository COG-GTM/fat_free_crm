package com.fatfreecrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.fatfreecrm.support.RailsSchemaRb;
import com.fatfreecrm.support.SchemaParityAssertions;
import java.io.IOException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Starts Spring against a database that Rails already migrated and holds live CRM data, then proves
 * Flyway adoption leaves the Rails-owned schema and rows untouched and cannot drop them.
 */
@SpringBootTest
class RailsMigratedDatabaseSafetyTest {

    @ServiceConnection
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("fat_free_crm_rails_safety")
        .withUsername("postgres")
        .withPassword("postgres");

    private static RailsSchemaRb schema;

    static {
        POSTGRES.start();
        try {
            POSTGRES.copyFileToContainer(
                MountableFile.forClasspathResource("db/rails/rails_schema.sql"),
                "/tmp/rails_schema.sql"
            );
            execOrFail("-f", "/tmp/rails_schema.sql");
            execOrFail("-c", "INSERT INTO users (id, username, email, encrypted_password, password_salt, "
                + "created_at, updated_at) VALUES (1, 'rails_owner', 'owner@example.com', 'x', 'y', now(), now())");
            execOrFail("-c", "INSERT INTO accounts (id, user_id, name, access, created_at, updated_at) "
                + "VALUES (1, 1, 'Rails Account', 'Private', now(), now())");
            execOrFail("-c", "INSERT INTO ar_internal_metadata (key, value, created_at, updated_at) "
                + "VALUES ('environment', 'development', now(), now())");
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

    private static void execOrFail(String option, String argument) throws IOException, InterruptedException {
        var result = POSTGRES.execInContainer(
            "psql", "-U", "postgres", "-d", "fat_free_crm_rails_safety", "-v", "ON_ERROR_STOP=1", option, argument
        );
        if (result.getExitCode() != 0) {
            fail("psql %s %s failed: %s", option, argument, result.getStderr());
        }
    }

    @BeforeAll
    static void loadSchemaRb() throws IOException {
        schema = RailsSchemaRb.load();
    }

    @Test
    void recordsTheBaselineUnderTheConfiguredDescription() {
        var applied = flyway.info().applied();

        assertThat(applied).hasSize(1);
        assertThat(applied[0].getDescription()).isEqualTo("Rails schema adopted");
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void leavesTheRailsSchemaExactlyAsRailsDeclaredIt() throws IOException {
        SchemaParityAssertions.assertTablesMatchSchemaRb(jdbcTemplate, schema);
        SchemaParityAssertions.assertColumnsMatchSchemaRb(jdbcTemplate, schema);
        SchemaParityAssertions.assertForeignKeysMatchSchemaRb(jdbcTemplate, schema);
        SchemaParityAssertions.assertSchemaMigrationsMatchMigrationFiles(jdbcTemplate, schema);
    }

    @Test
    void preservesCrmRowsAndRailsEnvironmentMetadata() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT name FROM accounts WHERE id = 1 AND user_id = 1 AND access = 'Private'", String.class
        )).isEqualTo("Rails Account");
        assertThat(jdbcTemplate.queryForObject("SELECT username FROM users WHERE id = 1", String.class))
            .isEqualTo("rails_owner");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT value FROM ar_internal_metadata WHERE key = 'environment'", String.class
        )).isEqualTo("development");
    }

    @Test
    void cleanIsDisabledSoRailsTablesCannotBeDropped() {
        assertThatThrownBy(flyway::clean).isInstanceOf(FlywayException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM accounts", Integer.class)).isEqualTo(1);
    }
}
