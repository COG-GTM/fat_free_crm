package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.support.HasCustomFields;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every Rails model with {@code has_fields} now maps the {@code custom_fields} JSONB column as a
 * read-only attribute. Pins, per entity, that JPA reads what the trigger wrote, that a missing
 * document reads as an empty map, and that an ordinary JPA update never clobbers the document
 * or the physical {@code cf_*} column.
 */
@Transactional
class HasCustomFieldsEntitiesIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COLUMN = "cf_entity_probe";
    private static final String MARKER = "ab271-entity-probe";

    private static final Map<RailsModelType, String> NAME_COLUMN = Map.of(
        RailsModelType.ACCOUNT, "name",
        RailsModelType.CAMPAIGN, "name",
        RailsModelType.CONTACT, "first_name",
        RailsModelType.LEAD, "first_name",
        RailsModelType.OPPORTUNITY, "name",
        RailsModelType.TASK, "name");

    private static final Map<RailsModelType, Consumer<Object>> RENAME = Map.of(
        RailsModelType.ACCOUNT, entity -> ((Account) entity).setName(MARKER + "-renamed"),
        RailsModelType.CAMPAIGN, entity -> ((Campaign) entity).setName(MARKER + "-renamed"),
        RailsModelType.CONTACT, entity -> ((Contact) entity).setFirstName(MARKER + "-renamed"),
        RailsModelType.LEAD, entity -> ((Lead) entity).setFirstName(MARKER + "-renamed"),
        RailsModelType.OPPORTUNITY, entity -> ((Opportunity) entity).setName(MARKER + "-renamed"),
        RailsModelType.TASK, entity -> ((Task) entity).setName(MARKER + "-renamed"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void cleanUp() {
        for (RailsModelType type : NAME_COLUMN.keySet()) {
            String table = CustomFieldRegistry.tableName(type);
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP COLUMN IF EXISTS " + COLUMN);
            jdbcTemplate.update(
                "DELETE FROM " + table + " WHERE " + NAME_COLUMN.get(type) + " LIKE '" + MARKER + "%'");
        }
    }

    @Test
    void everyCustomFieldEntityReadsTheTriggerMaintainedDocument() {
        for (RailsModelType type : NAME_COLUMN.keySet()) {
            String table = CustomFieldRegistry.tableName(type);
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + COLUMN + " text");
            long id = insert(type, "probe-" + table);

            HasCustomFields entity = (HasCustomFields) entityManager.find(type.entityClass(), id);
            assertThat(entity).as(table).isNotNull();
            assertThat(entity.getCustomFields()).as(table)
                .containsExactlyInAnyOrderEntriesOf(Map.of(COLUMN, "probe-" + table));
        }
    }

    @Test
    void everyCustomFieldEntityReadsAMissingDocumentAsAnEmptyUnmodifiableMap() {
        for (RailsModelType type : NAME_COLUMN.keySet()) {
            String table = CustomFieldRegistry.tableName(type);
            long id = jdbcTemplate.queryForObject(
                "INSERT INTO " + table + " (" + NAME_COLUMN.get(type) + ") VALUES (?) RETURNING id",
                Long.class, MARKER);
            jdbcTemplate.update("UPDATE " + table + " SET custom_fields = NULL WHERE id = ?", id);

            HasCustomFields entity = (HasCustomFields) entityManager.find(type.entityClass(), id);
            Map<String, Object> values = entity.getCustomFields();
            assertThat(values).as(table).isNotNull().isEmpty();
            assertThatThrownBy(() -> values.put("cf_x", "y")).as(table)
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void jpaUpdatesOfOrdinaryAttributesPreserveTheDocumentAndThePhysicalColumn() {
        Map<RailsModelType, Long> ids = new LinkedHashMap<>();
        for (RailsModelType type : NAME_COLUMN.keySet()) {
            String table = CustomFieldRegistry.tableName(type);
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + COLUMN + " text");
            ids.put(type, insert(type, "keep-" + table));
        }

        for (Map.Entry<RailsModelType, Long> entry : ids.entrySet()) {
            Object entity = entityManager.find(entry.getKey().entityClass(), entry.getValue());
            RENAME.get(entry.getKey()).accept(entity);
        }
        entityManager.flush();
        entityManager.clear();

        for (Map.Entry<RailsModelType, Long> entry : ids.entrySet()) {
            RailsModelType type = entry.getKey();
            String table = CustomFieldRegistry.tableName(type);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT " + NAME_COLUMN.get(type) + " AS label, " + COLUMN + " AS physical, "
                    + "custom_fields ->> '" + COLUMN + "' AS stored FROM " + table + " WHERE id = ?",
                entry.getValue());
            assertThat(rows).as(table).singleElement().satisfies(row -> {
                assertThat(row.get("label")).isEqualTo(MARKER + "-renamed");
                assertThat(row.get("physical")).isEqualTo("keep-" + table);
                assertThat(row.get("stored")).isEqualTo("keep-" + table);
            });
            HasCustomFields reloaded = (HasCustomFields) entityManager.find(type.entityClass(), entry.getValue());
            assertThat(reloaded.getCustomFields()).as(table).containsEntry(COLUMN, "keep-" + table);
        }
    }

    private long insert(RailsModelType type, String value) {
        String table = CustomFieldRegistry.tableName(type);
        return jdbcTemplate.queryForObject(
            "INSERT INTO " + table + " (" + NAME_COLUMN.get(type) + ", " + COLUMN + ") VALUES (?, ?) RETURNING id",
            Long.class, MARKER, value);
    }
}
