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
    void baselinesAnExistingRailsSchemaWithoutExecutingSqlMigrations() {
        var applied = flyway.info().applied();
        assertThat(applied).hasSize(1);
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getType().name()).isEqualTo("BASELINE");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE type = 'SQL'",
            Integer.class
        )).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void preservesRailsDataAndMigrationHistory() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT value FROM settings WHERE name = 'ab263-sentinel'",
            String.class
        )).isEqualTo("preserved");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM schema_migrations", Integer.class))
            .isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM schema_migrations WHERE version = '20260413041448'",
            Integer.class
        )).isEqualTo(1);
    }
}
