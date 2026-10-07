package com.fatfreecrm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.Table;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DomainMappingCompletenessTest extends AbstractPostgresIntegrationTest {

    private static final Set<String> DELIBERATELY_UNMAPPED = Set.of(
        "sessions",
        "schema_migrations",
        "ar_internal_metadata",
        "active_storage_attachments",
        "active_storage_blobs",
        "active_storage_variant_records",
        "action_text_rich_texts",
        "solid_queue_blocked_executions",
        "solid_queue_claimed_executions",
        "solid_queue_failed_executions",
        "solid_queue_jobs",
        "solid_queue_pauses",
        "solid_queue_processes",
        "solid_queue_ready_executions",
        "solid_queue_recurring_executions",
        "solid_queue_recurring_tasks",
        "solid_queue_scheduled_executions",
        "solid_queue_semaphores",
        "flyway_schema_history"
    );

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void mapsEveryBaselineTableAndColumnOrDeclaresItUnmapped() {
        Map<String, Set<String>> mapped = new HashMap<>();
        entityManagerFactory.getMetamodel().getEntities().forEach(entity -> {
            Class<?> entityClass = entity.getJavaType();
            Table table = entityClass.getAnnotation(Table.class);
            assertThat(table).as("table for %s", entityClass.getName()).isNotNull();
            Set<String> columns = mapped.computeIfAbsent(table.name(), ignored -> new HashSet<>());
            for (Class<?> current = entityClass; current != null && current != Object.class;
                    current = current.getSuperclass()) {
                for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                    Column column = field.getAnnotation(Column.class);
                    JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                    if (column != null) {
                        columns.add(unquote(column.name().isBlank() ? field.getName() : column.name()));
                    } else if (joinColumn != null) {
                        columns.add(unquote(joinColumn.name()));
                    } else if (field.isAnnotationPresent(Id.class)) {
                        columns.add("id");
                    }
                    JoinTable joinTable = field.getAnnotation(JoinTable.class);
                    if (joinTable != null) {
                        Set<String> joinColumns = mapped.computeIfAbsent(joinTable.name(), ignored -> new HashSet<>());
                        for (JoinColumn join : joinTable.joinColumns()) {
                            joinColumns.add(unquote(join.name()));
                        }
                        for (JoinColumn join : joinTable.inverseJoinColumns()) {
                            joinColumns.add(unquote(join.name()));
                        }
                    }
                }
            }
        });
        Set<String> databaseTables = new HashSet<>(jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_type = 'BASE TABLE'",
            String.class
        ));
        Set<String> unexplained = new HashSet<>(databaseTables);
        unexplained.removeAll(mapped.keySet());
        unexplained.removeAll(DELIBERATELY_UNMAPPED);
        assertThat(unexplained).as("unmapped database tables").isEmpty();
        assertThat(mapped.keySet()).doesNotContainAnyElementsOf(DELIBERATELY_UNMAPPED);
        Set<String> unaccounted = new HashSet<>(mapped.keySet());
        unaccounted.removeAll(databaseTables);
        assertThat(unaccounted).as("mapped tables absent from database").isEmpty();

        mapped.forEach((table, expectedColumns) -> {
            Set<String> databaseColumns = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name = ?",
                String.class,
                table
            ));
            assertThat(expectedColumns).as("mapped columns for %s", table).containsExactlyInAnyOrderElementsOf(
                databaseColumns
            );
        });
    }

    private String unquote(String identifier) {
        return identifier.replace("\"", "").replace("`", "");
    }
}
