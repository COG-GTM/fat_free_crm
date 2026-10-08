package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CustomFieldPredicates#handles} is the gate that decides whether a Ransack attribute is
 * routed into the JSONB predicate path. Only lower-case {@code cf_*} names that are registered for
 * a custom-field model may pass; everything else falls through to the regular attribute handling.
 */
@Transactional
class CustomFieldPredicatesGuardIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final long GROUP_ID = 990411;
    private static final long FIELD_ID = 990411;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomFieldRegistry registry;

    @Autowired
    private CustomFieldPredicates predicates;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_guard_probe text");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (?, 'Account', 'guard test', 1, now(), now())", GROUP_ID);
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "collection, disabled, required, created_at, updated_at) "
                + "VALUES (?, 'CustomField', ?, 1, 'cf_guard_probe', 'Probe', 'string', "
                + "'--- []', false, false, now(), now())", FIELD_ID, GROUP_ID);
        registry.invalidate();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_guard_probe");
        jdbcTemplate.update("DELETE FROM fields WHERE id = ?", FIELD_ID);
        jdbcTemplate.update("DELETE FROM field_groups WHERE id = ?", GROUP_ID);
        registry.invalidate();
    }

    @Test
    void handlesOnlyRegisteredLowerCaseCustomFieldNamesOfTheOwningModel() {
        assertThat(predicates.handles(Account.class, "cf_guard_probe")).isTrue();
        assertThat(predicates.handles(Account.class, "cf_unregistered")).isFalse();
        assertThat(predicates.handles(Account.class, "name")).isFalse();
        assertThat(predicates.handles(Account.class, "cf_Guard_Probe")).isFalse();
        assertThat(predicates.handles(Account.class, "cf_guard_probe; drop table accounts")).isFalse();
        assertThat(predicates.handles(Account.class, "cf_guard-probe")).isFalse();
    }

    @Test
    void doesNotHandleCustomFieldNamesOnModelsWithoutCustomFields() {
        assertThat(predicates.handles(User.class, "cf_guard_probe")).isFalse();
    }

    @Test
    void returnsNoPredicateForEmptyValuesOrUnregisteredFields() {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Account> criteria = builder.createQuery(Account.class);
        Root<Account> root = criteria.from(Account.class);

        assertThat(predicates.toPredicate(
            root, criteria, builder, Account.class, "cf_guard_probe", "eq", List.of())).isNull();
        assertThat(predicates.toPredicate(
            root, criteria, builder, Account.class, "cf_unregistered", "eq", List.of("x"))).isNull();
        assertThat(predicates.toPredicate(
            root, criteria, builder, Account.class, "cf_guard_probe", "eq", List.of("x"))).isNotNull();
    }
}
