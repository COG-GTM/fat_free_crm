package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.query.CrmQueryService;
import com.fatfreecrm.service.query.ListQuery;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * A {@code cf_*} Ransack predicate must be ANDed with the Rails Public/Private/Shared visibility scope:
 * matching a custom field on another user's private record must not leak that record.
 */
@Transactional
class CustomFieldSearchAccessIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomFieldRegistry registry;

    @Autowired
    private CrmQueryService queryService;

    private AuthenticatedUser alice;
    private AuthenticatedUser bob;
    private AuthenticatedUser admin;
    private Long alicePrivateId;
    private Long bobPrivateId;
    private Long publicId;
    private Long publicWithoutRegionId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (990501, 'Account', 'access test', 1, now(), now())");
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "disabled, required, created_at, updated_at) VALUES "
                + "(990501, 'CustomField', 990501, 1, 'cf_access_region', 'Region', 'string', "
                + "false, false, now(), now())");
        registry.invalidate();
        User aliceUser = user("alice", false);
        User bobUser = user("bob", false);
        User adminUser = user("root", true);
        alice = new AuthenticatedUser(aliceUser.getId(), "alice", false);
        bob = new AuthenticatedUser(bobUser.getId(), "bob", false);
        admin = new AuthenticatedUser(adminUser.getId(), "root", true);
        alicePrivateId = account("Alice Private", "Private", aliceUser);
        bobPrivateId = account("Bob Private", "Private", bobUser);
        publicId = account("Shared North", "Public", bobUser);
        publicWithoutRegionId = account("Public South", "Public", bobUser);
        jdbcTemplate.update(
            "UPDATE accounts SET custom_fields = '{\"cf_access_region\": \"north\"}'::jsonb WHERE id IN (?, ?, ?)",
            alicePrivateId, bobPrivateId, publicId);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM fields WHERE id = 990501");
        jdbcTemplate.update("DELETE FROM field_groups WHERE id = 990501");
        registry.invalidate();
    }

    @Test
    void customFieldMatchesOnOtherUsersPrivateRecordsAreNotReturned() {
        assertThat(ids(alice, "q[cf_access_region_eq]=north"))
            .containsExactlyInAnyOrder(alicePrivateId, publicId)
            .doesNotContain(bobPrivateId);
        assertThat(ids(bob, "q[cf_access_region_eq]=north"))
            .containsExactlyInAnyOrder(bobPrivateId, publicId)
            .doesNotContain(alicePrivateId);
    }

    @Test
    void adminsSeeEveryMatchingRecord() {
        assertThat(ids(admin, "q[cf_access_region_eq]=north"))
            .containsExactlyInAnyOrder(alicePrivateId, bobPrivateId, publicId);
    }

    @Test
    void negatedAndBlankCustomFieldPredicatesStayInsideTheVisibilityScope() {
        assertThat(ids(alice, "q[cf_access_region_blank]=1")).containsExactly(publicWithoutRegionId);
        assertThat(ids(alice, "q[cf_access_region_null]=1")).containsExactly(publicWithoutRegionId);
        assertThat(ids(alice, "q[cf_access_region_not_eq]=south"))
            .containsExactlyInAnyOrder(alicePrivateId, publicId);
        assertThat(ids(alice, "q[cf_access_region_present]=1"))
            .containsExactlyInAnyOrder(alicePrivateId, publicId);
    }

    private List<Long> ids(AuthenticatedUser user, String params) {
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        for (String pair : params.split("&")) {
            int equals = pair.indexOf('=');
            map.add(pair.substring(0, equals), pair.substring(equals + 1));
        }
        return queryService.list(user, Account.class, ListQuery.fromParameters(map))
            .items().stream().map(Account::getId).toList();
    }

    private User user(String username, boolean admin) {
        User user = new User();
        user.setUsername(username);
        user.setEncryptedPassword("enc");
        user.setPasswordSalt("salt");
        user.setAdmin(admin);
        return userRepository.save(user);
    }

    private Long account(String name, String access, User owner) {
        Account account = new Account();
        account.setName(name);
        account.setUser(owner);
        account.setAccess(access);
        account.setCreatedAt(Instant.parse("2025-01-01T09:00:00Z"));
        account.setUpdatedAt(Instant.parse("2025-01-01T09:00:00Z"));
        return accountRepository.save(account).getId();
    }
}
