package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reset must TRUNCATE every application table — not just snapshot tables — so stray rows written
 * mid-run (versions, comments, soak leftovers) cannot leak into later cases.
 */
class ContractDbResetTest {

    @Test
    void applicationTablesCoversAllPublicTablesExceptMigrationMetadata() throws Exception {
        ContractDbReset reset = ContractDbReset.fromProperties();
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("contract.dbUrl",
                    "jdbc:postgresql://127.0.0.1:5433/ffcrm_contract"),
                System.getProperty("contract.dbUser", "postgres"),
                System.getProperty("contract.dbPassword", "postgres"))) {
            List<String> tables = reset.applicationTables(connection);
            assertTrue(tables.contains("users"), tables.toString());
            assertTrue(tables.contains("versions"), tables.toString());
            assertTrue(tables.contains("comments"), tables.toString());
            assertTrue(tables.contains("accounts"), tables.toString());
            assertTrue(tables.contains("tasks"), tables.toString());
            assertFalse(tables.contains("schema_migrations"), tables.toString());
            assertFalse(tables.contains("ar_internal_metadata"), tables.toString());
            assertFalse(tables.contains("flyway_schema_history"), tables.toString());
        }
    }

    @Test
    void parseStatementsSkipsPsqlMetaCommandsAndJoinsMultiline() {
        List<String> statements = ContractDbReset.parseStatements(
            "\\restrict token\n"
                + "INSERT INTO public.users (id) VALUES (1);\n"
                + "-- comment\n"
                + "INSERT INTO public.tasks (id) VALUES\n(2)\n(3);\n"
                + "\\unrestrict token\n"
                + "SELECT pg_catalog.setval('tasks_id_seq', 1, true);\n");
        assertTrue(statements.stream().noneMatch(s -> s.contains("\\restrict")
            || s.contains("\\unrestrict")), statements.toString());
        assertTrue(statements.stream().anyMatch(s -> s.contains("setval")));
        assertTrue(statements.stream().anyMatch(s -> s.contains("INSERT INTO public.tasks")));
    }
}
