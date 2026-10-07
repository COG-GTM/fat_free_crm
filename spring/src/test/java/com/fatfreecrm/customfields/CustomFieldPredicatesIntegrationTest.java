package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.AfterEach;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "com.fatfreecrm.customfields.CustomFieldsSqlRecorder"
})
@Transactional
class CustomFieldPredicatesIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomFieldRegistry registry;

    @Autowired
    private CustomFieldPredicates predicates;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_predicate_segment");
        jdbcTemplate.update("DELETE FROM fields WHERE id = 990291");
        jdbcTemplate.update("DELETE FROM field_groups WHERE id = 990291");
        jdbcTemplate.update("DELETE FROM accounts WHERE name LIKE 'ab271-predicate-%'");
        registry.invalidate();
    }

    @Test
    void equalityUsesJsonbContainmentAndTheCustomFieldsGinIndex() {
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_predicate_segment text");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (990291, 'Account', 'predicate test', 1, now(), now())");
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "created_at, updated_at) VALUES (990291, 'CustomField', 990291, 1, "
                + "'cf_predicate_segment', 'Segment', 'select', now(), now())");
        jdbcTemplate.execute(
            "INSERT INTO accounts (name, cf_predicate_segment) "
                + "SELECT 'ab271-predicate-' || n, CASE WHEN n % 10 = 0 THEN 'prospect' ELSE 'customer' END "
                + "FROM generate_series(1, 2000) n");
        jdbcTemplate.execute("ANALYZE accounts");
        registry.invalidate();

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Account> criteria = builder.createQuery(Account.class);
        Root<Account> root = criteria.from(Account.class);
        Predicate predicate = predicates.toPredicate(
            root, criteria, builder, Account.class, "cf_predicate_segment", "eq", List.of("prospect"));
        criteria.select(root).where(predicate);

        CustomFieldsSqlRecorder.clear();
        assertThat(entityManager.createQuery(criteria).getResultList()).hasSize(200);
        String generated = CustomFieldsSqlRecorder.statements().stream()
            .filter(statement -> statement.startsWith("select") && statement.contains("@>"))
            .findFirst()
            .orElseThrow();
        assertThat(generated).contains("custom_fields").contains("@>").contains("as jsonb");

        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
        String explainable = generated.replace(
            "cast('{\"cf_predicate_segment\":\"prospect\"}' as jsonb)",
            "'{\"cf_predicate_segment\":\"prospect\"}'::jsonb");
        List<String> plan = jdbcTemplate.queryForList("EXPLAIN " + explainable, String.class);
        assertThat(String.join("\n", plan)).contains("Bitmap Index Scan on index_accounts_on_custom_fields");
    }
}
