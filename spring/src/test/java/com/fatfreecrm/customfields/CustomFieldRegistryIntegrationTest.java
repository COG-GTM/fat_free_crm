package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
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

    private void insertField(long id, long groupId, String name, String type) {
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "created_at, updated_at) VALUES (?, ?, ?, 1, ?, ?, 'string', now(), now())",
            id, type, groupId, name, name);
    }
}
