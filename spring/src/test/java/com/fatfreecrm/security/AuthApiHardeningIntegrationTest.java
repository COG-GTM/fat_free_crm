package com.fatfreecrm.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Hardens the Phase 1 auth surface beyond the happy path: request validation,
 * Devise parity for case-insensitive email and soft-deleted users, admin
 * route protection, and group-derived authorities.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthApiHardeningIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String INSERT_USER = """
        INSERT INTO users (username, email, encrypted_password, password_salt, admin,
                           sign_in_count, subscribe_to_comment_replies, receive_assigned_notifications,
                           confirmed_at, created_at, updated_at, suspended_at, deleted_at)
        VALUES (?, ?, ?, ?, ?, 0, true, true, now(), now(), now(), null, null)
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
    void rejectsBlankLoginOrPasswordWithA400NotA500() throws Exception {
        login("", "secret").andExpect(status().isBadRequest());
        login("legacy_user", "").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedJsonWithA400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("not-json"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void emailLoginIsCaseInsensitiveLikeDeviseCaseInsensitiveKeys() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");

        login(user.email().toUpperCase(Locale.ROOT), user.password()).andExpect(status().isOk());
    }

    @Test
    void softDeletedUsersCannotLogIn() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE username = ?",
            OffsetDateTime.now(), user.username());

        login(user.username(), user.password()).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshIsRefusedOnceTheUserIsSuspended() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        String refreshToken = objectMapper.readTree(login(user.username(), user.password())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
            .get("refreshToken").asText();

        jdbcTemplate.update("UPDATE users SET suspended_at = ? WHERE username = ?",
            OffsetDateTime.now(), user.username());

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void accessTokenStopsWorkingOnceTheUserIsSuspended() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        String token = accessToken(user);
        jdbcTemplate.update("UPDATE users SET suspended_at = ? WHERE username = ?",
            OffsetDateTime.now(), user.username());

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void adminRoutesAreForbiddenForNonAdmins() throws Exception {
        String userToken = accessToken(RailsUserFixtures.user("legacy_user"));
        String adminToken = accessToken(RailsUserFixtures.user("legacy_admin"));

        mockMvc.perform(get("/api/v1/admin/anything")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/anything")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
            .andExpect(status().isForbidden());
        // No admin controllers exist yet, so an admin falls through to 404 —
        // the point is that the role check itself passes.
        mockMvc.perform(get("/api/v1/admin/anything")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void groupMembershipSurfacesAsGroupAuthorities() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        Long groupId = jdbcTemplate.queryForObject(
            "INSERT INTO groups (name, created_at, updated_at) VALUES ('sales', now(), now()) RETURNING id",
            Long.class);
        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE username = ?", Long.class, user.username());
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", groupId, userId);

        mockMvc.perform(get("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authorities").value(org.hamcrest.Matchers.hasItem("GROUP_sales")));
    }

    private org.springframework.test.web.servlet.ResultActions login(String login, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("login", login, "password", password))));
    }

    private String accessToken(LegacyUser user) throws Exception {
        String body = login(user.username(), user.password())
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
