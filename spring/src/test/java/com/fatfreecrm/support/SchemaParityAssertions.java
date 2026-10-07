package com.fatfreecrm.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Assertions shared by the Flyway-created and Rails-created database tests: the live database must
 * expose exactly the tables, columns, nullability and foreign keys that Rails declares in db/schema.rb.
 */
public final class SchemaParityAssertions {

    public static final Set<String> RAILS_BOOKKEEPING_TABLES = Set.of("schema_migrations", "ar_internal_metadata");
    public static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

    private SchemaParityAssertions() {
    }

    public static Set<String> publicTables(JdbcTemplate jdbcTemplate) {
        return Set.copyOf(jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
            String.class
        ));
    }

    public static void assertTablesMatchSchemaRb(JdbcTemplate jdbcTemplate, RailsSchemaRb schema) {
        Set<String> expected = schema.tableNames();
        expected.addAll(RAILS_BOOKKEEPING_TABLES);
        expected.add(FLYWAY_HISTORY_TABLE);
        assertThat(publicTables(jdbcTemplate)).containsExactlyInAnyOrderElementsOf(expected);
    }

    public static void assertColumnsMatchSchemaRb(JdbcTemplate jdbcTemplate, RailsSchemaRb schema) {
        for (Map.Entry<String, List<RailsSchemaRb.Column>> table : schema.tables().entrySet()) {
            Map<String, Boolean> actual = jdbcTemplate.queryForList(
                "SELECT column_name, is_nullable FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name = ?",
                table.getKey()
            ).stream().collect(Collectors.toMap(
                row -> (String) row.get("column_name"),
                row -> "YES".equals(row.get("is_nullable"))
            ));
            Map<String, Boolean> expected = table.getValue().stream()
                .collect(Collectors.toMap(RailsSchemaRb.Column::name, RailsSchemaRb.Column::nullable));
            assertThat(actual)
                .as("columns of %s", table.getKey())
                .containsExactlyInAnyOrderEntriesOf(expected);
        }
    }

    public static void assertForeignKeysMatchSchemaRb(JdbcTemplate jdbcTemplate, RailsSchemaRb schema) {
        Set<String> actual = jdbcTemplate.queryForList(
            "SELECT tc.table_name || '.' || kcu.column_name || '->' || ccu.table_name AS fk "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name "
                + "JOIN information_schema.constraint_column_usage ccu ON tc.constraint_name = ccu.constraint_name "
                + "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public'",
            String.class
        ).stream().collect(Collectors.toSet());
        Set<String> expected = schema.foreignKeys().stream()
            .map(fk -> fk.fromTable() + "." + fk.column() + "->" + fk.toTable())
            .collect(Collectors.toSet());
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    public static void assertSchemaMigrationsMatchMigrationFiles(
        JdbcTemplate jdbcTemplate,
        RailsSchemaRb schema
    ) throws IOException {
        Set<String> expected;
        try (Stream<Path> files = Files.list(Path.of("../db/migrate"))) {
            expected = files.map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".rb"))
                .map(name -> name.substring(0, name.indexOf('_')))
                .collect(Collectors.toSet());
        }
        Set<String> recorded = Set.copyOf(
            jdbcTemplate.queryForList("SELECT version FROM schema_migrations", String.class)
        );
        assertThat(recorded).contains(schema.version());
        assertThat(recorded).containsExactlyInAnyOrderElementsOf(expected);
    }
}
