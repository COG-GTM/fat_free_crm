package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.config.CustomFieldsProperties;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CustomFieldsBackfillJobIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomFieldRegistry registry;

    @Autowired
    private CustomFieldsBackfillJob backfillJob;

    @Autowired
    private CheckBoxesYamlCodec yamlCodec;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private CustomFieldsProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("ALTER TABLE accounts ENABLE TRIGGER ffcrm_sync_custom_fields");
        jdbcTemplate.update("DELETE FROM accounts WHERE name LIKE 'ab271-backfill-%'");
        jdbcTemplate.update("DELETE FROM fields WHERE id IN (990311, 990312)");
        jdbcTemplate.update("DELETE FROM field_groups WHERE id = 990311");
        jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_backfill_text");
        jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_backfill_check_boxes");
        registry.invalidate();
    }

    @Test
    void backfillsInBatchesIsRestartableAndReportsDriftSeparately() {
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_backfill_text text");
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_backfill_check_boxes text");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (990311, 'Account', 'backfill test', 1, now(), now())");
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "disabled, required, created_at, updated_at) VALUES "
                + "(990311, 'CustomField', 990311, 1, 'cf_backfill_text', 'Backfill', 'string', "
                + "false, false, now(), now()), "
                + "(990312, 'CustomField', 990311, 2, 'cf_backfill_check_boxes', 'Backfill boxes', "
                + "'check_boxes', false, false, now(), now())");
        registry.invalidate();

        jdbcTemplate.execute("ALTER TABLE accounts DISABLE TRIGGER ffcrm_sync_custom_fields");
        jdbcTemplate.execute(
            "INSERT INTO accounts (name, cf_backfill_text, cf_backfill_check_boxes) "
                + "SELECT 'ab271-backfill-' || n, 'value-' || n, "
                + "CASE WHEN n = 1 THEN E'---\\n- |\\n  line one\\n  line two\\n' ELSE NULL END "
                + "FROM generate_series(1, 25000) n");
        jdbcTemplate.execute("ALTER TABLE accounts ENABLE TRIGGER ffcrm_sync_custom_fields");

        CustomFieldsBackfillJob interruptedJob = new CustomFieldsBackfillJob(
            registry, yamlCodec, jdbcTemplate, transactionTemplate, properties, objectMapper,
            (table, batch) -> {
                if (table.equals("accounts") && batch == 2) {
                    throw new IllegalStateException("simulated backfill interruption");
                }
            });
        assertThatThrownBy(interruptedJob::run)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("simulated backfill interruption");

        CustomFieldsBackfillReport report = backfillJob.run();
        assertThat(report.ok()).isTrue();
        assertThat(report.tables().get("accounts").rowsBackfilled()).isEqualTo(7_010);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM accounts WHERE custom_fields ->> 'cf_backfill_text' = 'value-25000'",
            Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT custom_fields -> 'cf_backfill_check_boxes' FROM accounts "
                + "WHERE name = 'ab271-backfill-1'",
            String.class)).isEqualTo("[\"line one\\nline two\\n\"]");
        assertThat(report.tables().get("accounts").markersRemaining()).isZero();

        CustomFieldsBackfillReport restarted = backfillJob.run();
        assertThat(restarted.ok()).isTrue();
        assertThat(restarted.tables().get("accounts").rowsBackfilled()).isZero();

        jdbcTemplate.execute("ALTER TABLE accounts DISABLE TRIGGER ffcrm_sync_custom_fields");
        jdbcTemplate.update(
            "UPDATE accounts SET custom_fields = '{\"cf_backfill_text\":\"wrong\"}'::jsonb "
                + "WHERE name = 'ab271-backfill-25000'");
        jdbcTemplate.execute("ALTER TABLE accounts ENABLE TRIGGER ffcrm_sync_custom_fields");
        CustomFieldsBackfillReport verification = backfillJob.verify();
        assertThat(verification.ok()).isFalse();
        assertThat(verification.tables().get("accounts").drift()).isEqualTo(1);
    }
}
