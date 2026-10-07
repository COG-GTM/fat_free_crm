package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@TestPropertySource(properties = "ffcrm.custom-fields.registry-ttl=50ms")
class CustomFieldRegistryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private CustomFieldRegistry registry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void invalidateRegistry() {
        registry.invalidate();
    }

    @Test
    void filtersRailsCoreAndUnknownTypesOrdersDefinitionsAndReloadsPhysicalColumns() {
        assertThat(registry.definitionsFor(RailsModelType.ACCOUNT)).isEmpty();
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_registry_runtime text");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, label, \"position\", created_at, updated_at) "
                + "VALUES (990281, 'Account', 'late', 'late', 2, now(), now()), "
                + "(990282, 'Account', 'early', 'early', 1, now(), now()), "
                + "(990283, 'NotARailsModel', 'unknown', 'unknown', 0, now(), now())");
        insertField(990281, 990281, "cf_registry_late", "CustomField");
        insertField(990282, 990282, "cf_registry_early", "CustomField");
        insertField(990283, 990283, "cf_registry_unknown", "CustomField");
        insertField(990284, 990282, "cf_registry_core", "CoreField");
        insertField(990285, 990282, "cf_registry_null", null);

        registry.invalidate();

        assertThat(registry.definitionsFor(RailsModelType.ACCOUNT))
            .extracting(CustomFieldDefinition::name)
            .containsExactly("cf_registry_early", "cf_registry_late");
        assertThat(registry.physicalColumns(RailsModelType.ACCOUNT)).contains("cf_registry_runtime");
    }

    @Test
    void detectsFieldEditsThatKeepTheOldUpdatedAtAfterTtl() throws InterruptedException {
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, label, \"position\", created_at, updated_at) "
                + "VALUES (990290, 'Account', 'TTL', 'TTL', 1, now(), now())");
        insertField(990291, 990290, "cf_ttl_a", "CustomField");
        insertField(990292, 990290, "cf_ttl_b", "CustomField");
        Timestamp earlier = Timestamp.valueOf("2024-01-01 00:00:00");
        Timestamp later = Timestamp.valueOf("2024-02-01 00:00:00");
        jdbcTemplate.update("UPDATE fields SET updated_at = ? WHERE id = 990291", earlier);
        jdbcTemplate.update("UPDATE fields SET updated_at = ? WHERE id = 990292", later);

        assertThat(registry.find(RailsModelType.ACCOUNT, "cf_ttl_a"))
            .get()
            .extracting(CustomFieldDefinition::label, CustomFieldDefinition::as)
            .containsExactly("cf_ttl_a", "string");
        jdbcTemplate.update(
            "UPDATE fields SET label = ?, \"as\" = ?, updated_at = ? WHERE id = 990291",
            "Changed label", "integer", earlier);

        Thread.sleep(100);

        assertThat(registry.find(RailsModelType.ACCOUNT, "cf_ttl_a"))
            .get()
            .extracting(CustomFieldDefinition::label, CustomFieldDefinition::as)
            .containsExactly("Changed label", "integer");
    }

    @Test
    void detectsNewPhysicalColumnsAfterTtl() throws InterruptedException {
        assertThat(registry.physicalColumns(RailsModelType.ACCOUNT)).doesNotContain("cf_ttl_probe");
        try {
            jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_ttl_probe varchar(255)");

            Thread.sleep(100);

            assertThat(registry.physicalColumns(RailsModelType.ACCOUNT)).contains("cf_ttl_probe");
        } finally {
            jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_ttl_probe");
            registry.invalidate();
        }
    }

    private void insertField(long id, long groupId, String name, String type) {
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "created_at, updated_at) VALUES (?, ?, ?, 1, ?, ?, 'string', now(), now())",
            id, type, groupId, name, name);
    }
}
