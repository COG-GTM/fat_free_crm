package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class CustomFieldsTriggerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void synchronizesDynamicColumnsAndRetainsResolvedYamlArraysOnUnrelatedUpdates() {
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_text_trigger text");
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_boxes_trigger text");
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_orphan_trigger text");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (990271, 'Account', 'AB-271 trigger test', 1, now(), now())");
        insertField(990271, "cf_boxes_trigger", "check_boxes");

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
    }

    private void insertField(long fieldGroupId, String name, String as) {
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "collection, disabled, required, created_at, updated_at) "
                + "VALUES (?, 'CustomField', ?, 1, ?, ?, ?, '--- []', false, false, now(), now())",
            990271L, fieldGroupId, name, name, as);
    }

    private String document(long id) {
        return jdbcTemplate.queryForObject(
            "SELECT custom_fields::text FROM accounts WHERE id = ?", String.class, id);
    }
}
