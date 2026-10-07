package com.fatfreecrm.spike.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Demonstrates the four query shapes a future search feature (AB-269) can use
 * against a jsonb column, and verifies via EXPLAIN which shapes can use GIN
 * indexes. Seeds ~2000 rows into {@code spike_accounts_json} with a
 * {@code jsonb_path_ops} GIN index and a default {@code jsonb_ops} GIN index.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonbQueryShapesTest {

    private static final int ROWS = 2000;
    private static final String CONTAINMENT_JSON = "{\"cf_segment\": \"prospect\"}";

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("spike_query_shapes")
        .withUsername("postgres")
        .withPassword("postgres");

    private EntityManagerFactory emf;
    private EntityManager em;
    private SpikeAccountJsonRepository repo;

    @BeforeAll
    void setUp() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            Statement st = conn.createStatement()) {
            st.execute("create table spike_accounts_json ("
                + "id bigserial primary key, name varchar(64) not null,"
                + " custom_fields jsonb not null default '{}')");
            st.execute("create index spike_cf_path_ops_idx on spike_accounts_json"
                + " using gin (custom_fields jsonb_path_ops)");
            st.execute("create index spike_cf_ops_idx on spike_accounts_json"
                + " using gin (custom_fields)");

            try (PreparedStatement ps = conn.prepareStatement(
                "insert into spike_accounts_json (name, custom_fields) values (?, ?::jsonb)")) {
                for (int i = 0; i < ROWS; i++) {
                    ps.setString(1, "acct-" + i);
                    String segment = (i % 10 == 0) ? "prospect" : "customer";
                    ps.setString(2, "{\"cf_segment\": \"" + segment + "\", \"cf_score\": " + (i % 100) + "}");
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            st.execute("analyze spike_accounts_json");
        }
        DriverManagerDataSource ds = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        emf = SpikeJpa.createEmf(ds);
        em = emf.createEntityManager();
        repo = new JpaRepositoryFactory(em).getRepository(SpikeAccountJsonRepository.class);
    }

    @AfterAll
    void tearDown() {
        if (em != null) {
            em.close();
        }
        if (emf != null) {
            emf.close();
        }
    }

    private static List<Long> jdbcIds(String sql) throws Exception {
        List<Long> ids = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    private static List<String> explain(Connection conn, String sql) throws Exception {
        List<String> plan = new ArrayList<>();
        try (Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("explain " + sql)) {
            while (rs.next()) {
                plan.add(rs.getString(1));
            }
        }
        return plan;
    }

    @Test
    void fourQueryShapesReturnTheSameRows() throws Exception {
        // (a) Spring Data Specification + paging
        var page = repo.findAll(
            CustomFieldSpecifications.<SpikeAccountJson>contains(
                "customFields", Map.of("cf_segment", "prospect")),
            PageRequest.of(0, 1000));
        assertThat(page.getTotalElements()).isEqualTo(ROWS / 10);

        // (b) JPQL via the spike_jsonb_contains function
        List<SpikeAccountJson> jpql = repo.findContainingJpql(CONTAINMENT_JSON);
        assertThat(jpql).hasSize(ROWS / 10);

        // (c) native @Query with the @> operator
        List<SpikeAccountJson> nativeContains = repo.findContainingNative(CONTAINMENT_JSON);
        assertThat(nativeContains).hasSize(ROWS / 10);

        // (d) jsonpath: function form vs operator form, rows where cf_score > 50.
        // NOTE: these must run via plain JDBC - Hibernate's native-query parameter
        // parsing treats every literal '?' (in '@?' and inside the jsonpath string)
        // as a positional parameter and fails with 'No argument for ordinal parameter'.
        List<Long> fnForm = jdbcIds(
            "select id from spike_accounts_json"
                + " where jsonb_path_exists(custom_fields, '$.cf_score ? (@ > 50)')");
        List<Long> opForm = jdbcIds(
            "select id from spike_accounts_json"
                + " where custom_fields @? '$.cf_score ? (@ > 50)'");
        assertThat(fnForm).isNotEmpty();
        assertThat(opForm).hasSameSizeAs(fnForm);

        // spec-generated SQL, for the report
        SqlRecorder.clear();
        repo.findAll(
            CustomFieldSpecifications.<SpikeAccountJson>contains(
                "customFields", Map.of("cf_segment", "prospect")),
            PageRequest.of(0, 10));
        String generated = SqlRecorder.statements().stream()
            .filter(s -> s.startsWith("select") && s.contains("@>"))
            .findFirst().orElseThrow();
        assertThat(generated).contains("@>").contains("jsonb");
    }

    /**
     * KEY CHECK: the operator forms (@> and @?) use the GIN index
     * (Bitmap Index Scan), while the equivalent function forms
     * (jsonb_contains / jsonb_path_exists) do NOT - PostgreSQL's planner only
     * recognizes the operators as GIN-indexable.
     */
    @Test
    void operatorFormsUseGinIndexFunctionFormsDoNot() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            Statement st = conn.createStatement()) {
            st.execute("set enable_seqscan = off");

            // capture the SQL Hibernate generated for the Specification
            SqlRecorder.clear();
            repo.findAll(
                CustomFieldSpecifications.<SpikeAccountJson>contains(
                    "customFields", Map.of("cf_segment", "prospect")),
                PageRequest.of(0, 10));
            String hibernateSql = SqlRecorder.statements().stream()
                .filter(s -> s.startsWith("select") && s.contains("@>"))
                .findFirst().orElseThrow();
            // substitute bound parameters with literals for EXPLAIN:
            // the jsonb operand appears as cast(? as jsonb); any other '?' is the fetch limit
            String explainable = hibernateSql
                .replace("cast(? as jsonb)", "'" + CONTAINMENT_JSON + "'::jsonb")
                .replace("?", "10");
            List<String> specPlan = explain(conn, explainable);
            assertThat(String.join("\n", specPlan))
                .contains("Bitmap Index Scan on spike_cf_path_ops_idx");

            List<String> opPlan = explain(conn,
                "select * from spike_accounts_json where custom_fields @> '" + CONTAINMENT_JSON + "'::jsonb");
            assertThat(String.join("\n", opPlan))
                .contains("Bitmap Index Scan on spike_cf_path_ops_idx");

            List<String> fnPlan = explain(conn,
                "select * from spike_accounts_json where jsonb_contains(custom_fields, '"
                    + CONTAINMENT_JSON + "'::jsonb)");
            assertThat(String.join("\n", fnPlan))
                .doesNotContain("Bitmap Index Scan on spike_cf_path_ops_idx")
                .doesNotContain("Bitmap Index Scan on spike_cf_ops_idx");

            List<String> pathOpPlan = explain(conn,
                "select * from spike_accounts_json where custom_fields @? '$.cf_score ? (@ > 50)'");
            assertThat(String.join("\n", pathOpPlan))
                .contains("Bitmap Index Scan");

            List<String> pathFnPlan = explain(conn,
                "select * from spike_accounts_json"
                    + " where jsonb_path_exists(custom_fields, '$.cf_score ? (@ > 50)')");
            assertThat(String.join("\n", pathFnPlan))
                .doesNotContain("Bitmap Index Scan on spike_cf_path_ops_idx")
                .doesNotContain("Bitmap Index Scan on spike_cf_ops_idx");
        }
    }
}
