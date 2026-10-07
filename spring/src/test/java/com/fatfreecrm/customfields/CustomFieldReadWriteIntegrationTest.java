package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class CustomFieldReadWriteIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomFieldRegistry registry;

    @Autowired
    private CustomFieldReadService readService;

    @Autowired
    private CustomFieldWriteService writeService;

    @Autowired
    private CustomFieldConsistencyCheck consistencyCheck;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM accounts WHERE name LIKE 'ab271-%'");
        jdbcTemplate.update("DELETE FROM fields WHERE id IN (990301, 990302, 990303)");
        jdbcTemplate.update("DELETE FROM field_groups WHERE id = 990301");
        jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_read_write");
        jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_read_boxes");
        registry.invalidate();
    }

    @Test
    void readsWritesValidatesAndComparesTheDualReadWithJsonb() {
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_read_write text");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (990301, 'Account', 'read/write test', 1, now(), now())");
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "disabled, required, created_at, updated_at) VALUES "
                + "(990301, 'CustomField', 990301, 1, 'cf_read_write', 'Read/write', 'string', "
                + "false, false, now(), now()), "
                + "(990302, 'CustomField', 990301, 2, 'cf_json_only', 'JSON only', 'string', "
                + "false, false, now(), now())");
        Long id = jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, cf_read_write) VALUES ('ab271-read-write', 'initial') RETURNING id",
            Long.class);
        registry.invalidate();
        Account account = entityManager.find(Account.class, id);

        assertThat(readService.valuesFor(account)).containsEntry("cf_read_write", "initial");
        assertThat(readService.railsJsonValues(account)).containsEntry("cf_read_write", "initial");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("cf_json_only", "json-only");
        input.put("cf_read_write", "written");
        assertThat(writeService.write(account, input))
            .containsEntry("cf_json_only", "json-only")
            .containsEntry("cf_read_write", "written");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT cf_read_write FROM accounts WHERE id = ?", String.class, id)).isEqualTo("written");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT custom_fields ->> 'cf_json_only' FROM accounts WHERE id = ?", String.class, id))
            .isEqualTo("json-only");
        assertThat(writeService.write(account, java.util.Collections.singletonMap("cf_json_only", null)))
            .doesNotContainKey("cf_json_only");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT jsonb_exists(custom_fields, 'cf_json_only') FROM accounts WHERE id = ?",
            Boolean.class,
            id
        )).isFalse();
        assertThat(consistencyCheck.check(RailsModelType.ACCOUNT).ok()).isTrue();

        entityManager.flush();
        jdbcTemplate.execute("ALTER TABLE accounts DISABLE TRIGGER ffcrm_sync_custom_fields");
        jdbcTemplate.update(
            "UPDATE accounts SET custom_fields = '{\"cf_read_write\":\"stale\"}'::jsonb WHERE id = ?", id);
        jdbcTemplate.update(
            "INSERT INTO accounts (name, custom_fields) VALUES "
                + "('ab271-null-column-drift', '{\"cf_read_write\":\"stale-null\"}'::jsonb)");
        jdbcTemplate.execute("ALTER TABLE accounts ENABLE TRIGGER ffcrm_sync_custom_fields");
        entityManager.clear();

        var report = consistencyCheck.check(RailsModelType.ACCOUNT).tables().get("accounts");
        Long nullColumnId = jdbcTemplate.queryForObject(
            "SELECT id FROM accounts WHERE name = 'ab271-null-column-drift'", Long.class);
        assertThat(report.driftedRows()).isEqualTo(2);
        assertThat(report.driftedRowIds()).contains(id, nullColumnId);
        assertThat(report.keyDriftCounts()).containsEntry("cf_read_write", 2L);

        assertThatThrownBy(() -> writeService.write(
            entityManager.find(Account.class, id), Map.of("cf_unknown", "value")))
            .isInstanceOfSatisfying(CustomFieldValidationException.class, exception ->
                assertThat(exception.errors()).containsKey("cf_unknown"));
    }

    @Test
    void malformedCheckboxYamlIsReturnedByJsonbReadsAndConsistencyCheck() {
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_read_boxes text");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (990301, 'Account', 'read malformed YAML test', 1, now(), now())");
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "disabled, required, created_at, updated_at) VALUES "
                + "(990303, 'CustomField', 990301, 1, 'cf_read_boxes', 'Read boxes', 'check_boxes', "
                + "false, false, now(), now())");
        String raw = "---\n- \"\\uZZZZ\"\n";
        Long id = jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, cf_read_boxes) VALUES ('ab271-read-malformed-yaml', ?) RETURNING id",
            Long.class,
            raw);
        registry.invalidate();
        entityManager.clear();
        Account account = entityManager.find(Account.class, id);

        assertThat(readService.valuesFor(account)).containsEntry("cf_read_boxes", raw);
        assertThat(readService.railsJsonValues(account)).containsEntry("cf_read_boxes", raw);
        assertThat(consistencyCheck.check(RailsModelType.ACCOUNT).ok()).isTrue();
    }
}
