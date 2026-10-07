package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.customfields.CustomFieldRegistry;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-level flow for custom-field search: a {@code q[cf_*]} Ransack predicate on
 * {@code GET /api/v1/accounts} is answered from the JSONB column and is still subject to Rails'
 * Public/Private/Shared visibility rules and to authentication.
 */
@Transactional
class AccountsControllerCustomFieldSearchIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private CustomFieldRegistry registry;

    @Autowired
    private ObjectMapper objectMapper;

    private String aliceBearer;
    private Long alicePublicId;
    private Long alicePrivateId;
    private Long bobPrivateId;
    private Long sharedWithAliceId;
    private Long bobPublicOtherRegionId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM permissions");
        jdbcTemplate.update("DELETE FROM taggings");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, \"position\", created_at, updated_at) "
                + "VALUES (990801, 'Account', 'api search', 1, now(), now())");
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", "
                + "disabled, required, created_at, updated_at) VALUES "
                + "(990801, 'CustomField', 990801, 1, 'cf_api_region', 'Region', 'string', false, false, now(), now())");
        registry.invalidate();

        User alice = user("alice");
        User bob = user("bob");
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();

        alicePublicId = account("ab271-alice-public", alice, "Public", "north");
        alicePrivateId = account("ab271-alice-private", alice, "Private", "north");
        bobPrivateId = account("ab271-bob-private", bob, "Private", "north");
        sharedWithAliceId = account("ab271-shared", bob, "Shared", "north");
        jdbcTemplate.update(
            "INSERT INTO permissions (user_id, asset_id, asset_type, created_at, updated_at) "
                + "VALUES (?, ?, 'Account', now(), now())", alice.getId(), sharedWithAliceId);
        bobPublicOtherRegionId = account("ab271-bob-public-south", bob, "Public", "south");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM fields WHERE id = 990801");
        jdbcTemplate.update("DELETE FROM field_groups WHERE id = 990801");
        registry.invalidate();
    }

    @Test
    void customFieldPredicateIsScopedToAccountsTheCallerMaySee() throws Exception {
        List<Long> ids = ids(aliceBearer, "north");

        assertThat(ids)
            .containsExactlyInAnyOrder(alicePublicId, alicePrivateId, sharedWithAliceId)
            .doesNotContain(bobPrivateId, bobPublicOtherRegionId);
    }

    @Test
    void customFieldPredicateOnlyMatchesTheJsonbValue() throws Exception {
        assertThat(ids(aliceBearer, "south")).containsExactly(bobPublicOtherRegionId);
        assertThat(ids(aliceBearer, "nowhere")).isEmpty();
    }

    @Test
    void customFieldSearchRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").param("q[cf_api_region_eq]", "north"))
            .andExpect(status().isUnauthorized());
    }

    private List<Long> ids(String bearer, String region) throws Exception {
        String body = mockMvc.perform(get("/api/v1/accounts")
                .param("q[cf_api_region_eq]", region)
                .param("per_page", "100")
                .header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode items = objectMapper.readTree(body).path("items");
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : items) {
            ids.add(item.path("id").asLong());
        }
        return ids;
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEncryptedPassword("enc");
        user.setPasswordSalt("salt");
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.save(user);
    }

    private Long account(String name, User owner, String access, String region) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, user_id, access, custom_fields, created_at, updated_at) "
                + "VALUES (?, ?, ?, jsonb_build_object('cf_api_region', ?::text), now(), now()) RETURNING id",
            Long.class, name, owner.getId(), access, region);
    }
}
