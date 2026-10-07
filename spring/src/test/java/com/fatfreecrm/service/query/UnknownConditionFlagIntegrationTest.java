package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** {@code ffcrm.search.ignore-unknown-conditions=false} turns dropped conditions into a 400. */
@SpringBootTest(properties = "ffcrm.search.ignore-unknown-conditions=false")
@Transactional
class UnknownConditionFlagIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CrmQueryService queryService;

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
    void unknownConditionsThrowWhenFlagIsFalse() {
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
        User alice = new User();
        alice.setUsername("alice");
        alice.setEncryptedPassword("enc");
        alice.setPasswordSalt("salt");
        userRepository.save(alice);
        Account account = new Account();
        account.setName("Acme");
        account.setUser(alice);
        account.setAccess("Public");
        account.setCreatedAt(Instant.parse("2025-01-01T09:00:00Z"));
        account.setUpdatedAt(Instant.parse("2025-01-01T09:00:00Z"));
        accountRepository.save(account);

        AuthenticatedUser user = new AuthenticatedUser(alice.getId(), "alice", false);
        assertThatThrownBy(() -> queryService.list(user, Account.class, query("q[bogus_cont]=x")))
            .isInstanceOf(InvalidSearchQueryException.class)
            .satisfies(e -> assertThat(((InvalidSearchQueryException) e).invalidParameters())
                .contains("q[bogus_cont]"));
        assertThatThrownBy(() -> queryService.list(user, Account.class, query("q[name_bogus]=x")))
            .isInstanceOf(InvalidSearchQueryException.class);
        // Valid conditions still work.
        assertThat(queryService.list(user, Account.class, query("q[name_cont]=acme")).totalCount())
            .isEqualTo(1);
    }
}
