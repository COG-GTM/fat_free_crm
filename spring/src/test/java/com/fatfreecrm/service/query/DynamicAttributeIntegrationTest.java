package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** {@link DynamicAttributePredicates} extension point: stub bean only (no {@code cf_*} implementation). */
@Transactional
@Import(DynamicAttributeIntegrationTest.StubConfig.class)
class DynamicAttributeIntegrationTest extends AbstractPostgresIntegrationTest {

    static final AtomicReference<String> SEEN_OPERATOR = new AtomicReference<>();
    static final AtomicReference<List<String>> SEEN_VALUES = new AtomicReference<>();

    @TestConfiguration
    static class StubConfig {

        @Bean
        DynamicAttributePredicates stubDynamicAttribute() {
            return new DynamicAttributePredicates() {
                @Override
                public boolean handles(Class<?> entityType, String attribute) {
                    return entityType == Account.class && attribute.equals("cf_region");
                }

                @Override
                public <T> Predicate toPredicate(
                    Root<T> root,
                    CriteriaQuery<?> query,
                    CriteriaBuilder cb,
                    Class<T> entityType,
                    String attribute,
                    String operator,
                    List<String> rawValues
                ) {
                    SEEN_OPERATOR.set(operator);
                    SEEN_VALUES.set(new ArrayList<>(rawValues));
                    return cb.equal(root.get("name"), "stub:" + rawValues.get(0));
                }
            };
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CrmQueryService queryService;

    private AuthenticatedUser alice;
    private Long stubMatchId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
        User owner = new User();
        owner.setUsername("alice");
        owner.setEncryptedPassword("enc");
        owner.setPasswordSalt("salt");
        userRepository.save(owner);
        alice = new AuthenticatedUser(owner.getId(), "alice", false);
        Account match = new Account();
        match.setName("stub:north");
        match.setUser(owner);
        match.setAccess("Public");
        match.setCreatedAt(Instant.parse("2025-01-01T09:00:00Z"));
        match.setUpdatedAt(Instant.parse("2025-01-01T09:00:00Z"));
        stubMatchId = accountRepository.save(match).getId();
        Account other = new Account();
        other.setName("Other Corp");
        other.setUser(owner);
        other.setAccess("Public");
        other.setCreatedAt(Instant.parse("2025-01-02T09:00:00Z"));
        other.setUpdatedAt(Instant.parse("2025-01-02T09:00:00Z"));
        accountRepository.save(other);
    }

    private ListQuery query(String... entries) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (String entry : entries) {
            for (String pair : entry.split("&")) {
                int equals = pair.indexOf('=');
                params.add(pair.substring(0, equals), pair.substring(equals + 1));
            }
        }
        return ListQuery.fromParameters(params);
    }

    @Test
    void dynamicAttributeReceivesOperatorAndStrippedRawValues() {
        ListResult<Account> result = queryService.list(alice, Account.class,
            query("q[cf_region_eq]= north "));
        assertThat(result.items()).extracting(Account::getId).containsExactly(stubMatchId);
        assertThat(SEEN_OPERATOR.get()).isEqualTo("eq");
        assertThat(SEEN_VALUES.get()).containsExactly("north");
    }

    @Test
    void dynamicAttributeComposesWithOr() {
        ListResult<Account> result = queryService.list(alice, Account.class,
            query("q[cf_region_or_name_eq]= north "));
        assertThat(result.items()).extracting(Account::getId).containsExactly(stubMatchId);
        assertThat(SEEN_OPERATOR.get()).isEqualTo("eq");
    }

    @Test
    void dynamicAttributeInsideGroup() {
        ListResult<Account> result = queryService.list(alice, Account.class,
            query("q[g][0][cf_region_eq]= north ", "q[g][0][m]=and"));
        assertThat(result.items()).extracting(Account::getId).containsExactly(stubMatchId);
    }
}
