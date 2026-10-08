package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The custom-fields stack is installed on all six Rails models that include {@code FatFreeCRM::Fields}
 * ({@code Account, Campaign, Contact, Lead, Opportunity, Task}). Existing coverage only exercises
 * {@code accounts}; this pins the per-table trigger wiring, a full Contact read/write round trip, and
 * that ordinary JPA updates never clobber the trigger-maintained {@code custom_fields} document.
 */
@Transactional
class CustomFieldsMultiModelIntegrationTest extends AbstractPostgresIntegrationTest {

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

    @Autowired
    private CheckBoxesYamlCodec yamlCodec;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ContactRepository contactRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM contacts WHERE first_name = 'AB271'");
        jdbcTemplate.update("DELETE FROM accounts WHERE name LIKE 'ab271-%'");
        jdbcTemplate.update("DELETE FROM fields WHERE id IN (990601, 990602, 990603)");
        jdbcTemplate.update("DELETE FROM field_groups WHERE id = 990601");
        jdbcTemplate.execute("ALTER TABLE contacts DROP COLUMN IF EXISTS cf_ab271_contact_note");
        jdbcTemplate.execute("ALTER TABLE contacts DROP COLUMN IF EXISTS cf_ab271_contact_boxes");
        registry.invalidate();
    }

    @Test
    void syncTriggerAndJsonbColumnAreInstalledOnEveryCustomFieldTable() {
        Map<String, String> triggerKlassByTable = new LinkedHashMap<>();
        jdbcTemplate.query(
            "SELECT c.relname, replace(encode(t.tgargs, 'escape'), '\\000', '') AS klass "
                + "FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                + "WHERE t.tgname = 'ffcrm_sync_custom_fields' AND NOT t.tgisinternal ORDER BY c.relname",
            rs -> {
                triggerKlassByTable.put(rs.getString("relname"), rs.getString("klass"));
            });
        assertThat(triggerKlassByTable).containsExactlyInAnyOrderEntriesOf(Map.of(
            "accounts", "Account",
            "campaigns", "Campaign",
            "contacts", "Contact",
            "leads", "Lead",
            "opportunities", "Opportunity",
            "tasks", "Task"));

        List<String> jsonbTables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND column_name = 'custom_fields' AND data_type = 'jsonb' "
                + "ORDER BY table_name",
            String.class);
        assertThat(jsonbTables).containsExactly(
            "accounts", "campaigns", "contacts", "leads", "opportunities", "tasks");
    }

    @Test
    void contactCustomFieldsRoundTripThroughTriggerRegistryReadAndWriteServices() {
        jdbcTemplate.execute("ALTER TABLE contacts ADD COLUMN cf_ab271_contact_note text");
        jdbcTemplate.execute("ALTER TABLE contacts ADD COLUMN cf_ab271_contact_boxes text");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (990601, 'Contact', 'ab271 contact', 1, now(), now())");
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", collection, "
                + "disabled, required, created_at, updated_at) VALUES "
                + "(990601, 'CustomField', 990601, 1, 'cf_ab271_contact_note', 'Note', 'string', NULL, "
                + "false, false, now(), now()), "
                + "(990602, 'CustomField', 990601, 2, 'cf_ab271_contact_boxes', 'Boxes', 'check_boxes', "
                + "E'---\\n- a\\n- b\\n- c\\n', false, false, now(), now()), "
                + "(990603, 'CustomField', 990601, 3, 'cf_ab271_contact_json', 'JSON only', 'string', NULL, "
                + "false, false, now(), now())");
        registry.invalidate();

        Long id = jdbcTemplate.queryForObject(
            "INSERT INTO contacts (first_name, last_name, cf_ab271_contact_note, cf_ab271_contact_boxes, "
                + "created_at, updated_at) VALUES ('AB271', 'Contact', 'hello', E'---\\n- a\\n- b\\n', "
                + "'2025-01-01 00:00:00', '2025-01-01 00:00:00') RETURNING id",
            Long.class);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT custom_fields ->> 'cf_ab271_contact_note' FROM contacts WHERE id = ?", String.class, id))
            .as("the contacts trigger is parameterised with klass 'Contact' and syncs its own fields")
            .isEqualTo("hello");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT custom_fields -> 'cf_ab271_contact_boxes' FROM contacts WHERE id = ?", String.class, id))
            .isEqualTo("[\"a\", \"b\"]");

        assertThat(registry.definitionsFor(RailsModelType.CONTACT)).extracting(CustomFieldDefinition::name)
            .contains("cf_ab271_contact_note", "cf_ab271_contact_boxes", "cf_ab271_contact_json");
        assertThat(registry.definitionsFor(RailsModelType.ACCOUNT)).extracting(CustomFieldDefinition::name)
            .as("Contact fields never leak into the Account registry slice")
            .doesNotContain("cf_ab271_contact_note", "cf_ab271_contact_boxes", "cf_ab271_contact_json");
        assertThat(registry.physicalColumns(RailsModelType.CONTACT))
            .contains("cf_ab271_contact_note", "cf_ab271_contact_boxes");
        assertThat(registry.physicalColumns(RailsModelType.ACCOUNT))
            .doesNotContain("cf_ab271_contact_note", "cf_ab271_contact_boxes");
        assertThat(registry.physicalColumnTypes(RailsModelType.CONTACT))
            .containsEntry("cf_ab271_contact_note", "text")
            .containsEntry("cf_ab271_contact_boxes", "text");

        entityManager.clear();
        Contact contact = entityManager.find(Contact.class, id);
        assertThat(readService.valuesFor(contact))
            .containsEntry("cf_ab271_contact_note", "hello")
            .containsEntry("cf_ab271_contact_boxes", List.of("a", "b"))
            .doesNotContainKey("cf_ab271_contact_json");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("cf_ab271_contact_note", "updated");
        input.put("cf_ab271_contact_boxes", List.of("c"));
        input.put("cf_ab271_contact_json", "json-only");
        Map<String, Object> written = writeService.write(contact, input);

        assertThat(written)
            .containsEntry("cf_ab271_contact_note", "updated")
            .containsEntry("cf_ab271_contact_boxes", List.of("c"))
            .containsEntry("cf_ab271_contact_json", "json-only");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT cf_ab271_contact_note FROM contacts WHERE id = ?", String.class, id)).isEqualTo("updated");
        assertThat(yamlCodec.decode(jdbcTemplate.queryForObject(
            "SELECT cf_ab271_contact_boxes FROM contacts WHERE id = ?", String.class, id)))
            .as("check_boxes are persisted as the Rails YAML array serialization")
            .isEqualTo(List.of("c"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT custom_fields ->> 'cf_ab271_contact_json' FROM contacts WHERE id = ?", String.class, id))
            .isEqualTo("json-only");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT updated_at FROM contacts WHERE id = ?", Timestamp.class, id))
            .as("a real custom-field write touches updated_at like Rails save does")
            .isAfter(Timestamp.valueOf("2025-01-01 00:00:00"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT created_at FROM contacts WHERE id = ?", Timestamp.class, id))
            .isEqualTo(Timestamp.valueOf("2025-01-01 00:00:00"));

        entityManager.clear();
        assertThat(readService.valuesFor(entityManager.find(Contact.class, id)))
            .containsEntry("cf_ab271_contact_note", "updated")
            .containsEntry("cf_ab271_contact_boxes", List.of("c"))
            .containsEntry("cf_ab271_contact_json", "json-only");
        assertThat(consistencyCheck.check(RailsModelType.CONTACT).ok()).isTrue();
    }

    @Test
    void jpaUpdatesOfRegularColumnsPreserveTheJsonbCustomFieldsDocument() {
        Long accountId = jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, custom_fields) "
                + "VALUES ('ab271-jpa-preserve', '{\"cf_ab271_java_only\":\"kept\"}'::jsonb) RETURNING id",
            Long.class);
        Long contactId = jdbcTemplate.queryForObject(
            "INSERT INTO contacts (first_name, last_name, custom_fields) "
                + "VALUES ('AB271', 'Preserve', '{\"cf_ab271_java_only\":\"kept\"}'::jsonb) RETURNING id",
            Long.class);
        entityManager.clear();

        Account account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getCustomFields()).containsEntry("cf_ab271_java_only", "kept");
        account.setName("ab271-jpa-preserve-renamed");
        accountRepository.saveAndFlush(account);

        Contact contact = contactRepository.findById(contactId).orElseThrow();
        assertThat(contact.getCustomFields()).containsEntry("cf_ab271_java_only", "kept");
        contact.setLastName("Renamed");
        contactRepository.saveAndFlush(contact);
        entityManager.clear();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT custom_fields ->> 'cf_ab271_java_only' FROM accounts WHERE id = ?", String.class, accountId))
            .isEqualTo("kept");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT name FROM accounts WHERE id = ?", String.class, accountId))
            .isEqualTo("ab271-jpa-preserve-renamed");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT custom_fields ->> 'cf_ab271_java_only' FROM contacts WHERE id = ?", String.class, contactId))
            .isEqualTo("kept");
        assertThat(entityManager.find(Contact.class, contactId).getCustomFields())
            .containsEntry("cf_ab271_java_only", "kept");
    }
}
