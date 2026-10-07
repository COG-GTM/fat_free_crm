package com.fatfreecrm.spike.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises spike/customfields/dual_read_trigger.sql: the trigger merges cf_*
 * columns into the custom_fields jsonb document on INSERT/UPDATE.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomFieldsSyncTriggerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("spike_trigger")
        .withUsername("postgres")
        .withPassword("postgres");

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

            st.execute("create table spike_trigger_accounts ("
                + "id bigserial primary key, name varchar(64),"
                + " custom_fields jsonb not null default '{}',"
                + " cf_segment varchar, cf_amount numeric(15,2), cf_score integer,"
                + " cf_opt_in boolean, cf_since date, cf_seen_at timestamp, cf_interests text)");

            String sql;
            try (InputStream in = getClass()
                .getResourceAsStream("/spike/customfields/dual_read_trigger.sql")) {
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            st.execute(sql);
            st.execute("create trigger spike_sync_cf before insert or update on spike_trigger_accounts"
                + " for each row execute function spike_sync_custom_fields('Account')");
        }
    }

    private static Connection conn() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static JsonNode customFields(Connection conn, long id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
            "select custom_fields::text from spike_trigger_accounts where id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return MAPPER.readTree(rs.getString(1));
            }
        }
    }

    @Test
    void insertMapsEveryColumnType() throws Exception {
        try (Connection conn = conn(); PreparedStatement ps = conn.prepareStatement(
            "insert into spike_trigger_accounts (name, cf_segment, cf_amount, cf_score,"
                + " cf_opt_in, cf_since, cf_seen_at) values"
                + " ('a', 'prospect', 1234.50, 7, true, '2024-01-31', '2024-01-31 10:00:00')",
            Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            long id;
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                id = rs.getLong(1);
            }
            JsonNode cf = customFields(conn, id);
            assertThat(cf.get("cf_segment").asText()).isEqualTo("prospect");
            // numeric stays a number, but JSONB canonicalizes 1234.50 -> 1234.5 (scale is NOT preserved)
            assertThat(cf.get("cf_amount").isNumber()).isTrue();
            assertThat(cf.get("cf_amount").decimalValue().toPlainString()).isEqualTo("1234.5");
            assertThat(cf.get("cf_score").intValue()).isEqualTo(7);
            assertThat(cf.get("cf_opt_in").booleanValue()).isTrue();
            assertThat(cf.get("cf_since").asText()).isEqualTo("2024-01-31");
            assertThat(cf.get("cf_seen_at").asText()).isEqualTo("2024-01-31T10:00:00");
        }
    }

    @Test
    void updateNullAndJavaOnlyKeySemantics() throws Exception {
        try (Connection conn = conn(); Statement st = conn.createStatement()) {
            st.execute("insert into spike_trigger_accounts (name, cf_segment)"
                + " values ('u', 'prospect')");
            st.execute("update spike_trigger_accounts set cf_segment = 'vip',"
                + " custom_fields = custom_fields || '{\"j_only\": 1}'::jsonb where name = 'u'");
            JsonNode cf;
            try (ResultSet rs = st.executeQuery(
                "select id, custom_fields::text from spike_trigger_accounts where name = 'u'")) {
                rs.next();
                cf = MAPPER.readTree(rs.getString(2));
            }
            assertThat(cf.get("cf_segment").asText()).isEqualTo("vip");
            // Java-only key (no cf_ column) survives a cf_ update
            assertThat(cf.get("j_only").intValue()).isEqualTo(1);

            // setting the cf_ column to NULL removes the key
            st.execute("update spike_trigger_accounts set cf_segment = null where name = 'u'");
            cf = customFields(conn, jsonId(conn, "u"));
            assertThat(cf.has("cf_segment")).isFalse();
            assertThat(cf.get("j_only").intValue()).isEqualTo(1);
        }
    }

    private static long jsonId(Connection conn, String name) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
            "select id from spike_trigger_accounts where name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void psychFixturesDecodeOrProduceMarker() throws Exception {
        CheckBoxesYamlCodec codec = new CheckBoxesYamlCodec();
        List<String> markers = new ArrayList<>();
        try (InputStream in = getClass()
            .getResourceAsStream("/spike/customfields/check_boxes_yaml_fixtures.json");
            Connection conn = conn()) {
            JsonNode fixtures = MAPPER.readTree(in);
            int i = 0;
            for (JsonNode fixture : fixtures) {
                i++;
                String name = "fx" + i;
                try (PreparedStatement ps = conn.prepareStatement(
                    "insert into spike_trigger_accounts (name, cf_interests) values (?, ?)")) {
                    ps.setString(1, name);
                    ps.setString(2, fixture.get("yaml").asText());
                    ps.executeUpdate();
                }
                JsonNode cf = customFields(conn, jsonId(conn, name));
                JsonNode val = cf.get("cf_interests");
                List<String> expected = new ArrayList<>();
                fixture.get("values").forEach(v -> expected.add(v.asText()));
                if (val.has("$yaml")) {
                    // SQL decoder punted: Java side decodes the marker
                    markers.add(expected.toString());
                    assertThat(codec.decode(val.get("$yaml").asText())).isEqualTo(expected);
                } else {
                    List<String> actual = new ArrayList<>();
                    val.forEach(v -> actual.add(v.asText()));
                    assertThat(actual).isEqualTo(expected);
                }
            }
        }
        // expected marker only for the multi-line fixture (Psych block scalar |-)
        assertThat(markers).containsExactly(List.of("multi\nline").toString());
    }

    @Test
    void setBasedBackfillIsLossless() throws Exception {
        // ordered cf column -> fields."as" for spike_trigger_accounts
        java.util.Map<String, String> cfColumnTypes = new java.util.LinkedHashMap<>();
        cfColumnTypes.put("cf_segment", "string");
        cfColumnTypes.put("cf_amount", "decimal");
        cfColumnTypes.put("cf_score", "integer");
        cfColumnTypes.put("cf_opt_in", "boolean");
        cfColumnTypes.put("cf_since", "date");
        cfColumnTypes.put("cf_seen_at", "datetime");
        cfColumnTypes.put("cf_interests", "check_boxes");
        String expr = CustomFieldsBackfill.setBasedExpression(cfColumnTypes);

        try (Connection conn = conn(); Statement st = conn.createStatement()) {
            // row 1: Java-only key + a multiline block-scalar YAML the SQL decoder cannot handle
            st.execute("insert into spike_trigger_accounts (name, cf_segment, cf_interests,"
                + " custom_fields) values ('bl1', 'prospect',"
                + " '---\n- |-\n  multi\n  line\n', '{\"cf_java_only\":\"kept\"}'::jsonb)");
            // row 2: a simple decodable YAML array
            st.execute("insert into spike_trigger_accounts (name, cf_interests)"
                + " values ('bl2', '---\n- A\n- B\n')");

            // capture the trigger's output for the same rows first
            JsonNode trigger1 = customFields(conn, jsonId(conn, "bl1"));
            JsonNode trigger2 = customFields(conn, jsonId(conn, "bl2"));

            // reset documents as if never backfilled, then run the set-based UPDATE
            st.execute("update spike_trigger_accounts set custom_fields ="
                + " case when name = 'bl1' then '{\"cf_java_only\":\"kept\"}'::jsonb"
                + " else '{}'::jsonb end");
            st.execute("alter table spike_trigger_accounts disable trigger spike_sync_cf");
            st.execute("update spike_trigger_accounts set custom_fields = " + expr);
            st.execute("alter table spike_trigger_accounts enable trigger spike_sync_cf");

            JsonNode backfill1 = customFields(conn, jsonId(conn, "bl1"));
            JsonNode backfill2 = customFields(conn, jsonId(conn, "bl2"));

            // Java-only key preserved; multiline YAML becomes the $yaml marker; simple array decoded
            assertThat(backfill1.get("cf_java_only").asText()).isEqualTo("kept");
            assertThat(backfill1.get("cf_interests").get("$yaml").asText())
                .isEqualTo("---\n- |-\n  multi\n  line\n");
            assertThat(backfill2.get("cf_interests")).isEqualTo(MAPPER.readTree("[\"A\",\"B\"]"));
            // identical to what the trigger produces for the same rows
            assertThat(backfill1).isEqualTo(trigger1);
            assertThat(backfill2).isEqualTo(trigger2);
        }
    }

    @Test
    void runtimeAddedColumnAndOrphanAndBackfill() throws Exception {
        try (Connection conn = conn(); Statement st = conn.createStatement()) {
            // runtime ADD COLUMN: no trigger change needed, key appears on next write
            st.execute("alter table spike_trigger_accounts add column cf_new_field varchar");
            st.execute("insert into spike_trigger_accounts (name, cf_new_field) values ('n1', 'v1')");
            JsonNode cf = customFields(conn, jsonId(conn, "n1"));
            assertThat(cf.get("cf_new_field").asText()).isEqualTo("v1");

            // orphan column (no fields metadata row) is still copied; Java filters on read
            assertThat(cf.has("cf_new_field")).isTrue();

            // backfill: disable trigger, insert raw rows, enable, no-op update
            st.execute("alter table spike_trigger_accounts disable trigger spike_sync_cf");
            st.execute("insert into spike_trigger_accounts (name, cf_segment)"
                + " values ('bf', 'old')");
            st.execute("alter table spike_trigger_accounts enable trigger spike_sync_cf");
            JsonNode before = customFields(conn, jsonId(conn, "bf"));
            assertThat(before.has("cf_segment")).isFalse();
            st.execute("update spike_trigger_accounts set custom_fields = custom_fields"
                + " where name = 'bf'");
            JsonNode after = customFields(conn, jsonId(conn, "bf"));
            assertThat(after.get("cf_segment").asText()).isEqualTo("old");
        }
    }
}
