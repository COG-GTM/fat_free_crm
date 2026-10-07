package com.fatfreecrm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class FlywayEmptyDatabaseTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        assertThat(applied[2].getType().name()).isEqualTo("SQL");
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
}
