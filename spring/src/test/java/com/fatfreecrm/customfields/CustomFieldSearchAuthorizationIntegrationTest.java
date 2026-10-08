package com.fatfreecrm.customfields;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * A {@code q[cf_*_eq]} search over {@code GET /api/v1/accounts} must stay inside the Rails
 * Public/Private/Shared access scope: the JSONB predicate is AND-ed with the access policy, so a
 * matching custom-field value never leaks another user's Private or un-shared record.
 */
@Transactional
class CustomFieldSearchAuthorizationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final long GROUP_ID = 990401;
    private static final long FIELD_ID = 990401;
    private static final long PERMISSION_ID = 990401;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private CustomFieldRegistry registry;

    private String aliceBearer;
    private String bobBearer;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM permissions WHERE id = ?", PERMISSION_ID);
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_authz_tier text");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (?, 'Account', 'authz test', 1, now(), now())", GROUP_ID);
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "collection, disabled, required, created_at, updated_at) "
                + "VALUES (?, 'CustomField', ?, 1, 'cf_authz_tier', 'Tier', 'select', "
                + "'--- []', false, false, now(), now())", FIELD_ID, GROUP_ID);
        registry.invalidate();

        User alice = user("alice");
        User bob = user("bob");
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
        bobBearer = "Bearer " + jwtTokenService.issue(bob).accessToken();

        account("ab271-authz-alice-private", alice, "Private", "gold");
        account("ab271-authz-alice-public-silver", alice, "Public", "silver");
        account("ab271-authz-bob-private", bob, "Private", "gold");
        account("ab271-authz-bob-public", bob, "Public", "gold");
        long granted = account("ab271-authz-bob-shared-granted", bob, "Shared", "gold");
        account("ab271-authz-bob-shared-withheld", bob, "Shared", "gold");
        jdbcTemplate.update(
            "INSERT INTO permissions (id, user_id, asset_type, asset_id, created_at, updated_at) "
                + "VALUES (?, ?, 'Account', ?, now(), now())",
            PERMISSION_ID, alice.getId(), granted);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS cf_authz_tier");
        jdbcTemplate.update("DELETE FROM permissions WHERE id = ?", PERMISSION_ID);
        jdbcTemplate.update("DELETE FROM fields WHERE id = ?", FIELD_ID);
        jdbcTemplate.update("DELETE FROM field_groups WHERE id = ?", GROUP_ID);
        jdbcTemplate.update("DELETE FROM accounts WHERE name LIKE 'ab271-authz-%'");
        registry.invalidate();
    }

    @Test
    void customFieldSearchRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").param("q[cf_authz_tier_eq]", "gold"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void customFieldSearchHidesOtherUsersPrivateAndUnsharedRecords() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")
                .param("q[cf_authz_tier_eq]", "gold")
                .param("per_page", "200")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(3))
            .andExpect(jsonPath("$.items[*].name").value(containsInAnyOrder(
                "ab271-authz-alice-private",
                "ab271-authz-bob-public",
                "ab271-authz-bob-shared-granted")));
    }

    @Test
    void ownerSeesAllOfTheirOwnMatchesButNotAnotherUsersPrivateRecord() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")
                .param("q[cf_authz_tier_eq]", "gold")
                .param("per_page", "200")
                .header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(4))
            .andExpect(jsonPath("$.items[*].name").value(containsInAnyOrder(
                "ab271-authz-bob-private",
                "ab271-authz-bob-public",
                "ab271-authz-bob-shared-granted",
                "ab271-authz-bob-shared-withheld")));
    }

    @Test
    void customFieldSearchDoesNotWidenTheAccessScopeForNonMatchingValues() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")
                .param("q[cf_authz_tier_eq]", "silver")
                .param("per_page", "200")
                .header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].name").value("ab271-authz-alice-public-silver"));
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEncryptedPassword("enc");
        user.setPasswordSalt("salt");
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.save(user);
    }

    private long account(String name, User owner, String access, String tier) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, user_id, access, cf_authz_tier, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, now(), now()) RETURNING id",
            Long.class, name, owner.getId(), access, tier);
    }
}
