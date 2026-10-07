package com.fatfreecrm.spike.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end dual-read: rows written the way Rails writes them (typed cf_* columns,
 * Psych YAML for check_boxes) go through {@code spike_sync_custom_fields()} and the
 * resulting jsonb document through {@link CustomFieldsDualReader}. Expected values are
 * the Rails-side representations: the column value Rails would read back, dates as
 * ISO-8601, timestamps as UTC, numeric(15,2) with two decimals, check_boxes arrays
 * with blank items removed ({@code Field#render} uses {@code select(&:present?)}).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomFieldsDualReadParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("spike_dual_read")
        .withUsername("postgres")
        .withPassword("postgres");

    private static FieldDefinition field(String name, String as) {
        return new FieldDefinition(name, name, as, false, null, null, null, null, null);
    }

    private static final List<FieldDefinition> META = List.of(
        field("cf_segment", "string"),
        field("cf_amount", "decimal"),
        field("cf_score", "integer"),
        field("cf_opt_in", "boolean"),
        field("cf_since", "date"),
        field("cf_seen_at", "datetime"),
        field("cf_interests", "check_boxes"));

    @BeforeAll
    void setUp() throws Exception {
        try (Connection conn = conn(); Statement st = conn.createStatement()) {
            st.execute("create table field_groups (id bigint primary key, klass_name varchar(32))");
            st.execute("create table fields (id bigint primary key, type varchar,"
                + " field_group_id integer, position integer, name varchar(64), label varchar(128),"
                + " \"as\" varchar(32), collection text, required boolean, pair_id integer)");
            st.execute("insert into field_groups (id, klass_name) values (1, 'Account')");
            st.execute("insert into fields (id, field_group_id, name, label, \"as\") values"
                + " (1, 1, 'cf_segment', 'Segment', 'string'),"
                + " (2, 1, 'cf_amount', 'Amount', 'decimal'),"
                + " (3, 1, 'cf_score', 'Score', 'integer'),"
                + " (4, 1, 'cf_opt_in', 'Opt in', 'boolean'),"
                + " (5, 1, 'cf_since', 'Since', 'date'),"
                + " (6, 1, 'cf_seen_at', 'Seen at', 'datetime'),"
                + " (7, 1, 'cf_interests', 'Interests', 'check_boxes')");
            st.execute("create table spike_dual_read_accounts ("
                + "id bigserial primary key, name varchar(64),"
                + " custom_fields jsonb not null default '{}',"
                + " cf_segment varchar, cf_amount numeric(15,2), cf_score integer,"
                + " cf_opt_in boolean, cf_since date, cf_seen_at timestamp, cf_interests text,"
                + " cf_orphan varchar)");
            String sql;
            try (InputStream in = getClass()
                .getResourceAsStream("/spike/customfields/dual_read_trigger.sql")) {
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            st.execute(sql);
            st.execute("create trigger spike_sync_cf before insert or update on spike_dual_read_accounts"
                + " for each row execute function spike_sync_custom_fields('Account')");
        }
    }

    private static Connection conn() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Map<String, Object> jsonbDocument(Connection conn, String name) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
            "select custom_fields::text from spike_dual_read_accounts where name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return MAPPER.readValue(rs.getString(1), JSON_MAP);
            }
        }
    }

    private static Map<String, Object> readJsonbOnly(Connection conn, String name) throws Exception {
        return CustomFieldsDualReader.read(Map.of(), jsonbDocument(conn, name), META);
    }

    @Test
    void triggerDocumentReadsBackAsRailsRepresentations() throws Exception {
        try (Connection conn = conn(); Statement st = conn.createStatement()) {
            st.execute("insert into spike_dual_read_accounts (name, cf_segment, cf_amount, cf_score,"
                + " cf_opt_in, cf_since, cf_seen_at, cf_interests, cf_orphan) values"
                + " ('full', 'prospect', 1234.50, 7, true, '2024-01-31', '2024-01-31 10:00:00.250000',"
                + " '---\n- Email\n- Events\n- Product updates\n', 'orphan-value')");
            Map<String, Object> read = readJsonbOnly(conn, "full");

            assertThat(read.get("cf_segment")).isEqualTo("prospect");
            // jsonb canonicalizes 1234.50 -> 1234.5; READ restores the numeric(15,2) scale
            assertThat(read.get("cf_amount")).isEqualTo(new BigDecimal("1234.50"));
            assertThat(read.get("cf_score")).isEqualTo(7);
            assertThat(read.get("cf_opt_in")).isEqualTo(true);
            assertThat(read.get("cf_since")).isEqualTo("2024-01-31");
            // timestamp without time zone holds UTC in Rails: gains the Z, keeps the fraction
            assertThat(read.get("cf_seen_at")).isEqualTo("2024-01-31T10:00:00.25Z");
            assertThat(read.get("cf_interests")).isEqualTo(List.of("Email", "Events", "Product updates"));
            // the trigger copies the orphan column, the reader drops it (no fields row)
            assertThat(jsonbDocument(conn, "full")).containsKey("cf_orphan");
            assertThat(read).doesNotContainKey("cf_orphan");
            assertThat(read).containsOnlyKeys("cf_segment", "cf_amount", "cf_score", "cf_opt_in",
                "cf_since", "cf_seen_at", "cf_interests");
        }
    }

    @Test
    void everyPsychFixtureReadsBackAsRailsRendersIt() throws Exception {
        try (InputStream in = getClass()
            .getResourceAsStream("/spike/customfields/check_boxes_yaml_fixtures.json");
            Connection conn = conn()) {
            JsonNode fixtures = MAPPER.readTree(in);
            int i = 0;
            for (JsonNode fixture : fixtures) {
                i++;
                String name = "yaml" + i;
                try (PreparedStatement ps = conn.prepareStatement(
                    "insert into spike_dual_read_accounts (name, cf_interests) values (?, ?)")) {
                    ps.setString(1, name);
                    ps.setString(2, fixture.get("yaml").asText());
                    ps.executeUpdate();
                }
                // Rails: value.select(&:present?) - blank items never reach the UI
                List<String> expected = new ArrayList<>();
                fixture.get("values").forEach(v -> {
                    if (!v.asText().isBlank()) {
                        expected.add(v.asText());
                    }
                });
                Map<String, Object> viaJsonb = readJsonbOnly(conn, name);
                // whether the SQL decoder handled it or left a {"$yaml": ...} marker is invisible
                assertThat(viaJsonb.get("cf_interests")).as("jsonb path, fixture %s", i).isEqualTo(expected);
                // the column path (raw YAML text, as JDBC returns a text column) must agree
                Map<String, Object> cols = Map.of("cf_interests", fixture.get("yaml").asText());
                Map<String, Object> viaColumn = CustomFieldsDualReader.read(cols, Map.of(), META);
                assertThat(viaColumn.get("cf_interests")).as("column path, fixture %s", i).isEqualTo(expected);
            }
        }
    }

    @Test
    void emptyCheckBoxesSelectionIsAnEmptyArrayNotAbsent() throws Exception {
        // Rails stores "--- []\n" when every box is unchecked; the field still exists
        try (Connection conn = conn(); Statement st = conn.createStatement()) {
            st.execute("insert into spike_dual_read_accounts (name, cf_interests) values ('none', '--- []\n')");
            Map<String, Object> read = readJsonbOnly(conn, "none");
            assertThat(read).containsEntry("cf_interests", List.of());
        }
    }

    @Test
    void clearingAColumnInRailsClearsTheFieldOnBothReadPaths() throws Exception {
        try (Connection conn = conn(); Statement st = conn.createStatement()) {
            st.execute("insert into spike_dual_read_accounts (name, cf_segment, cf_score)"
                + " values ('clear', 'vip', 3)");
            st.execute("update spike_dual_read_accounts set cf_segment = null where name = 'clear'");
            Map<String, Object> read = readJsonbOnly(conn, "clear");
            assertThat(read).doesNotContainKey("cf_segment").containsEntry("cf_score", 3);

            // diagnostic column path with a stale document that still carries the old value
            Map<String, Object> cols = new LinkedHashMap<>();
            cols.put("cf_segment", null);
            cols.put("cf_score", 3);
            Map<String, Object> stale = Map.of("cf_segment", "vip", "cf_score", 3);
            assertThat(CustomFieldsDualReader.read(cols, stale, META))
                .doesNotContainKey("cf_segment").containsEntry("cf_score", 3);
        }
    }

    @Test
    void javaOnlyKeysSurviveRailsWritesButOnlyWhenDefinedInFields() throws Exception {
        List<FieldDefinition> metaWithJavaOnly = new ArrayList<>(META);
        metaWithJavaOnly.add(field("cf_java_only", "string"));
        try (Connection conn = conn(); Statement st = conn.createStatement()) {
            st.execute("insert into spike_dual_read_accounts (name, cf_segment, custom_fields)"
                + " values ('jo', 'a', '{\"cf_java_only\": \"kept\"}'::jsonb)");
            st.execute("update spike_dual_read_accounts set cf_segment = 'b' where name = 'jo'");
            Map<String, Object> doc = jsonbDocument(conn, "jo");
            assertThat(CustomFieldsDualReader.read(Map.of(), doc, metaWithJavaOnly))
                .containsEntry("cf_segment", "b").containsEntry("cf_java_only", "kept");
            // without a fields row the same key is an orphan and is dropped on read
            assertThat(CustomFieldsDualReader.read(Map.of(), doc, META))
                .containsEntry("cf_segment", "b").doesNotContainKey("cf_java_only");
        }
    }

    @Test
    void safeColumnTypeTransitionsReadBackInTheNewType() throws Exception {
        // CustomField::SAFE_DB_TRANSITIONS: date <-> timestamp and integer <-> float change the
        // column without rewriting jsonb; READ normalisation must present the stored value in
        // the field's current type.
        try (Connection conn = conn(); Statement st = conn.createStatement()) {
            st.execute("insert into spike_dual_read_accounts (name, cf_since, cf_seen_at, cf_score)"
                + " values ('tr', '2024-01-31', '2024-01-31 10:00:00', 3)");
            Map<String, Object> doc = jsonbDocument(conn, "tr");

            List<FieldDefinition> flipped = List.of(
                field("cf_since", "datetime"),   // date -> timestamp
                field("cf_seen_at", "date"),     // timestamp -> date
                field("cf_score", "float"));     // integer -> float
            Map<String, Object> read = CustomFieldsDualReader.read(Map.of(), doc, flipped);
            assertThat(read.get("cf_since")).isEqualTo("2024-01-31T00:00:00Z");
            assertThat(read.get("cf_seen_at")).isEqualTo("2024-01-31");
            assertThat(read.get("cf_score")).isEqualTo(3.0d);
        }
    }
}
