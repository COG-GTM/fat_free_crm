package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.service.query.CrmQueryService;
import com.fatfreecrm.service.query.ListQuery;
import com.fatfreecrm.service.query.ListResult;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * A {@code cf_*} search condition is AND-ed with the Rails {@code Ability} row scope
 * ({@code Account.accessible_by(user.ability).ransack(q)}): filtering on a custom field must never
 * surface another user's Private or un-shared records, through the service or {@code GET /api/v1/accounts}.
 */
@Transactional
class CustomFieldsAccessControlIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String FIELD = "cf_ab271_tier";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomFieldRegistry registry;

    @Autowired
    private CrmQueryService queryService;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private User bob;
    private User admin;
    private Long publicGold;
    private Long alicePrivateGold;
    private Long sharedWithAliceGold;
    private Long bobPrivateGold;
    private Long sharedWithoutAliceGold;
    private Long publicSilver;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM permissions");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (990501, 'Account', 'ab271 access', 1, now(), now())");
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "disabled, required, created_at, updated_at) VALUES "
                + "(990501, 'CustomField', 990501, 1, '" + FIELD + "', 'Tier', 'string', false, false, now(), now())");
        registry.invalidate();

        alice = user("alice", false);
        bob = user("bob", false);
        admin = user("admin", true);

        publicGold = account("ab271-public-gold", "Public", bob, "gold");
        alicePrivateGold = account("ab271-alice-private-gold", "Private", alice, "gold");
        sharedWithAliceGold = account("ab271-shared-with-alice-gold", "Shared", bob, "gold");
        bobPrivateGold = account("ab271-bob-private-gold", "Private", bob, "gold");
        sharedWithoutAliceGold = account("ab271-shared-without-alice-gold", "Shared", bob, "gold");
        publicSilver = account("ab271-public-silver", "Public", bob, "silver");
        jdbcTemplate.update(
            "INSERT INTO permissions (user_id, asset_type, asset_id, created_at, updated_at) "
                + "VALUES (?, 'Account', ?, now(), now())",
            alice.getId(), sharedWithAliceGold);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM fields WHERE id = 990501");
        jdbcTemplate.update("DELETE FROM field_groups WHERE id = 990501");
        registry.invalidate();
    }

    @Test
    void customFieldSearchIsScopedToRowsTheUserCanAccess() {
        List<Long> ids = ids(queryService.list(principal(alice), Account.class, query("q[" + FIELD + "_eq]", "gold")));

        assertThat(ids).containsExactlyInAnyOrder(publicGold, alicePrivateGold, sharedWithAliceGold);
        assertThat(ids).doesNotContain(bobPrivateGold, sharedWithoutAliceGold, publicSilver);
    }

    @Test
    void ownerSeesOwnPrivateAndSharedRowsButNotAnotherUsersPrivateRow() {
        List<Long> ids = ids(queryService.list(principal(bob), Account.class, query("q[" + FIELD + "_eq]", "gold")));

        assertThat(ids).containsExactlyInAnyOrder(
            publicGold, sharedWithAliceGold, bobPrivateGold, sharedWithoutAliceGold);
        assertThat(ids).doesNotContain(alicePrivateGold);
    }

    @Test
    void adminSeesEveryMatchingRow() {
        List<Long> ids = ids(queryService.list(principal(admin), Account.class, query("q[" + FIELD + "_eq]", "gold")));

        assertThat(ids).containsExactlyInAnyOrder(
            publicGold, alicePrivateGold, sharedWithAliceGold, bobPrivateGold, sharedWithoutAliceGold);
    }

    @Test
    void negatedAndOrCompoundCustomFieldPredicatesDoNotWidenTheAccessScope() {
        List<Long> notSilver = ids(queryService.list(
            principal(alice), Account.class, query("q[" + FIELD + "_not_eq]", "silver")));
        assertThat(notSilver).containsExactlyInAnyOrder(publicGold, alicePrivateGold, sharedWithAliceGold);

        List<Long> orName = ids(queryService.list(
            principal(alice), Account.class, query("q[" + FIELD + "_or_name_eq]", "gold")));
        assertThat(orName).containsExactlyInAnyOrder(publicGold, alicePrivateGold, sharedWithAliceGold);

        List<Long> present = ids(queryService.list(
            principal(alice), Account.class, query("q[" + FIELD + "_present]", "1")));
        assertThat(present).containsExactlyInAnyOrder(publicGold, alicePrivateGold, sharedWithAliceGold, publicSilver);
    }

    @Test
    void unknownCustomFieldConditionIsIgnoredWithoutBypassingTheAccessScope() {
        List<Long> ids = ids(queryService.list(
            principal(alice), Account.class, query("q[cf_ab271_not_registered_eq]", "gold")));

        assertThat(ids).containsExactlyInAnyOrder(publicGold, alicePrivateGold, sharedWithAliceGold, publicSilver);
    }

    @Test
    void customFieldSearchOverHttpAppliesTheCallerAccessScope() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").param("q[" + FIELD + "_eq]", "gold"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/accounts")
                .param("q[" + FIELD + "_eq]", "gold")
                .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(3))
            .andExpect(jsonPath("$.items[*].name").value(org.hamcrest.Matchers.containsInAnyOrder(
                "ab271-public-gold", "ab271-alice-private-gold", "ab271-shared-with-alice-gold")));

        mockMvc.perform(get("/api/v1/accounts")
                .param("q[" + FIELD + "_eq]", "gold")
                .header(HttpHeaders.AUTHORIZATION, bearer(bob)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(4))
            .andExpect(jsonPath("$.items[*].name").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.hasItem("ab271-alice-private-gold"))));

        mockMvc.perform(get("/api/v1/accounts")
                .param("q[" + FIELD + "_eq]", "gold")
                .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(5));
    }

    private User user(String username, boolean isAdmin) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@ab271.example");
        user.setEncryptedPassword("enc");
        user.setPasswordSalt("salt");
        user.setAdmin(isAdmin);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.save(user);
    }

    private Long account(String name, String access, User owner, String tier) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, access, user_id, custom_fields, created_at, updated_at) "
                + "VALUES (?, ?, ?, jsonb_build_object('" + FIELD + "', ?::text), now(), now()) RETURNING id",
            Long.class, name, access, owner.getId(), tier);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private static AuthenticatedUser principal(User user) {
        return new AuthenticatedUser(user.getId(), user.getUsername(), user.isAdmin());
    }

    private static ListQuery query(String key, String value) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add(key, value);
        return ListQuery.fromParameters(params);
    }

    private static List<Long> ids(ListResult<Account> result) {
        return result.items().stream().map(Account::getId).toList();
    }
}
