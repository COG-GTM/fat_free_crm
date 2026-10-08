package com.fatfreecrm.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-side write-case reset: restores the canonical fixture snapshot written by
 * {@code spring/scripts/contract-db.sh} ({@code --snapshot} / {@code CONTRACT_FIXTURES_SQL},
 * default {@code spring/build/contract-db/fixtures.sql}). One transaction TRUNCATEs every table
 * that appears in the snapshot (RESTART IDENTITY CASCADE) and replays the snapshot statements —
 * including {@code setval} calls — so ids line up across the Rails and Spring sides.
 *
 * <p>Connection details come from {@code contract.dbUrl}/{@code contract.dbUser}/
 * {@code contract.dbPassword} system properties (populated from {@code CONTRACT_DB_URL} etc. or
 * {@code FFCRM_DB_URL} style fallbacks), defaulting to the contract database on
 * {@code jdbc:postgresql://127.0.0.1:5433/ffcrm_contract} with postgres/postgres.
 */
public final class ContractDbReset {

    private static final Pattern INSERT_TABLE =
        Pattern.compile("(?i)^\\s*INSERT\\s+INTO\\s+(?:public\\.)?\"?([a-z0-9_]+)\"?");
    private static final Pattern COMMENTED_INSERT =
        Pattern.compile("(?i)^\\s*--\\s*INSERT\\s+INTO\\s+(?:public\\.)?\"?([a-z0-9_]+)\"?");

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final Path snapshotPath;

    private ContractDbReset(String jdbcUrl, String user, String password, Path snapshotPath) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.snapshotPath = snapshotPath;
    }

    public static ContractDbReset fromProperties() {
        String url = first("contract.dbUrl", "CONTRACT_DB_URL", "FFCRM_DB_URL",
            "jdbc:postgresql://127.0.0.1:5433/ffcrm_contract");
        String user = first("contract.dbUser", "CONTRACT_DB_USER", "FFCRM_DB_USER", "postgres");
        String password = first("contract.dbPassword", "CONTRACT_DB_PASSWORD", "FFCRM_DB_PASSWORD",
            "postgres");
        Path snapshot = Path.of(first("contract.fixturesSql", "CONTRACT_FIXTURES_SQL", "",
            "build/contract-db/fixtures.sql"));
        return new ContractDbReset(normalizeJdbc(url), user, password, snapshot);
    }

    private static String normalizeJdbc(String url) {
        if (url.startsWith("jdbc:")) {
            return url;
        }
        // postgres://user:pass@host:port/db → jdbc form
        java.net.URI uri = java.net.URI.create(url);
        return "jdbc:postgresql://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
            + uri.getPath();
    }

    private static String first(String property, String env1, String env2, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env1);
        }
        if (value == null || value.isBlank()) {
            value = System.getenv(env2);
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Tables the snapshot touches — TRUNCATE covers these so replay reproduces the corpus. */
    public List<String> snapshotTables(List<String> statements) {
        Set<String> tables = new LinkedHashSet<>();
        for (String statement : statements) {
            Matcher matcher = INSERT_TABLE.matcher(statement);
            if (matcher.find()) {
                tables.add(matcher.group(1));
            }
        }
        return List.copyOf(tables);
    }

    /**
     * Parse the snapshot into executable statements. {@code pg_dump --column-inserts} emits one
     * statement per line for data (plus comments/empty lines); multi-line statements are joined on
     * {@code ;}-terminated lines.
     */
    public static List<String> parseStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : sql.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--") || trimmed.startsWith("/*")
                || trimmed.startsWith("\\")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                statements.add(current.toString().trim());
                current.setLength(0);
            }
        }
        if (current.length() > 0 && !current.toString().isBlank()) {
            statements.add(current.toString().trim());
        }
        return statements;
    }

    /** Load + cache the snapshot text once per JVM. */
    public synchronized List<String> snapshotStatements() throws IOException {
        if (!Files.exists(snapshotPath)) {
            throw new IOException("Contract DB snapshot is missing at " + snapshotPath
                + " — run spring/scripts/contract-db.sh --snapshot (or a fresh load) first");
        }
        return parseStatements(Files.readString(snapshotPath));
    }

    /** TRUNCATE the snapshot tables RESTART IDENTITY CASCADE and replay the snapshot. */
    public void reset() throws IOException, SQLException {
        List<String> statements = snapshotStatements();
        List<String> tables = snapshotTables(statements);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                if (!tables.isEmpty()) {
                    statement.execute("TRUNCATE " + String.join(", ", tables)
                        + " RESTART IDENTITY CASCADE");
                }
                for (String sql : statements) {
                    statement.execute(sql);
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }
}
