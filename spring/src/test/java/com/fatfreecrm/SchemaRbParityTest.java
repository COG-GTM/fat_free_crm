package com.fatfreecrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import com.fatfreecrm.support.RailsSchemaRb;
import com.fatfreecrm.support.SchemaParityAssertions;
import java.io.IOException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins the Flyway-created database to what Rails declares in db/schema.rb, so the V1 baseline cannot
 * drift from the Rails-owned schema without a test noticing.
 */
class SchemaRbParityTest extends AbstractPostgresIntegrationTest {

    private static RailsSchemaRb schema;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void loadSchemaRb() throws IOException {
        schema = RailsSchemaRb.load();
    }

    @Test
    void createsExactlyTheRailsTablesPlusBookkeepingTables() {
        SchemaParityAssertions.assertTablesMatchSchemaRb(jdbcTemplate, schema);
    }

    @Test
    void columnsAndNullabilityMatchSchemaRb() {
        SchemaParityAssertions.assertColumnsMatchSchemaRb(jdbcTemplate, schema);
    }

    @Test
    void foreignKeysMatchSchemaRb() {
        SchemaParityAssertions.assertForeignKeysMatchSchemaRb(jdbcTemplate, schema);
    }

    @Test
    void schemaMigrationsListsEveryRailsMigrationFile() throws IOException {
        SchemaParityAssertions.assertSchemaMigrationsMatchMigrationFiles(jdbcTemplate, schema);
    }

    @Test
    void railsCanTellTheDatabaseIsAtTheSchemaRbVersion() {
        assertThat(jdbcTemplate.queryForObject("SELECT max(version) FROM schema_migrations", String.class))
            .isEqualTo(schema.version());
    }

    @Test
    void migratingAgainIsANoOp() {
        var result = flyway.migrate();

        assertThat(result.migrationsExecuted).isZero();
        assertThat(flyway.info().applied()).hasSize(1);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void cleanIsDisabledSoTheSharedDatabaseCannotBeDropped() {
        assertThatThrownBy(flyway::clean)
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("clean");
        assertThat(SchemaParityAssertions.publicTables(jdbcTemplate)).contains("accounts", "users");
    }
}
