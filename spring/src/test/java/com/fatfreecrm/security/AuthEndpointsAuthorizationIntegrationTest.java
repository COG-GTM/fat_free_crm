package com.fatfreecrm.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import com.fatfreecrm.support.RailsUserFixtures;
import com.fatfreecrm.support.RailsUserFixtures.LegacyUser;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Complements {@link LegacyLoginIntegrationTest} with the authorization rules
 * from {@code SecurityConfig}, Devise parity cases it does not exercise
 * (soft-deleted users, case-insensitive email), request-validation failures,
 * and group-membership authorities read from the Rails {@code groups_users}
 * join table.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthEndpointsAuthorizationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String INSERT_USER = """
        INSERT INTO users (username, email, encrypted_password, password_salt, admin,
                           sign_in_count, subscribe_to_comment_replies, receive_assigned_notifications,
                           confirmed_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, 0, true, true, now(), now(), now())
        """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seedRailsUsers() {
        jdbcTemplate.update("DELETE FROM groups_users");
        jdbcTemplate.update("DELETE FROM groups");
        jdbcTemplate.update("DELETE FROM users");
        for (LegacyUser user : RailsUserFixtures.users()) {
            jdbcTemplate.update(INSERT_USER, user.username(), user.email(), user.encryptedPassword(),
                user.passwordSalt(), user.admin());
        }
    }

    @Test
    void refusesSoftDeletedUsersLikeRailsParanoia() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE username = ?",
            OffsetDateTime.now(), user.username());

        login(user.username(), user.password()).andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsEmailCaseInsensitivelyLikeDevise() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        login(user.email().toUpperCase(Locale.ROOT), user.password()).andExpect(status().isOk());
    }

    @Test
    void rejectsBlankLoginOrPasswordWithoutHittingAuthentication() throws Exception {
        login("", "whatever").andExpect(status().isBadRequest());
        login("legacy_user", "").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedLoginBodies() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("not json"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBlankRefreshTokenWithABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", ""))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void refusesAnAccessTokenOnTheRefreshEndpoint() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        JsonNode tokens = tokens(user);

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("refreshToken", tokens.get("accessToken").asText()))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void refusesToRefreshOnceTheUserIsSuspended() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        String refreshToken = tokens(user).get("refreshToken").asText();
        jdbcTemplate.update("UPDATE users SET suspended_at = ? WHERE username = ?",
            OffsetDateTime.now(), user.username());

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void refusesABearerTokenOnceTheUserIsSuspended() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        String accessToken = tokens(user).get("accessToken").asText();
        jdbcTemplate.update("UPDATE users SET suspended_at = ? WHERE username = ?",
            OffsetDateTime.now(), user.username());

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpointsRequireTheAdminRole() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        LegacyUser admin = RailsUserFixtures.user("legacy_admin");

        mockMvc.perform(get("/api/v1/admin/anything"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/anything")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens(user).get("accessToken").asText()))
            .andExpect(status().isForbidden());
        // No admin controller is mapped yet, so an authorized admin falls
        // through the role gate to a 404 rather than a 401/403.
        mockMvc.perform(get("/api/v1/admin/anything")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens(admin).get("accessToken").asText()))
            .andExpect(status().isNotFound());
    }

    @Test
    void unlistedEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/anything-else")).andExpect(status().isUnauthorized());
    }

    @Test
    void groupMembershipFromTheRailsJoinTableBecomesAGroupAuthority() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        jdbcTemplate.update("INSERT INTO groups (id, name, created_at, updated_at) VALUES (1, 'Sales', now(), now())");
        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE username = ?", Long.class, user.username());
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (1, ?)", userId);

        mockMvc.perform(get("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens(user).get("accessToken").asText()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authorities").value(org.hamcrest.Matchers.hasItem("GROUP_Sales")));
    }

    private org.springframework.test.web.servlet.ResultActions login(String login, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("login", login, "password", password))));
    }

    private JsonNode tokens(LegacyUser user) throws Exception {
        String body = login(user.username(), user.password())
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
