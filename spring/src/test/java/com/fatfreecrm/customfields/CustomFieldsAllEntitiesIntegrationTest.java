package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.support.HasCustomFields;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rails {@code has_fields} is declared on Account, Campaign, Contact, Lead, Opportunity and Task. The
 * other tests exercise accounts only; this one pins the trigger, the JPA mapping and the read/write shim
 * on every table the migration touches.
 */
class CustomFieldsAllEntitiesIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final List<RailsModelType> MODELS = List.of(
        RailsModelType.ACCOUNT, RailsModelType.CAMPAIGN, RailsModelType.CONTACT,
        RailsModelType.LEAD, RailsModelType.OPPORTUNITY, RailsModelType.TASK);

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
        for (RailsModelType model : MODELS) {
            String table = CustomFieldRegistry.tableName(model);
            jdbcTemplate.update("DELETE FROM " + table + " WHERE " + nameColumn(model) + " LIKE 'ab271-all-%'");
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP COLUMN IF EXISTS cf_all_legacy");
        }
        jdbcTemplate.update("DELETE FROM fields WHERE id BETWEEN 990401 AND 990499");
        jdbcTemplate.update("DELETE FROM field_groups WHERE id BETWEEN 990401 AND 990499");
        registry.invalidate();
    }

    @Test
    void everyHasFieldsModelSyncsReadsAndWritesCustomFields() {
        int offset = 0;
        for (RailsModelType model : MODELS) {
            String table = CustomFieldRegistry.tableName(model);
            long groupId = 990401 + offset;
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN cf_all_legacy text");
            jdbcTemplate.update(
                "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                    + "VALUES (?, ?, 'all entities', 1, now(), now())", groupId, model.railsName());
            jdbcTemplate.update(
                "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                    + "disabled, required, created_at, updated_at) VALUES "
                    + "(?, 'CustomField', ?, 1, 'cf_all_legacy', 'Legacy', 'string', false, false, now(), now()), "
                    + "(?, 'CustomField', ?, 2, 'cf_all_json', 'Json', 'integer', false, false, now(), now())",
                groupId * 10 + 1, groupId, groupId * 10 + 2, groupId);
            offset += 1;
        }
        registry.invalidate();

        for (RailsModelType model : MODELS) {
            String table = CustomFieldRegistry.tableName(model);
            Long id = jdbcTemplate.queryForObject(
                "INSERT INTO " + table + " (" + nameColumn(model) + ", cf_all_legacy, updated_at) "
                    + "VALUES ('ab271-all-row', 'from-rails', timestamp '2020-01-01 00:00:00') RETURNING id",
                Long.class);
            Long nullId = jdbcTemplate.queryForObject(
                "INSERT INTO " + table + " (" + nameColumn(model) + ") VALUES ('ab271-all-null') RETURNING id",
                Long.class);
            jdbcTemplate.update("UPDATE " + table + " SET custom_fields = NULL WHERE id = ?", nullId);

            assertThat(jdbcTemplate.queryForObject(
                "SELECT custom_fields ->> 'cf_all_legacy' FROM " + table + " WHERE id = ?", String.class, id))
                .as(table + " trigger").isEqualTo("from-rails");

            HasCustomFields entity = (HasCustomFields) entityManager.find(model.entityClass(), id);
            assertThat(entity.getCustomFields()).as(table + " jsonb mapping")
                .containsEntry("cf_all_legacy", "from-rails");
            assertThatThrownBy(() -> entity.getCustomFields().put("x", 1))
                .as(table + " read-only view").isInstanceOf(UnsupportedOperationException.class);
            assertThat(readService.valuesFor(entity)).as(table + " dual read")
                .containsEntry("cf_all_legacy", "from-rails").doesNotContainKey("cf_all_json");

            HasCustomFields blank = (HasCustomFields) entityManager.find(model.entityClass(), nullId);
            assertThat(blank.getCustomFields()).as(table + " null document").isEmpty();
            assertThat(readService.valuesFor(blank)).as(table + " null dual read").isEmpty();

            Map<String, Object> written = writeService.write(
                entity, Map.of("cf_all_legacy", "from-java", "cf_all_json", "42"));
            assertThat(written).containsEntry("cf_all_legacy", "from-java").containsEntry("cf_all_json", 42);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT cf_all_legacy FROM " + table + " WHERE id = ?", String.class, id))
                .as(table + " legacy column write").isEqualTo("from-java");
            assertThat(jdbcTemplate.queryForObject(
                "SELECT custom_fields FROM " + table + " WHERE id = ?", String.class, id))
                .as(table + " jsonb after write").contains("\"cf_all_legacy\": \"from-java\"")
                .contains("\"cf_all_json\": 42");
            assertThat(jdbcTemplate.queryForObject(
                "SELECT updated_at FROM " + table + " WHERE id = ?", OffsetDateTime.class, id))
                .as(table + " updated_at bump").isAfter(OffsetDateTime.parse("2020-01-02T00:00:00Z"));
            assertThat(entity.getCustomFields()).as(table + " refreshed entity")
                .containsEntry("cf_all_legacy", "from-java");
            assertThat(consistencyCheck.check(model).ok()).as(table + " consistency").isTrue();
        }
    }

    private static String nameColumn(RailsModelType model) {
        return switch (model) {
            case CONTACT, LEAD -> "last_name";
            default -> "name";
        };
    }
}
