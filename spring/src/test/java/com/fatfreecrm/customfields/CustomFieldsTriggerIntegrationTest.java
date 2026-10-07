package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CustomFieldsTriggerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomFieldRegistry registry;

    @Autowired
    private CustomFieldsBackfillJob backfillJob;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM accounts WHERE name LIKE 'ab271-trigger%'");
        jdbcTemplate.update("DELETE FROM fields WHERE id IN (990271, 990272)");
        jdbcTemplate.update("DELETE FROM field_groups WHERE id IN (990271, 990272)");
        jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_text_trigger");
        jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_boxes_trigger");
        jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_orphan_trigger");
        jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_boxes_malformed_trigger");
        registry.invalidate();
    }

    @Test
    void synchronizesDynamicColumnsAndRetainsResolvedYamlArraysOnUnrelatedUpdates() {
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_text_trigger text");
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_boxes_trigger text");
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_orphan_trigger text");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (990271, 'Account', 'AB-271 trigger test', 1, now(), now())");
        insertField(990271, 990271, "cf_boxes_trigger", "check_boxes");

        jdbcTemplate.update(
            "INSERT INTO accounts (name, custom_fields, cf_text_trigger, cf_boxes_trigger, cf_orphan_trigger) "
                + "VALUES ('ab271-trigger', '{\"java_only\":\"keep\"}'::jsonb, ?, ?, ?)",
            "raw text", "---\n- true\n- '123'\n", "orphan");
        long id = jdbcTemplate.queryForObject(
            "SELECT id FROM accounts WHERE name = 'ab271-trigger'", Long.class);
        assertThat(document(id))
            .contains("\"cf_text_trigger\": \"raw text\"")
            .contains("\"cf_boxes_trigger\": [\"true\", \"123\"]")
            .contains("\"cf_orphan_trigger\": \"orphan\"")
            .contains("\"java_only\": \"keep\"");

        jdbcTemplate.update("UPDATE accounts SET cf_text_trigger = NULL WHERE id = ?", id);
        assertThat(document(id)).doesNotContain("cf_text_trigger");

        jdbcTemplate.update(
            "UPDATE accounts SET cf_boxes_trigger = ? WHERE id = ?",
            "---\n- |-\n  multi\n  line\n", id);
        assertThat(document(id)).contains("\"$yaml\"");
        jdbcTemplate.update(
            "UPDATE accounts SET custom_fields = '{\"cf_boxes_trigger\":[\"resolved\"]}'::jsonb WHERE id = ?", id);
        jdbcTemplate.update("UPDATE accounts SET name = name || '-updated' WHERE id = ?", id);
        assertThat(document(id)).contains("\"cf_boxes_trigger\": [\"resolved\"]");

        jdbcTemplate.update(
            "UPDATE accounts SET cf_boxes_trigger = ? WHERE id = ?",
            "---\n- |-\n  changed\n  multiline\n", id);
        assertThat(document(id)).contains("\"$yaml\"");
        jdbcTemplate.update(
            "UPDATE accounts SET cf_boxes_trigger = 'plain checkbox text' WHERE id = ?", id);
        assertThat(document(id)).contains("\"cf_boxes_trigger\": \"plain checkbox text\"");

        jdbcTemplate.update("UPDATE accounts SET custom_fields = '[]'::jsonb WHERE id = ?", id);
        assertThat(document(id)).contains("\"cf_boxes_trigger\": \"plain checkbox text\"");
    }

    @Test
    void storesMalformedYamlAsMarkersAndReportsValuesJsonbCannotResolve() {
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_boxes_malformed_trigger text");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (990272, 'Account', 'AB-271 malformed YAML trigger test', 1, now(), now())");
        insertField(990272, 990272, "cf_boxes_malformed_trigger", "check_boxes");
        registry.invalidate();

        List<MalformedYamlCase> cases = List.of(
            new MalformedYamlCase("non-hex escape", yamlEscape("ZZZZ"), false),
            new MalformedYamlCase("truncated escape", yamlEscape("00"), false),
            new MalformedYamlCase("NUL escape", yamlEscape("0000"), false),
            new MalformedYamlCase("lone surrogate escape", yamlEscape("D800"), true));
        for (int index = 0; index < cases.size(); index++) {
            MalformedYamlCase malformed = cases.get(index);
            String name = "ab271-trigger-malformed-" + index;
            Long id = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (name) VALUES (?) RETURNING id", Long.class, name);
            jdbcTemplate.update(
                "UPDATE accounts SET cf_boxes_malformed_trigger = ? WHERE id = ?", malformed.raw(), id);
            assertThat(marker(id)).as(malformed.label()).isEqualTo(malformed.raw());
        }

        var report = backfillJob.run();
        var accounts = report.tables().get("accounts");
        assertThat(report.ok()).isFalse();
        assertThat(accounts.drift()).isZero();
        for (int index = 0; index < cases.size(); index++) {
            MalformedYamlCase malformed = cases.get(index);
            Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE name = ?", Long.class, "ab271-trigger-malformed-" + index);
            boolean remainsMarker = marker(id) != null;
            assertThat(remainsMarker).as(malformed.label()).isEqualTo(!malformed.resolves());
            if (malformed.resolves()) {
                assertThat(jdbcTemplate.queryForObject(
                    "SELECT jsonb_typeof(custom_fields -> 'cf_boxes_malformed_trigger') "
                        + "FROM accounts WHERE id = ?",
                    String.class,
                    id)).as(malformed.label()).isEqualTo("array");
            } else {
                assertThat(marker(id)).as(malformed.label()).isEqualTo(malformed.raw());
            }
        }
        assertThat(accounts.markersRemaining())
            .isEqualTo(cases.stream().filter(malformed -> !malformed.resolves()).count());
    }

    private void insertField(long id, long fieldGroupId, String name, String as) {
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "collection, disabled, required, created_at, updated_at) "
                + "VALUES (?, 'CustomField', ?, 1, ?, ?, ?, '--- []', false, false, now(), now())",
            id, fieldGroupId, name, name, as);
    }

    private String document(long id) {
        return jdbcTemplate.queryForObject(
            "SELECT custom_fields::text FROM accounts WHERE id = ?", String.class, id);
    }

    private String marker(long id) {
        return jdbcTemplate.queryForObject(
            "SELECT custom_fields -> 'cf_boxes_malformed_trigger' ->> '$yaml' FROM accounts WHERE id = ?",
            String.class,
            id);
    }

    private static String yamlEscape(String codepoint) {
        return "---\n- \"" + "\\" + "u" + codepoint + "\"\n";
    }

    private record MalformedYamlCase(String label, String raw, boolean resolves) {
    }
}
