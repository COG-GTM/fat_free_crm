package com.fatfreecrm.spike.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * AB-267 benchmark: {@code cf_*} columns versus one {@code custom_fields jsonb} column.
 *
 * <p>Run with {@code ./gradlew benchmarkTest -Pbenchmark.rows=10000,100000,1000000}. Optional
 * {@code -Pbenchmark.reps}, {@code -Pbenchmark.warmup}, {@code -Pbenchmark.writes} and
 * {@code -Pbenchmark.outputDir}. Methodology is documented in
 * {@code docs/migration/spikes/custom-fields-jsonb-benchmark.md}.
 */
@Tag("benchmark")
class CustomFieldsJsonbBenchmark {

    private static final String TABLE = "bench_accounts";

    private static final List<String> CF_COLUMNS = List.of(
        "cf_account_segment", "cf_account_code", "cf_account_email", "cf_annual_value", "cf_employee_count",
        "cf_health_score", "cf_renewal_date", "cf_last_reviewed_at", "cf_marketing_opt_in", "cf_profile_url",
        "cf_interests", "cf_notes");

    private static final Map<String, String> CF_TYPES = Map.ofEntries(
        Map.entry("cf_account_segment", "select"), Map.entry("cf_account_code", "string"),
        Map.entry("cf_account_email", "email"), Map.entry("cf_annual_value", "decimal"),
        Map.entry("cf_employee_count", "integer"), Map.entry("cf_health_score", "float"),
        Map.entry("cf_renewal_date", "date"), Map.entry("cf_last_reviewed_at", "datetime"),
        Map.entry("cf_marketing_opt_in", "boolean"), Map.entry("cf_profile_url", "url"),
        Map.entry("cf_interests", "check_boxes"), Map.entry("cf_notes", "text"));

    private static final String CORE_COLUMNS =
        "id, user_id, assigned_to, name, access, email, category, rating, deleted_at, created_at, updated_at, "
            + "background_info";

    private static final Pattern INDEX_IN_PLAN =
        Pattern.compile("(?:Scan(?: Backward)? using|Bitmap Index Scan on) (\\w+)");
    private static final Pattern EXECUTION_TIME = Pattern.compile("Execution Time: ([0-9.]+) ms");
    private static final Pattern BUFFERS = Pattern.compile("Buffers: shared(?: hit=(\\d+))?(?: read=(\\d+))?");

    private static PostgreSQLContainer<?> postgres;

    private final int reps = Integer.getInteger("benchmark.reps", 20);
    private final int warmup = Integer.getInteger("benchmark.warmup", 3);
    private final int writes = Integer.getInteger("benchmark.writes", 2000);
    private final Path outputDir = Path.of(System.getProperty("benchmark.outputDir", "build/benchmark-results"));

    @BeforeAll
    @SuppressWarnings("resource")
    static void startPostgres() {
        postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("bench")
            .withSharedMemorySize(1024L * 1024 * 1024)
            .withCommand("postgres", "-c", "shared_buffers=1GB", "-c", "work_mem=32MB",
                "-c", "maintenance_work_mem=512MB", "-c", "max_wal_size=8GB", "-c", "effective_cache_size=4GB",
                "-c", "random_page_cost=1.1", "-c", "jit=off");
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void benchmarkCustomFieldStorage() throws Exception {
        List<Integer> rowCounts = Arrays.stream(System.getProperty("benchmark.rows", "10000,100000").split(","))
            .map(String::trim).map(Integer::parseInt).toList();
        Files.createDirectories(outputDir);
        try (Connection connection = connect()) {
            connection.setAutoCommit(true);
            installHelpers(connection);
            writeEnvironment(connection, rowCounts);
            initCsv("queries.csv", "rows,phase,scenario,form,variant,rows_returned,p50_ms,p95_ms,mean_ms,min_ms,"
                + "max_ms,explain_exec_ms,shared_hit,shared_read,indexes_used");
            initCsv("sizes.csv", "rows,table,relation,kind,bytes,build_ms");
            initCsv("writes.csv", "rows,table,operation,count,p50_ms,p95_ms,mean_ms,throughput_per_s,total_ms");
            for (int rows : rowCounts) {
                runForRowCount(connection, rows);
            }
        }
    }

    private void runForRowCount(Connection connection, int rows) throws SQLException {
        log("=== N=%d: loading", rows);
        long loadStart = System.nanoTime();
        execute(connection, "DROP TABLE IF EXISTS " + TABLE + ", w_cf, w_jsonb, w_both CASCADE");
        execute(connection, createTableSql(TABLE, true, true));
        execute(connection, "SELECT setseed(0.267)");
        execute(connection, insertSql(rows));
        execute(connection, "VACUUM (ANALYZE) " + TABLE);
        appendCsv("sizes.csv", row(rows, TABLE, TABLE, "load", 0, millis(loadStart)));
        recordColumnBytes(connection, rows);
        StringBuilder plans = new StringBuilder();

        runPhase(connection, rows, "no_index", plans, variant -> true);

        log("N=%d: building cf btree / trigram and jsonb GIN(jsonb_path_ops) + expression indexes", rows);
        execute(connection, "CREATE EXTENSION IF NOT EXISTS pg_trgm");
        Map<String, String> indexes = new LinkedHashMap<>();
        indexes.put("ix_cf_segment", "(cf_account_segment)");
        indexes.put("ix_cf_annual_value", "(cf_annual_value DESC NULLS LAST, id DESC)");
        indexes.put("ix_cf_renewal_date", "(cf_renewal_date)");
        indexes.put("ix_cf_interests_trgm", "USING gin (cf_interests gin_trgm_ops)");
        indexes.put("ix_jsonb_path_ops", "USING gin (custom_fields jsonb_path_ops)");
        indexes.put("ix_jsonb_segment", "((custom_fields->>'cf_account_segment'))");
        indexes.put("ix_jsonb_annual_value",
            "(((custom_fields->>'cf_annual_value')::numeric) DESC NULLS LAST, id DESC)");
        indexes.put("ix_jsonb_renewal_date", "((custom_fields->>'cf_renewal_date'))");
        for (Map.Entry<String, String> index : indexes.entrySet()) {
            createIndex(connection, rows, TABLE, index.getKey(), index.getValue());
        }
        execute(connection, "VACUUM (ANALYZE) " + TABLE);
        runPhase(connection, rows, "btree_gin_path_ops", plans, variant -> true);

        log("N=%d: swapping GIN jsonb_path_ops for default jsonb_ops", rows);
        execute(connection, "DROP INDEX ix_jsonb_path_ops");
        createIndex(connection, rows, TABLE, "ix_jsonb_ops", "USING gin (custom_fields)");
        execute(connection, "ANALYZE " + TABLE);
        runPhase(connection, rows, "gin_jsonb_ops", plans, variant -> variant.usesGin());
        execute(connection, "DROP INDEX ix_jsonb_ops");
        createIndex(connection, rows, TABLE, "ix_jsonb_path_ops", "USING gin (custom_fields jsonb_path_ops)");

        recordRelationSizes(connection, rows, TABLE);
        writeText("plans-" + rows + ".txt", plans.toString());

        runWriteBenchmark(connection, rows);
    }

    private void runPhase(Connection connection, int rows, String phase, StringBuilder plans,
            java.util.function.Predicate<QueryVariant> filter) throws SQLException {
        Map<String, Object> expectedByScenario = new LinkedHashMap<>();
        for (QueryVariant variant : variants()) {
            if (!filter.test(variant)) {
                continue;
            }
            for (String form : variant.forms()) {
                String sql = variant.sql(form);
                Measurement measurement = measure(connection, sql);
                String expectedKey = variant.scenario() + "/" + form;
                Object previous = expectedByScenario.putIfAbsent(expectedKey, measurement.result());
                if (previous != null) {
                    assertThat(measurement.result())
                        .as("%s %s %s must return the same rows as the first variant", phase, expectedKey,
                            variant.variant())
                        .isEqualTo(previous);
                }
                String plan = explain(connection, sql);
                plans.append("### N=").append(rows).append(" phase=").append(phase).append(" scenario=")
                    .append(variant.scenario()).append(" form=").append(form).append(" variant=")
                    .append(variant.variant()).append('\n').append(sql).append("\n\n").append(plan).append("\n\n");
                appendCsv("queries.csv", row(rows, phase, variant.scenario(), form, variant.variant(),
                    measurement.rowsReturned(), fmt(measurement.percentile(50)), fmt(measurement.percentile(95)),
                    fmt(measurement.mean()), fmt(measurement.min()), fmt(measurement.max()),
                    planValue(EXECUTION_TIME, plan, 1), planValue(BUFFERS, plan, 1), planValue(BUFFERS, plan, 2),
                    indexesUsed(plan)));
                log("N=%d %-18s %-14s %-5s %-24s rows=%-7d p50=%8s p95=%8s idx=%s", rows, phase,
                    variant.scenario(), form, variant.variant(), measurement.rowsReturned(),
                    fmt(measurement.percentile(50)), fmt(measurement.percentile(95)), indexesUsed(plan));
            }
        }
    }

    private static List<QueryVariant> variants() {
        List<QueryVariant> list = new ArrayList<>();
        filter(list, "eq_rare", "cf_column", "cf_account_segment = 'Strategic'");
        filter(list, "eq_rare", "jsonb_contains", "custom_fields @> '{\"cf_account_segment\": \"Strategic\"}'");
        filter(list, "eq_rare", "jsonb_text_expr", "custom_fields->>'cf_account_segment' = 'Strategic'");
        filter(list, "eq_common", "cf_column", "cf_account_segment = 'Enterprise'");
        filter(list, "eq_common", "jsonb_contains", "custom_fields @> '{\"cf_account_segment\": \"Enterprise\"}'");
        filter(list, "eq_common", "jsonb_text_expr", "custom_fields->>'cf_account_segment' = 'Enterprise'");
        filter(list, "multi_value", "cf_yaml_like",
            "cf_interests LIKE '%' || chr(10) || '- Partner program' || chr(10) || '%'");
        filter(list, "multi_value", "jsonb_contains", "custom_fields @> '{\"cf_interests\": [\"Partner program\"]}'");
        filter(list, "multi_value", "jsonb_path_match",
            "custom_fields @? '$.cf_interests[*] ? (@ == \"Partner program\")'");
        filter(list, "numeric_range", "cf_column", "cf_annual_value BETWEEN 250000 AND 500000");
        filter(list, "numeric_range", "jsonb_numeric_expr",
            "(custom_fields->>'cf_annual_value')::numeric BETWEEN 250000 AND 500000");
        filter(list, "numeric_range", "jsonb_path_match",
            "custom_fields @? '$.cf_annual_value ? (@ >= 250000 && @ <= 500000)'");
        filter(list, "date_range", "cf_column", "cf_renewal_date BETWEEN '2026-01-01' AND '2026-03-31'");
        filter(list, "date_range", "jsonb_text_expr",
            "custom_fields->>'cf_renewal_date' BETWEEN '2026-01-01' AND '2026-03-31'");
        filter(list, "date_range", "jsonb_path_match",
            "custom_fields @? '$.cf_renewal_date ? (@ >= \"2026-01-01\" && @ <= \"2026-03-31\")'");
        filter(list, "key_exists", "cf_column", "cf_renewal_date IS NOT NULL");
        filter(list, "key_exists", "jsonb_key_exists", "custom_fields ? 'cf_renewal_date'");
        filter(list, "key_exists", "jsonb_path_match", "custom_fields @? '$.cf_renewal_date'");
        filter(list, "key_exists", "jsonb_text_expr", "custom_fields->>'cf_renewal_date' IS NOT NULL");
        for (int offset : new int[] {0, 5000}) {
            String scenario = "sort_offset_" + offset;
            list.add(new QueryVariant(scenario, "cf_column", List.of("page"),
                "SELECT id, cf_annual_value AS v FROM " + TABLE
                    + " ORDER BY cf_annual_value DESC NULLS LAST, id DESC LIMIT 25 OFFSET " + offset));
            list.add(new QueryVariant(scenario, "jsonb_numeric_expr", List.of("page"),
                "SELECT id, (custom_fields->>'cf_annual_value')::numeric AS v FROM " + TABLE
                    + " ORDER BY (custom_fields->>'cf_annual_value')::numeric DESC NULLS LAST, id DESC"
                    + " LIMIT 25 OFFSET " + offset));
        }
        return list;
    }

    private static void filter(List<QueryVariant> list, String scenario, String variant, String predicate) {
        list.add(new QueryVariant(scenario, variant, List.of("count", "page"), predicate));
    }

    private Measurement measure(Connection connection, String sql) throws SQLException {
        Object result = null;
        int rowsReturned = 0;
        double[] samples = new double[reps];
        for (int i = 0; i < warmup + reps; i++) {
            long start = System.nanoTime();
            List<List<Object>> rowsRead = query(connection, sql);
            double elapsed = (System.nanoTime() - start) / 1_000_000.0;
            if (i >= warmup) {
                samples[i - warmup] = elapsed;
            }
            rowsReturned = rowsRead.size();
            result = normalize(rowsRead);
        }
        Arrays.sort(samples);
        return new Measurement(samples, rowsReturned, result);
    }

    private static Object normalize(List<List<Object>> rows) {
        return rows.stream()
            .map(r -> r.stream().map(v -> v instanceof java.math.BigDecimal d ? d.stripTrailingZeros().toPlainString()
                : Objects.toString(v)).toList())
            .toList();
    }

    private static String explain(Connection connection, String sql) throws SQLException {
        return query(connection, "EXPLAIN (ANALYZE, BUFFERS) " + sql).stream()
            .map(r -> String.valueOf(r.get(0))).collect(Collectors.joining("\n"));
    }

    private static String indexesUsed(String plan) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = INDEX_IN_PLAN.matcher(plan);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        if (names.isEmpty()) {
            return plan.contains("Seq Scan") ? "seq_scan" : "none";
        }
        return String.join("+", names);
    }

    private static String planValue(Pattern pattern, String plan, int group) {
        Matcher matcher = pattern.matcher(plan);
        return matcher.find() && matcher.group(group) != null ? matcher.group(group) : "0";
    }

    private void createIndex(Connection connection, int rows, String table, String name, String definition)
            throws SQLException {
        long start = System.nanoTime();
        execute(connection, "CREATE INDEX " + name + " ON " + table + " " + definition);
        appendCsv("sizes.csv", row(rows, table, name, "index_build", relationSize(connection, name), millis(start)));
    }

    private void recordRelationSizes(Connection connection, int rows, String table) throws SQLException {
        for (List<Object> r : query(connection, "SELECT 'heap', pg_relation_size('" + table + "')"
            + " UNION ALL SELECT 'toast', coalesce(pg_total_relation_size(reltoastrelid), 0) FROM pg_class"
            + " WHERE relname = '" + table + "'"
            + " UNION ALL SELECT 'indexes', pg_indexes_size('" + table + "')"
            + " UNION ALL SELECT 'total', pg_total_relation_size('" + table + "')")) {
            appendCsv("sizes.csv", row(rows, table, table, r.get(0), r.get(1), ""));
        }
        for (List<Object> r : query(connection, "SELECT indexrelname, pg_relation_size(indexrelid)"
            + " FROM pg_stat_user_indexes WHERE relname = '" + table + "' ORDER BY indexrelname")) {
            appendCsv("sizes.csv", row(rows, table, r.get(0), "index", r.get(1), ""));
        }
    }

    private void recordColumnBytes(Connection connection, int rows) throws SQLException {
        String cfSum = CF_COLUMNS.stream().map(c -> "coalesce(sum(pg_column_size(" + c + ")), 0)")
            .collect(Collectors.joining(" + "));
        List<Object> r = query(connection, "SELECT " + cfSum + ", sum(pg_column_size(custom_fields)) FROM " + TABLE)
            .get(0);
        appendCsv("sizes.csv", row(rows, TABLE, "cf_columns", "column_bytes", r.get(0), ""));
        appendCsv("sizes.csv", row(rows, TABLE, "custom_fields", "column_bytes", r.get(1), ""));
    }

    private void runWriteBenchmark(Connection connection, int rows) throws SQLException {
        log("N=%d: write benchmark (cf only / jsonb only / both + sync trigger)", rows);
        String cfList = String.join(", ", CF_COLUMNS);
        long start = System.nanoTime();
        execute(connection, createTableSql("w_cf", true, false));
        execute(connection, "INSERT INTO w_cf SELECT " + CORE_COLUMNS + ", " + cfList + " FROM " + TABLE);
        appendCsv("sizes.csv", row(rows, "w_cf", "w_cf", "copy", 0, millis(start)));
        start = System.nanoTime();
        execute(connection, createTableSql("w_jsonb", false, true));
        execute(connection, "INSERT INTO w_jsonb SELECT " + CORE_COLUMNS + ", custom_fields FROM " + TABLE);
        appendCsv("sizes.csv", row(rows, "w_jsonb", "w_jsonb", "copy", 0, millis(start)));
        start = System.nanoTime();
        execute(connection, createTableSql("w_both", true, true));
        execute(connection, "INSERT INTO w_both SELECT " + CORE_COLUMNS + ", " + cfList + ", custom_fields FROM "
            + TABLE);
        appendCsv("sizes.csv", row(rows, "w_both", "w_both", "copy", 0, millis(start)));

        createIndex(connection, rows, "w_cf", "w_cf_segment", "(cf_account_segment)");
        createIndex(connection, rows, "w_cf", "w_cf_annual_value", "(cf_annual_value DESC NULLS LAST, id DESC)");
        createIndex(connection, rows, "w_cf", "w_cf_renewal_date", "(cf_renewal_date)");
        for (String table : List.of("w_jsonb", "w_both")) {
            createIndex(connection, rows, table, table + "_gin", "USING gin (custom_fields jsonb_path_ops)");
            createIndex(connection, rows, table, table + "_segment", "((custom_fields->>'cf_account_segment'))");
            createIndex(connection, rows, table, table + "_annual_value",
                "(((custom_fields->>'cf_annual_value')::numeric) DESC NULLS LAST, id DESC)");
            createIndex(connection, rows, table, table + "_renewal_date", "((custom_fields->>'cf_renewal_date'))");
        }
        execute(connection, "CREATE TRIGGER w_both_sync_custom_fields BEFORE INSERT OR UPDATE ON w_both"
            + " FOR EACH ROW EXECUTE FUNCTION spike_sync_custom_fields('Account')");
        for (String table : List.of("w_cf", "w_jsonb", "w_both")) {
            execute(connection, "VACUUM (ANALYZE) " + table);
        }

        List<List<Object>> source = query(connection, "SELECT " + CORE_COLUMNS + ", " + cfList
            + ", custom_fields::text FROM " + TABLE + " ORDER BY id LIMIT " + writes);
        String coreParams = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?";
        String cfParams = CF_COLUMNS.stream().map(c -> "?").collect(Collectors.joining(", "));
        timedInserts(connection, rows, "w_cf", "INSERT INTO w_cf (" + CORE_COLUMNS + ", " + cfList + ") VALUES ("
            + coreParams + ", " + cfParams + ")", source, false, true);
        timedInserts(connection, rows, "w_jsonb", "INSERT INTO w_jsonb (" + CORE_COLUMNS
            + ", custom_fields) VALUES (" + coreParams + ", ?::jsonb)", source, true, false);
        timedInserts(connection, rows, "w_both", "INSERT INTO w_both (" + CORE_COLUMNS + ", " + cfList
            + ") VALUES (" + coreParams + ", " + cfParams + ")", source, false, true);

        String cfUpdate = "UPDATE %s SET cf_annual_value = ?, updated_at = now() WHERE id = ?";
        timedUpdates(connection, rows, "w_cf", cfUpdate.formatted("w_cf"));
        timedUpdates(connection, rows, "w_jsonb", "UPDATE w_jsonb SET custom_fields = jsonb_set(custom_fields,"
            + " '{cf_annual_value}', to_jsonb(?::numeric)), updated_at = now() WHERE id = ?");
        timedUpdates(connection, rows, "w_both", cfUpdate.formatted("w_both"));
        assertThat(query(connection, "SELECT count(*) FROM w_both WHERE custom_fields->>'cf_annual_value'"
            + " IS DISTINCT FROM cf_annual_value::text").get(0).get(0))
            .as("sync trigger keeps custom_fields in step with cf_annual_value").isEqualTo(0L);

        for (String table : List.of("w_cf", "w_jsonb", "w_both")) {
            recordRelationSizes(connection, rows, table);
        }

        execute(connection, "ALTER TABLE w_both DISABLE TRIGGER w_both_sync_custom_fields");
        execute(connection, "UPDATE w_both SET custom_fields = '{}'");
        execute(connection, "ALTER TABLE w_both ENABLE TRIGGER w_both_sync_custom_fields");
        execute(connection, "VACUUM w_both");
        start = System.nanoTime();
        execute(connection, "UPDATE w_both SET custom_fields = custom_fields");
        double triggerBackfill = millis(start);
        appendCsv("writes.csv", row(rows, "w_both", "backfill_via_trigger_noop_update", rows + writes, "", "", "",
            fmt((rows + writes) / (triggerBackfill / 1000.0)), fmt(triggerBackfill)));
        assertThat(query(connection, "SELECT count(*) FROM w_both WHERE custom_fields <> "
            + setBasedBackfillExpression()).get(0).get(0))
            .as("trigger backfill equals the set-based jsonb_build_object over the same cf_* values").isEqualTo(0L);

        execute(connection, "ALTER TABLE w_cf ADD COLUMN custom_fields jsonb NOT NULL DEFAULT '{}'");
        start = System.nanoTime();
        execute(connection, "UPDATE w_cf SET custom_fields = " + setBasedBackfillExpression());
        double setBackfill = millis(start);
        appendCsv("writes.csv", row(rows, "w_cf", "backfill_set_based_update", rows + writes, "", "", "",
            fmt((rows + writes) / (setBackfill / 1000.0)), fmt(setBackfill)));
        assertThat(query(connection, "SELECT count(*) FROM w_cf c JOIN w_both b USING (id)"
            + " WHERE c.custom_fields <> b.custom_fields").get(0).get(0))
            .as("set-based backfill and trigger backfill agree").isEqualTo(0L);
        log("N=%d: trigger backfill %s ms, set-based backfill %s ms", rows, fmt(triggerBackfill), fmt(setBackfill));
    }

    private void timedInserts(Connection connection, int rows, String table, String sql, List<List<Object>> source,
            boolean jsonbOnly, boolean cfColumns) throws SQLException {
        double[] samples = new double[source.size()];
        long total = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < source.size(); i++) {
                List<Object> values = source.get(i);
                int p = 1;
                statement.setLong(p++, 10_000_000L + rows + ((Number) values.get(0)).longValue());
                for (int c = 1; c < 12; c++) {
                    statement.setObject(p++, values.get(c));
                }
                if (cfColumns) {
                    for (int c = 0; c < CF_COLUMNS.size(); c++) {
                        statement.setObject(p++, values.get(12 + c));
                    }
                }
                if (jsonbOnly) {
                    statement.setString(p, (String) values.get(12 + CF_COLUMNS.size()));
                }
                long start = System.nanoTime();
                statement.executeUpdate();
                samples[i] = (System.nanoTime() - start) / 1_000_000.0;
            }
        }
        recordWrites(rows, table, "insert_single_row_autocommit", samples, millis(total));
    }

    private void timedUpdates(Connection connection, int rows, String table, String sql) throws SQLException {
        Random random = new Random(267);
        double[] samples = new double[writes];
        long total = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < writes; i++) {
                statement.setBigDecimal(1, java.math.BigDecimal.valueOf(random.nextInt(200_000_000), 2));
                statement.setLong(2, 1 + random.nextInt(rows));
                long start = System.nanoTime();
                statement.executeUpdate();
                samples[i] = (System.nanoTime() - start) / 1_000_000.0;
            }
        }
        recordWrites(rows, table, "update_one_custom_field_autocommit", samples, millis(total));
    }

    private void recordWrites(int rows, String table, String operation, double[] samples, double totalMs) {
        Arrays.sort(samples);
        Measurement m = new Measurement(samples, samples.length, null);
        appendCsv("writes.csv", row(rows, table, operation, samples.length, fmt(m.percentile(50)),
            fmt(m.percentile(95)), fmt(m.mean()), fmt(samples.length / (totalMs / 1000.0)), fmt(totalMs)));
        log("N=%d write %-8s %-36s p50=%s p95=%s", rows, table, operation, fmt(m.percentile(50)),
            fmt(m.percentile(95)));
    }

    private static String setBasedBackfillExpression() {
        return CF_COLUMNS.stream().map(c -> {
            String value = switch (CF_TYPES.get(c)) {
                case "check_boxes" -> "spike_yaml_string_array(" + c + ")";
                case "datetime" -> "to_char(" + c + ", 'YYYY-MM-DD\"T\"HH24:MI:SS')";
                case "date" -> "to_char(" + c + ", 'YYYY-MM-DD')";
                default -> c;
            };
            return "'" + c + "', " + value;
        }).collect(Collectors.joining(", ", "jsonb_strip_nulls(jsonb_build_object(", "))"));
    }

    private static String createTableSql(String table, boolean withCf, boolean withJsonb) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(table).append(" (")
            .append("id bigint PRIMARY KEY, user_id integer, assigned_to integer,")
            .append(" name varchar(64) NOT NULL DEFAULT '', access varchar(8) DEFAULT 'Public',")
            .append(" email varchar(254), category varchar(32), rating integer NOT NULL DEFAULT 0,")
            .append(" deleted_at timestamp, created_at timestamp, updated_at timestamp, background_info varchar");
        if (withCf) {
            sql.append(", cf_account_segment varchar, cf_account_code varchar, cf_account_email varchar,")
                .append(" cf_annual_value numeric(15,2), cf_employee_count integer,")
                .append(" cf_health_score double precision, cf_renewal_date date, cf_last_reviewed_at timestamp,")
                .append(" cf_marketing_opt_in boolean, cf_profile_url varchar, cf_interests text, cf_notes text");
        }
        if (withJsonb) {
            sql.append(", custom_fields jsonb NOT NULL DEFAULT '{}'");
        }
        return sql.append(")").toString();
    }

    private static String insertSql(int rows) {
        return """
            INSERT INTO bench_accounts
            SELECT g, 1 + g %% 50, 1 + g %% 37, 'Account ' || g, CASE WHEN g %% 10 = 0 THEN 'Private' ELSE 'Public' END,
              'info' || g || '@example.com',
              (ARRAY['affiliate','competitor','customer','partner','reseller','vendor'])[1 + g %% 6], g %% 6,
              NULL, timestamp '2020-01-01' + g * interval '1 minute', timestamp '2024-01-01' + g * interval '1 minute',
              CASE WHEN u_bg < 0.3 THEN repeat(md5(g::text), 4) END,
              segment, account_code, account_email, annual_value, employee_count, health_score, renewal_date,
              last_reviewed_at, opt_in, profile_url,
              CASE WHEN interests IS NOT NULL THEN
                CASE WHEN cardinality(interests) = 0 THEN '--- []' || chr(10)
                ELSE '---' || chr(10) || array_to_string(
                  ARRAY(SELECT '- ' || i || chr(10) FROM unnest(interests) i), '') END END,
              notes,
              jsonb_strip_nulls(jsonb_build_object(
                'cf_account_segment', segment, 'cf_account_code', account_code, 'cf_account_email', account_email,
                'cf_annual_value', annual_value, 'cf_employee_count', employee_count,
                'cf_health_score', health_score, 'cf_renewal_date', to_char(renewal_date, 'YYYY-MM-DD'),
                'cf_last_reviewed_at', to_char(last_reviewed_at, 'YYYY-MM-DD"T"HH24:MI:SS'),
                'cf_marketing_opt_in', opt_in, 'cf_profile_url', profile_url,
                'cf_interests', to_jsonb(interests), 'cf_notes', notes))
            FROM (
              SELECT g, u_bg,
                CASE WHEN u_seg_null < 0.10 THEN NULL
                     WHEN u_seg < 0.40 THEN 'SMB' WHEN u_seg < 0.65 THEN 'Mid-Market'
                     WHEN u_seg < 0.80 THEN 'Enterprise' WHEN u_seg < 0.88 THEN 'Public Sector'
                     WHEN u_seg < 0.94 THEN 'Education' WHEN u_seg < 0.98 THEN 'Non-profit'
                     WHEN u_seg < 0.995 THEN 'Partner' ELSE 'Strategic' END AS segment,
                CASE WHEN u_code < 0.95 THEN 'AC-' || lpad(g::text, 7, '0') END AS account_code,
                CASE WHEN u_email < 0.60 THEN 'billing' || g || '@example' || (g %% 500) || '.com' END
                  AS account_email,
                CASE WHEN u_val_null < 0.70 THEN round((power(u_val, 3) * 2000000)::numeric, 2) END AS annual_value,
                CASE WHEN u_emp_null < 0.50 THEN floor(power(u_emp, 2) * 5000)::int END AS employee_count,
                CASE WHEN u_health_null < 0.40 THEN round((u_health * 100)::numeric, 4)::float8 END AS health_score,
                CASE WHEN u_renew_null < 0.35 THEN date '2025-01-01' + floor(u_renew * 1095)::int END
                  AS renewal_date,
                CASE WHEN u_review_null < 0.50
                  THEN date_trunc('second', timestamp '2024-01-01' + u_review * interval '1000 days') END
                  AS last_reviewed_at,
                CASE WHEN u_opt_null < 0.80 THEN u_opt < 0.30 END AS opt_in,
                CASE WHEN u_url < 0.30 THEN 'https://example.com/accounts/' || g END AS profile_url,
                CASE WHEN u_int_null < 0.45 THEN ARRAY(
                  SELECT o FROM unnest(ARRAY['Email','Events','Product updates','Webinars','Newsletter'])
                    WITH ORDINALITY AS t(o, i)
                  WHERE (mask & (1 << (i::int - 1))) <> 0
                  UNION ALL SELECT 'Partner program' WHERE u_partner < 0.05) END AS interests,
                CASE WHEN u_notes < 0.20 THEN 'Note ' || repeat(md5((g * 7)::text), 3) END AS notes
              FROM (
                SELECT g, random() u_bg, random() u_seg_null, random() u_seg, random() u_code, random() u_email,
                  random() u_val_null, random() u_val, random() u_emp_null, random() u_emp, random() u_health_null,
                  random() u_health, random() u_renew_null, random() u_renew, random() u_review_null,
                  random() u_review, random() u_opt_null, random() u_opt, random() u_url, random() u_int_null,
                  floor(random() * 32)::int AS mask, random() u_partner, random() u_notes
                FROM generate_series(1, %d) g
              ) draws
            ) s
            """.formatted(rows);
    }

    private static void installHelpers(Connection connection) throws SQLException {
        execute(connection, "CREATE TABLE field_groups (id bigserial PRIMARY KEY, name varchar(64),"
            + " label varchar(128), position integer, hint varchar, created_at timestamp, updated_at timestamp,"
            + " tag_id integer, klass_name varchar(32))");
        execute(connection, "CREATE TABLE fields (id bigserial PRIMARY KEY, type varchar, field_group_id integer,"
            + " position integer, name varchar(64), label varchar(128), \"as\" varchar(32), collection text,"
            + " required boolean, minlength integer DEFAULT 0, maxlength integer, pair_id integer)");
        execute(connection, "INSERT INTO field_groups (id, name, label, klass_name) VALUES (1, 'custom', 'Custom',"
            + " 'Account')");
        int position = 1;
        for (String column : CF_COLUMNS) {
            execute(connection, "INSERT INTO fields (type, field_group_id, position, name, label, \"as\") VALUES"
                + " ('CustomField', 1, " + position++ + ", '" + column + "', '" + column + "', '"
                + CF_TYPES.get(column) + "')");
        }
        execute(connection, resource("/spike/customfields/dual_read_trigger.sql"));
    }

    private void writeEnvironment(Connection connection, List<Integer> rowCounts) throws SQLException {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("generated_at", Instant.now().toString());
        env.put("postgres_version", query(connection, "SELECT version()").get(0).get(0));
        for (String setting : List.of("shared_buffers", "work_mem", "maintenance_work_mem", "effective_cache_size",
            "random_page_cost", "jit", "max_parallel_workers_per_gather", "synchronous_commit", "fsync")) {
            env.put("pg." + setting, query(connection, "SHOW " + setting).get(0).get(0));
        }
        env.put("java.version", System.getProperty("java.version"));
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        env.put("cpus", Runtime.getRuntime().availableProcessors());
        env.put("cpu_model", cpuModel());
        env.put("rows", rowCounts);
        env.put("reps", reps);
        env.put("warmup", warmup);
        env.put("writes", writes);
        String json = env.entrySet().stream()
            .map(e -> "  \"" + e.getKey() + "\": " + (e.getValue() instanceof Number || e.getValue() instanceof List
                ? e.getValue().toString() : "\"" + String.valueOf(e.getValue()).replace("\"", "\\\"") + "\""))
            .collect(Collectors.joining(",\n", "{\n", "\n}\n"));
        writeText("environment.json", json);
    }

    private static String cpuModel() {
        try {
            return Files.readAllLines(Path.of("/proc/cpuinfo")).stream().filter(l -> l.startsWith("model name"))
                .findFirst().map(l -> l.substring(l.indexOf(':') + 1).trim()).orElse("unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<List<Object>> query(Connection connection, String sql) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            ResultSetMetaData meta = resultSet.getMetaData();
            while (resultSet.next()) {
                List<Object> row = new ArrayList<>(meta.getColumnCount());
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.add(resultSet.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static long relationSize(Connection connection, String relation) throws SQLException {
        return ((Number) query(connection, "SELECT pg_relation_size('" + relation + "')").get(0).get(0)).longValue();
    }

    private static String resource(String name) {
        try (InputStream in = CustomFieldsJsonbBenchmark.class.getResourceAsStream(name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void initCsv(String name, String header) {
        writeText(name, header + "\n");
    }

    private void appendCsv(String name, String line) {
        try {
            Files.writeString(outputDir.resolve(name), line + "\n", StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeText(String name, String text) {
        try {
            Files.writeString(outputDir.resolve(name), text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String row(Object... values) {
        return Arrays.stream(values).map(String::valueOf).collect(Collectors.joining(","));
    }

    private static double millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static void log(String format, Object... args) {
        System.out.println("[benchmark] " + String.format(Locale.ROOT, format, args));
    }

    private record QueryVariant(String scenario, String variant, List<String> forms, String predicateOrSql) {

        boolean usesGin() {
            return variant.startsWith("jsonb_contains") || variant.startsWith("jsonb_path")
                || variant.startsWith("jsonb_key");
        }

        String sql(String form) {
            return switch (form) {
                case "count" -> "SELECT count(*) FROM " + TABLE + " WHERE " + predicateOrSql;
                case "page" -> predicateOrSql.startsWith("SELECT") ? predicateOrSql
                    : "SELECT id, name FROM " + TABLE + " WHERE " + predicateOrSql + " ORDER BY id DESC LIMIT 25";
                default -> throw new IllegalArgumentException(form);
            };
        }
    }

    private record Measurement(double[] sorted, int rowsReturned, Object result) {

        double percentile(int p) {
            int rank = (int) Math.ceil(p / 100.0 * sorted.length);
            return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
        }

        double mean() {
            return Arrays.stream(sorted).average().orElse(0);
        }

        double min() {
            return sorted[0];
        }

        double max() {
            return sorted[sorted.length - 1];
        }
    }
}
