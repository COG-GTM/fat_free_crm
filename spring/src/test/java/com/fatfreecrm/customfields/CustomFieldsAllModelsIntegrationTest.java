package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Behaviour shared by every {@code has_fields} model that the other custom-field tests only exercise on
 * {@code accounts}: the V2 trigger on all six tables, write-service timestamps, and Rails'
 * {@code FieldGroup.with_tags} scoping of which definitions apply to a record.
 */
@Transactional
class CustomFieldsAllModelsIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Map<String, String> MINIMAL_INSERTS = Map.of(
        "accounts", "INSERT INTO accounts (name, cf_all_models) VALUES ('ab271-all', ?)",
        "campaigns", "INSERT INTO campaigns (name, cf_all_models) VALUES ('ab271-all', ?)",
        "contacts", "INSERT INTO contacts (first_name, last_name, cf_all_models) VALUES ('ab271', 'all', ?)",
        "leads", "INSERT INTO leads (first_name, last_name, cf_all_models) VALUES ('ab271', 'all', ?)",
        "opportunities", "INSERT INTO opportunities (name, cf_all_models) VALUES ('ab271-all', ?)",
        "tasks", "INSERT INTO tasks (name, cf_all_models) VALUES ('ab271-all', ?)");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomFieldRegistry registry;

    @Autowired
    private CustomFieldWriteService writeService;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void cleanUp() {
        for (String table : MINIMAL_INSERTS.keySet()) {
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP COLUMN IF EXISTS cf_all_models");
        }
        jdbcTemplate.update("DELETE FROM taggings WHERE tag_id = 990901");
        jdbcTemplate.update("DELETE FROM tags WHERE id = 990901");
        jdbcTemplate.update("DELETE FROM fields WHERE id IN (990901, 990902, 990903)");
        jdbcTemplate.update("DELETE FROM field_groups WHERE id IN (990901, 990902)");
        jdbcTemplate.update("DELETE FROM tasks WHERE name LIKE 'ab271-%'");
        jdbcTemplate.update("DELETE FROM contacts WHERE first_name = 'ab271'");
        registry.invalidate();
    }

    @Test
    void syncTriggerExistsAndMirrorsDynamicColumnsOnEveryHasFieldsTable() {
        for (Map.Entry<String, String> entry : MINIMAL_INSERTS.entrySet()) {
            String table = entry.getKey();
            Integer triggers = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_trigger WHERE tgname = 'ffcrm_sync_custom_fields' "
                    + "AND tgrelid = ?::regclass AND NOT tgisinternal",
                Integer.class, table);
            assertThat(triggers).as("trigger on %s", table).isEqualTo(1);

            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN cf_all_models text");
            jdbcTemplate.update(entry.getValue(), "value-" + table);
            Long id = jdbcTemplate.queryForObject(
                "SELECT max(id) FROM " + table + " WHERE cf_all_models = ?", Long.class, "value-" + table);

            assertThat(jdbcTemplate.queryForObject(
                "SELECT custom_fields ->> 'cf_all_models' FROM " + table + " WHERE id = ?", String.class, id))
                .as("JSONB mirror on %s after insert", table)
                .isEqualTo("value-" + table);

            jdbcTemplate.update("UPDATE " + table + " SET cf_all_models = NULL WHERE id = ?", id);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT jsonb_exists(custom_fields, 'cf_all_models') FROM " + table + " WHERE id = ?",
                Boolean.class, id))
                .as("JSONB key removed on %s after NULL update", table)
                .isFalse();
            jdbcTemplate.update("DELETE FROM " + table + " WHERE id = ?", id);
        }
    }

    @Test
    void writeServiceOnTaskBumpsUpdatedAtOnlyWhenSomethingChanges() {
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (990901, 'Task', 'task fields', 1, now(), now())");
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "disabled, required, created_at, updated_at) VALUES "
                + "(990901, 'CustomField', 990901, 1, 'cf_task_note', 'Note', 'string', false, false, now(), now())");
        Long id = jdbcTemplate.queryForObject(
            "INSERT INTO tasks (name, created_at, updated_at) VALUES "
                + "('ab271-task', '2020-01-01 00:00:00', '2020-01-01 00:00:00') RETURNING id",
            Long.class);
        registry.invalidate();
        Task task = entityManager.find(Task.class, id);
        Timestamp before = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM tasks WHERE id = ?", Timestamp.class, id);

        assertThat(writeService.write(task, Map.of("cf_task_note", "first")))
            .containsEntry("cf_task_note", "first");
        Timestamp afterWrite = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM tasks WHERE id = ?", Timestamp.class, id);
        assertThat(afterWrite).isAfter(before);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT custom_fields ->> 'cf_task_note' FROM tasks WHERE id = ?", String.class, id))
            .isEqualTo("first");

        assertThat(writeService.write(task, Map.of("cf_task_note", "first")))
            .containsEntry("cf_task_note", "first");
        Timestamp afterNoop = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM tasks WHERE id = ?", Timestamp.class, id);
        assertThat(afterNoop).as("unchanged value must not touch updated_at").isEqualTo(afterWrite);
    }

    @Test
    void tagScopedFieldGroupsApplyOnlyToRecordsCarryingTheTagLikeRailsWithTags() {
        jdbcTemplate.update("INSERT INTO tags (id, name) VALUES (990901, 'ab271-vip')");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", tag_id, created_at, updated_at) "
                + "VALUES (990901, 'Contact', 'untagged group', 1, NULL, now(), now()), "
                + "(990902, 'Contact', 'vip group', 2, 990901, now(), now())");
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "disabled, required, created_at, updated_at) VALUES "
                + "(990902, 'CustomField', 990901, 1, 'cf_plain', 'Plain', 'string', false, false, now(), now()), "
                + "(990903, 'CustomField', 990902, 1, 'cf_vip_level', 'VIP level', 'string', "
                + "false, true, now(), now())");
        Long plainId = jdbcTemplate.queryForObject(
            "INSERT INTO contacts (first_name, last_name) VALUES ('ab271', 'plain') RETURNING id", Long.class);
        Long vipId = jdbcTemplate.queryForObject(
            "INSERT INTO contacts (first_name, last_name) VALUES ('ab271', 'vip') RETURNING id", Long.class);
        jdbcTemplate.update(
            "INSERT INTO taggings (tag_id, taggable_id, taggable_type, context, created_at) "
                + "VALUES (990901, ?, 'Contact', 'tags', now())", vipId);
        registry.invalidate();

        // Both definitions are registered for Contact, but only the untagged group applies to an untagged record.
        assertThat(registry.definitionsFor(RailsModelType.CONTACT))
            .extracting(CustomFieldDefinition::name)
            .contains("cf_plain", "cf_vip_level");

        Contact plain = entityManager.find(Contact.class, plainId);
        assertThat(writeService.write(plain, Map.of("cf_plain", "ok"))).containsEntry("cf_plain", "ok");
        assertThatThrownBy(() -> writeService.write(plain, Map.of("cf_vip_level", "gold")))
            .isInstanceOfSatisfying(CustomFieldValidationException.class,
                exception -> assertThat(exception.errors()).containsOnlyKeys("cf_vip_level"));

        Contact vip = entityManager.find(Contact.class, vipId);
        assertThatThrownBy(() -> writeService.write(vip, Map.of("cf_plain", "ok")))
            .as("required field from the tagged group is enforced on a tagged record")
            .isInstanceOfSatisfying(CustomFieldValidationException.class,
                exception -> assertThat(exception.errors()).containsOnlyKeys("cf_vip_level"));
        assertThat(writeService.write(vip, Map.of("cf_plain", "ok", "cf_vip_level", "gold")))
            .containsEntry("cf_plain", "ok")
            .containsEntry("cf_vip_level", "gold");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT custom_fields ->> 'cf_vip_level' FROM contacts WHERE id = ?", String.class, vipId))
            .isEqualTo("gold");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM contacts WHERE id = ? AND jsonb_exists(custom_fields, 'cf_vip_level')",
            Integer.class, plainId)).isZero();
        assertThat(List.of(plainId, vipId)).doesNotContainNull();
    }
}
