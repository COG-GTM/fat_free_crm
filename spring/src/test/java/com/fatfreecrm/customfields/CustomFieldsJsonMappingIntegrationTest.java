package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class CustomFieldsJsonMappingIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void deserializesJsonbFractionalNumbersAsBigDecimal() {
        Long id = jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, custom_fields) "
                + "VALUES ('ab271-jsonb-decimal', '{\"cf_amount\":1234.50}'::jsonb) RETURNING id",
            Long.class);
        entityManager.clear();

        Account account = entityManager.find(Account.class, id);

        assertThat(account.getCustomFields().get("cf_amount"))
            .isInstanceOf(BigDecimal.class)
            .isEqualTo(new BigDecimal("1234.50"));
    }
}
