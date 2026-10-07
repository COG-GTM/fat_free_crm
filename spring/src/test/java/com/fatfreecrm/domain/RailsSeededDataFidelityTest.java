package com.fatfreecrm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.support.AbstractRailsSeededIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data-fidelity contract for the JPA model: every application table is mapped by exactly one entity, every column
 * of every table is mapped, every Rails-written row reads back attribute-for-attribute, and re-inserting the
 * entity graph through Hibernate reproduces the Rails rows byte-for-byte (only {@code id} differs).
 */
@Transactional
class RailsSeededDataFidelityTest extends AbstractRailsSeededIntegrationTest {

    /** Rails/infrastructure tables deliberately outside the JPA model (see docs/migration/data-model.md). */
    private static final Set<String> UNMAPPED_TABLES = Set.of(
        "schema_migrations", "ar_internal_metadata", "flyway_schema_history", "sessions", "action_text_rich_texts",
        "active_storage_attachments", "active_storage_blobs", "active_storage_variant_records",
        "solid_queue_blocked_executions", "solid_queue_claimed_executions", "solid_queue_failed_executions",
        "solid_queue_jobs", "solid_queue_pauses", "solid_queue_processes", "solid_queue_ready_executions",
        "solid_queue_recurring_executions", "solid_queue_recurring_tasks", "solid_queue_scheduled_executions",
        "solid_queue_semaphores"
    );

    /** Pure join table without a primary key; mapped as {@code User.groups} rather than as an entity. */
    private static final String GROUPS_USERS = "groups_users";

    private static final List<Class<?>> ENTITY_CLASSES = List.of(
        Account.class, AccountContact.class, AccountOpportunity.class, Activity.class, Address.class, Avatar.class,
        Campaign.class, Comment.class, Contact.class, ContactOpportunity.class, Email.class, Field.class,
        FieldGroup.class, Group.class, Lead.class, Opportunity.class, Permission.class, Preference.class,
        ResearchTool.class, SavedList.class, Setting.class, Tag.class, Tagging.class, Task.class, User.class,
        Version.class
    );

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    static Stream<Class<?>> entityClasses() {
        return ENTITY_CLASSES.stream();
    }

    @Test
    void everyApplicationTableIsMappedByExactlyOneEntity() {
        var mappedTables = new TreeMap<String, String>();
        for (var entity : entityManager.getMetamodel().getEntities()) {
            var mapping = new EntityColumnMapping(entity.getJavaType());
            var previous = mappedTables.put(mapping.table(), entity.getName());
            assertThat(previous).as("%s is mapped twice", mapping.table()).isNull();
        }
        var expected = new TreeSet<>(databaseTables());
        expected.removeAll(UNMAPPED_TABLES);
        expected.remove(GROUPS_USERS);
        assertThat(mappedTables.keySet()).containsExactlyElementsOf(expected);
        List<Class<?>> entityClasses = entityManager.getMetamodel().getEntities().stream()
            .<Class<?>>map(entity -> entity.getJavaType()).toList();
        assertThat(entityClasses).containsExactlyInAnyOrderElementsOf(ENTITY_CLASSES);
    }

    @ParameterizedTest
    @MethodSource("entityClasses")
    void everyColumnIsMapped(Class<?> entityClass) {
        var mapping = new EntityColumnMapping(entityClass);
        var mappedColumns = mapping.columns().stream().map(EntityColumnMapping.MappedColumn::column).toList();
        assertThat(mappedColumns).doesNotHaveDuplicates();
        assertThat(mappedColumns).containsExactlyInAnyOrderElementsOf(databaseColumns(mapping.table()));
    }

    @ParameterizedTest
    @MethodSource("entityClasses")
    void everyRailsRowReadsBackUnchangedAndReinsertsByteForByte(Class<?> entityClass) {
        var mapping = new EntityColumnMapping(entityClass);
        var softDeletable = SoftDeletable.class.isAssignableFrom(entityClass);
        var liveRows = rows(mapping.table(), softDeletable ? "WHERE deleted_at IS NULL" : "");
        var allRows = rows(mapping.table(), "");
        assertThat(allRows).as("fixture has rows for %s", mapping.table()).isNotEmpty();
        if (softDeletable) {
            assertThat(allRows.size()).as("fixture has soft-deleted %s rows", mapping.table())
                .isGreaterThan(liveRows.size());
        }

        var entities = loadAll(entityClass);
        assertThat(entities).as("%s loads exactly the live rows", entityClass.getSimpleName())
            .hasSameSizeAs(liveRows);
        for (int i = 0; i < entities.size(); i++) {
            assertEntityMatchesRow(mapping, entities.get(i), liveRows.get(i));
        }

        jdbcTemplate.update("DELETE FROM " + mapping.table());
        var copies = new ArrayList<Object>();
        for (Object entity : entities) {
            var copy = mapping.copyWithoutId(entity);
            entityManager.persist(copy);
            copies.add(copy);
        }
        entityManager.flush();

        var reinserted = rows(mapping.table(), "");
        assertThat(reinserted).hasSameSizeAs(liveRows);
        for (int i = 0; i < liveRows.size(); i++) {
            var expected = new LinkedHashMap<>(liveRows.get(i));
            var actual = new LinkedHashMap<>(reinserted.get(i));
            assertThat(actual.remove("id")).isEqualTo(((BaseEntity) copies.get(i)).getId());
            expected.remove("id");
            assertThat(normalize(actual)).as("%s row %d re-inserted by Hibernate", mapping.table(), i)
                .containsExactlyEntriesOf(normalize(expected));
        }
    }

    @Test
    void groupMembershipMirrorsTheJoinTable() {
        var expected = jdbcTemplate.queryForList(
            "SELECT user_id, group_id FROM groups_users ORDER BY user_id, group_id"
        );
        assertThat(expected).isNotEmpty();
        var actual = new ArrayList<Map<String, Object>>();
        for (User user : loadAll(User.class)) {
            user.getGroups().stream().map(Group::getId).sorted().forEach(groupId ->
                actual.add(Map.of("user_id", user.getId(), "group_id", groupId)));
        }
        assertThat(actual.stream().map(this::normalize).toList())
            .containsExactlyElementsOf(expected.stream().map(this::normalize).toList());
    }

    @Test
    void serializedColumnsKeepTheirRailsRepresentation() {
        var acme = entityManager.createQuery("select a from Account a where a.name = 'Acme Corp'", Account.class)
            .getSingleResult();
        var alice = entityManager.createQuery("select u from User u where u.username = 'alice'", User.class)
            .getSingleResult();
        var bob = entityManager.createQuery("select u from User u where u.username = 'bob'", User.class)
            .getSingleResult();
        // Rails appends commenters and @mentions without de-duplicating; the duplicates must survive intact.
        assertThat(acme.getSubscribedUsers()).containsExactly(alice.getId(), bob.getId(), bob.getId(), alice.getId());
        assertThat(rawValue("accounts", acme.getId(), "subscribed_users"))
            .isEqualTo(new RailsYamlIdListConverter().convertToDatabaseColumn(acme.getSubscribedUsers()));

        var globex = entityManager.createQuery("select a from Account a where a.name = 'Globex'", Account.class)
            .getSingleResult();
        assertThat(globex.getSubscribedUsers()).isEmpty();
        assertThat(rawValue("accounts", globex.getId(), "subscribed_users")).as("Rails stores [] as NULL").isNull();

        var companyName = entityManager
            .createQuery("select s from Setting s where s.name = 'company_name'", Setting.class).getSingleResult();
        assertThat(companyName.getValue()).isEqualTo("--- Fixture Co\n");
        var locale = entityManager
            .createQuery("select p from Preference p where p.name = 'locale'", Preference.class).getSingleResult();
        assertThat(locale.getValue()).isEqualTo("ImVuLVVTIg==\n");
    }

    private <T> List<T> loadAll(Class<T> entityClass) {
        return entityManager
            .createQuery("select e from " + entityClass.getSimpleName() + " e order by e.id", entityClass)
            .getResultList();
    }

    private void assertEntityMatchesRow(EntityColumnMapping mapping, Object entity, Map<String, Object> row) {
        var expected = new TreeMap<String, Object>();
        var actual = new TreeMap<String, Object>();
        for (var column : mapping.columns()) {
            expected.put(column.column(), normalize(row.get(column.column())));
            actual.put(column.column(), normalize(column.databaseValue(entity)));
        }
        assertThat(actual).as("%s id=%s", mapping.table(), row.get("id")).containsExactlyEntriesOf(expected);
    }

    private List<Map<String, Object>> rows(String table, String where) {
        return jdbcTemplate.queryForList("SELECT * FROM " + table + " " + where + " ORDER BY id");
    }

    private Object rawValue(String table, Long id, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", Object.class, id);
    }

    private List<String> databaseTables() {
        return jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_type = 'BASE TABLE' ORDER BY table_name",
            String.class
        );
    }

    private List<String> databaseColumns(String table) {
        return jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? "
                + "ORDER BY ordinal_position",
            String.class,
            table
        );
    }

    private Map<String, Object> normalize(Map<String, Object> row) {
        var normalized = new TreeMap<String, Object>(Comparator.naturalOrder());
        row.forEach((column, value) -> normalized.put(column, normalize(value)));
        return normalized;
    }

    private Object normalize(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros();
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Long) {
            return ((Number) value).longValue();
        }
        return value;
    }
}
