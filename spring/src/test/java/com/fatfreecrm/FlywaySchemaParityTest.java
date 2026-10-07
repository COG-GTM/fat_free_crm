package com.fatfreecrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.utility.MountableFile;

/**
 * Pins the Flyway-created schema (V1 executed by Flyway's SQL parser on an empty database) to the
 * schema Rails produces with {@code db:schema:load} (the psql-loaded {@code rails_schema.sql} fixture),
 * so a database Spring creates is structurally indistinguishable from one Rails created.
 */
class FlywaySchemaParityTest extends AbstractPostgresIntegrationTest {

    private static final String RAILS_DATABASE = "fat_free_crm_rails_parity";
    private static final Pattern MIGRATION_VERSION = Pattern.compile("^(\\d{14})_.*\\.rb$");

    private static final String COLUMNS_SQL = """
        SELECT table_name || '.' || column_name || ' ' || data_type || '/' || udt_name
            || ' null=' || is_nullable || ' default=' || coalesce(column_default, '<none>')
            || ' len=' || coalesce(character_maximum_length::text, '-')
            || ' prec=' || coalesce(numeric_precision::text, '-')
            || ' scale=' || coalesce(numeric_scale::text, '-')
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
        """;

    private static final String CONSTRAINTS_SQL = """
        SELECT rel.relname || ' ' || con.conname || ' ' || con.contype::text || ' ' || pg_get_constraintdef(con.oid)
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE nsp.nspname = 'public' AND rel.relname <> 'flyway_schema_history'
        """;

    private static final String INDEXES_SQL = """
        SELECT indexdef FROM pg_indexes
        WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
        """;

    private static final String SEQUENCES_SQL = """
        SELECT sequence_name || ' ' || data_type || ' start=' || start_value || ' inc=' || increment
        FROM information_schema.sequences WHERE sequence_schema = 'public'
        """;

    private static final String SEQUENCE_OWNERSHIP_SQL = """
        SELECT seq.relname || ' owned by ' || tbl.relname || '.' || att.attname
        FROM pg_class seq
        JOIN pg_depend dep ON dep.objid = seq.oid AND dep.deptype = 'a'
        JOIN pg_class tbl ON tbl.oid = dep.refobjid
        JOIN pg_attribute att ON att.attrelid = tbl.oid AND att.attnum = dep.refobjsubid
        JOIN pg_namespace nsp ON nsp.oid = seq.relnamespace
        WHERE seq.relkind = 'S' AND nsp.nspname = 'public'
        """;

    private static final String TABLES_SQL = """
        SELECT table_name || ' ' || table_type FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
        """;

    private static final String SCHEMA_MIGRATIONS_SQL = "SELECT version FROM schema_migrations";

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void loadRailsSchemaIntoSiblingDatabase() throws IOException, InterruptedException {
        var created = POSTGRES.execInContainer("createdb", "-U", "postgres", RAILS_DATABASE);
        assertThat(created.getExitCode())
            .withFailMessage("createdb failed: %s", created.getStderr())
            .isZero();
        POSTGRES.copyFileToContainer(
            MountableFile.forClasspathResource("db/rails/rails_schema.sql"),
            "/tmp/rails_schema_parity.sql"
        );
        var loaded = POSTGRES.execInContainer(
            "psql", "-U", "postgres", "-d", RAILS_DATABASE,
            "-v", "ON_ERROR_STOP=1", "-f", "/tmp/rails_schema_parity.sql"
        );
        assertThat(loaded.getExitCode())
            .withFailMessage("Loading the Rails schema fixture failed: %s", loaded.getStderr())
            .isZero();
    }

    @Test
    void flywayAppliedV1OnThisDatabase() {
        var applied = flyway.info().applied();
        assertThat(applied).hasSize(1);
        assertThat(applied[0].getType().name()).isEqualTo("SQL");
    }

    @Test
    void tablesMatchTheRailsSchema() throws SQLException {
        assertThat(springSide(TABLES_SQL)).isEqualTo(railsSide(TABLES_SQL));
    }

    @Test
    void columnsTypesNullabilityAndDefaultsMatchTheRailsSchema() throws SQLException {
        Set<String> spring = springSide(COLUMNS_SQL);
        assertThat(spring).isNotEmpty().isEqualTo(railsSide(COLUMNS_SQL));
    }

    @Test
    void primaryUniqueAndForeignKeyConstraintsMatchTheRailsSchema() throws SQLException {
        Set<String> spring = springSide(CONSTRAINTS_SQL);
        assertThat(spring).isNotEmpty().isEqualTo(railsSide(CONSTRAINTS_SQL));
    }

    @Test
    void indexesMatchTheRailsSchema() throws SQLException {
        Set<String> spring = springSide(INDEXES_SQL);
        assertThat(spring).isNotEmpty().isEqualTo(railsSide(INDEXES_SQL));
    }

    @Test
    void sequencesAndTheirOwningColumnsMatchTheRailsSchema() throws SQLException {
        assertThat(springSide(SEQUENCES_SQL)).isNotEmpty().isEqualTo(railsSide(SEQUENCES_SQL));
        assertThat(springSide(SEQUENCE_OWNERSHIP_SQL)).isNotEmpty().isEqualTo(railsSide(SEQUENCE_OWNERSHIP_SQL));
    }

    @Test
    void schemaMigrationsContainExactlyTheRailsMigrationVersions() throws SQLException, IOException {
        Set<String> expected;
        try (var files = Files.list(Path.of("../db/migrate"))) {
            expected = files
                .map(path -> MIGRATION_VERSION.matcher(path.getFileName().toString()))
                .filter(java.util.regex.Matcher::matches)
                .map(matcher -> matcher.group(1))
                .collect(Collectors.toCollection(TreeSet::new));
        }
        assertThat(expected).isNotEmpty();
        assertThat(springSide(SCHEMA_MIGRATIONS_SQL)).isEqualTo(expected);
        assertThat(railsSide(SCHEMA_MIGRATIONS_SQL)).isEqualTo(expected);
    }

    @Test
    void railsMetadataTablesArePresentSoRailsCanKeepUsingTheDatabase() throws SQLException {
        assertThat(springSide(TABLES_SQL)).contains(
            "schema_migrations BASE TABLE",
            "ar_internal_metadata BASE TABLE"
        );
        assertThat(springSide(CONSTRAINTS_SQL)).contains(
            "schema_migrations schema_migrations_pkey p PRIMARY KEY (version)",
            "ar_internal_metadata ar_internal_metadata_pkey p PRIMARY KEY (key)"
        );
    }

    @Test
    void flywayCleanIsDisabledSoRailsTablesCannotBeDropped() {
        assertThatThrownBy(() -> flyway.clean())
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("clean");
        assertThat(springSide(TABLES_SQL)).contains("accounts BASE TABLE", "users BASE TABLE");
    }

    private Set<String> springSide(String sql) {
        return new TreeSet<>(jdbcTemplate.queryForList(sql, String.class));
    }

    private Set<String> railsSide(String sql) throws SQLException {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
            + POSTGRES.getMappedPort(5432) + "/" + RAILS_DATABASE;
        List<String> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                rows.add(resultSet.getString(1));
            }
        }
        return new TreeSet<>(rows);
    }
}
