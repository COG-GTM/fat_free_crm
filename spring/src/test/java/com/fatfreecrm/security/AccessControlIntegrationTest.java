package com.fatfreecrm.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import com.fatfreecrm.support.RailsUserFixtures;
import com.fatfreecrm.support.RailsUserFixtures.LegacyUser;
import java.util.Map;
import org.hamcrest.Matchers;
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
 * Role- and group-based access control through the full HTTP stack: the
 * {@code /api/v1/admin/**} guard, group authorities sourced from the Rails
 * {@code groups_users} table, and login request validation failures.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AccessControlIntegrationTest extends AbstractPostgresIntegrationTest {

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
    void adminRoutesRejectAnonymousCallersWith401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/anything")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminRoutesRejectAuthenticatedNonAdminsWith403() throws Exception {
        String token = accessToken(RailsUserFixtures.user("legacy_user"));
        mockMvc.perform(get("/api/v1/admin/anything").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminRoutesLetAdminsPastTheRoleGuard() throws Exception {
        String token = accessToken(RailsUserFixtures.user("legacy_admin"));
        // No admin controller exists yet, so passing the guard surfaces as 404 rather than 401/403.
        mockMvc.perform(get("/api/v1/admin/anything").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isNotFound());
    }

    @Test
    void groupMembershipInTheRailsJoinTableBecomesAGroupAuthority() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE username = ?", Long.class, user.username());
        Long groupId = jdbcTemplate.queryForObject(
            "INSERT INTO groups (name, created_at, updated_at) VALUES ('Sales', now(), now()) RETURNING id",
            Long.class);
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", groupId, userId);

        mockMvc.perform(get("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authorities").value(Matchers.hasItem("GROUP_Sales")));
    }

    @Test
    void emailLoginIsCaseInsensitiveLikeDevise() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        login(user.email().toUpperCase(java.util.Locale.ROOT), user.password()).andExpect(status().isOk());
    }

    @Test
    void blankLoginOrPasswordFailsValidationWith400() throws Exception {
        login("", "secret").andExpect(status().isBadRequest());
        login("someone", "").andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void blankRefreshTokenFailsValidationWith400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", ""))))
            .andExpect(status().isBadRequest());
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
