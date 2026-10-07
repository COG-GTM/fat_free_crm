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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private CheckBoxesYamlCodec yamlCodec;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_predicate_segment");
        jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_predicate_special_string");
        jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_predicate_special_select");
        jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_predicate_special_boxes");
        jdbcTemplate.update("DELETE FROM fields WHERE id BETWEEN 990291 AND 990294");
        jdbcTemplate.update("DELETE FROM field_groups WHERE id IN (990291, 990292)");
        jdbcTemplate.update("DELETE FROM accounts WHERE name LIKE 'ab271-predicate-%'");
        registry.invalidate();
    }

    @Test
    void equalityUsesJsonbContainmentAndTheCustomFieldsGinIndex() {
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_predicate_segment text");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (990291, 'Account', 'predicate test', 1, now(), now())");
        insertField(990291, 990291, 1, "cf_predicate_segment", "select");
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
        assertThat(generated).contains("custom_fields").contains("@>").contains("as jsonb").contains("?")
            .doesNotContain("{\"cf_predicate_segment\":\"prospect\"}");

        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
        List<String> plan = jdbcTemplate.query(
            "EXPLAIN " + generated,
            statement -> statement.setString(1, "{\"cf_predicate_segment\":\"prospect\"}"),
            (result, rowNum) -> result.getString(1));
        assertThat(String.join("\n", plan)).contains("Bitmap Index Scan on index_accounts_on_custom_fields");
    }

    @Test
    void bindsSearchValuesContainingSqlAndLikeMetacharacters() {
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_predicate_special_string text");
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_predicate_special_select text");
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_predicate_special_boxes text");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (990292, 'Account', 'predicate special test', 1, now(), now())");
        insertField(990292, 990292, 1, "cf_predicate_special_string", "string");
        insertField(990293, 990292, 2, "cf_predicate_special_select", "select");
        insertField(990294, 990292, 3, "cf_predicate_special_boxes", "check_boxes");

        String special = "O'Brien\\path%_SKU";
        jdbcTemplate.update(
            "INSERT INTO accounts (name, cf_predicate_special_string, "
                + "cf_predicate_special_select, cf_predicate_special_boxes) VALUES (?, ?, ?, ?)",
            "ab271-predicate-special-match", special, special, yamlCodec.encode(List.of(special)));
        jdbcTemplate.update(
            "INSERT INTO accounts (name, cf_predicate_special_string, "
                + "cf_predicate_special_select, cf_predicate_special_boxes) VALUES (?, ?, ?, ?)",
            "ab271-predicate-special-decoy", "other", "other", yamlCodec.encode(List.of("other")));
        Long matchingId = jdbcTemplate.queryForObject(
            "SELECT id FROM accounts WHERE name = 'ab271-predicate-special-match'", Long.class);
        registry.invalidate();

        assertBoundSearch("cf_predicate_special_string", "eq", special, matchingId, special);
        assertBoundSearch("cf_predicate_special_select", "eq", special, matchingId, special);
        assertBoundSearch("cf_predicate_special_string", "cont", special, matchingId, special);
        assertBoundSearch("cf_predicate_special_boxes", "cont", special, matchingId, special);
    }

    private void assertBoundSearch(
        String attribute, String operator, String value, Long expectedId, String sensitiveValue
    ) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Account> criteria = builder.createQuery(Account.class);
        Root<Account> root = criteria.from(Account.class);
        Predicate predicate = predicates.toPredicate(
            root, criteria, builder, Account.class, attribute, operator, List.of(value));
        criteria.select(root).where(predicate);

        CustomFieldsSqlRecorder.clear();
        List<Long> ids = entityManager.createQuery(criteria).getResultList().stream()
            .map(Account::getId)
            .toList();
        assertThat(ids).as("%s %s", attribute, operator).containsExactly(expectedId);
        String generated = CustomFieldsSqlRecorder.statements().stream()
            .filter(statement -> statement.startsWith("select") && statement.contains("custom_fields"))
            .findFirst()
            .orElseThrow();
        assertThat(generated).contains("?").doesNotContain(sensitiveValue).doesNotContain(attribute);
    }

    private void insertField(long id, long fieldGroupId, int position, String name, String as) {
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "collection, disabled, required, created_at, updated_at) "
                + "VALUES (?, 'CustomField', ?, ?, ?, ?, ?, '--- []', false, false, now(), now())",
            id, fieldGroupId, position, name, name, as);
    }
}
