package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Phase 1 exit criterion: a Java test authenticates a user whose password hash
 * was written by the Rails app. The rows are inserted with raw SQL — exactly the
 * bytes Rails produced — so nothing in the Java stack can quietly re-hash them.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LegacyLoginIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String INSERT_USER = """
        INSERT INTO users (username, email, encrypted_password, password_salt, admin,
                           sign_in_count, subscribe_to_comment_replies, receive_assigned_notifications,
                           confirmed_at, created_at, updated_at, suspended_at)
        VALUES (?, ?, ?, ?, ?, 0, true, true, now(), now(), now(), ?)
        """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seedRailsUsers() {
        jdbcTemplate.update("DELETE FROM users");
        for (LegacyUser user : RailsUserFixtures.users()) {
            jdbcTemplate.update(INSERT_USER, user.username(), user.email(), user.encryptedPassword(),
                user.passwordSalt(), user.admin(), null);
        }
    }

    @Test
    void logsInWithARailsGeneratedHashAndReturnsAUsableAccessToken() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_admin");

        String body = login(user.username(), user.password())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(300))
            .andReturn().getResponse().getContentAsString();
        JsonNode tokens = objectMapper.readTree(body);

        mockMvc.perform(get("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get("accessToken").asText()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value(user.username()))
            .andExpect(jsonPath("$.email").value(user.email()))
            .andExpect(jsonPath("$.authorities").value(org.hamcrest.Matchers.hasItems("ROLE_USER", "ROLE_ADMIN")));
    }

    @Test
    void nonAdminUserDoesNotGetTheAdminAuthority() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        String token = accessToken(user);

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authorities").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.hasItem("ROLE_ADMIN"))));
    }

    @Test
    void acceptsEmailAsTheLoginKeyLikeDevise() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        login(user.email(), user.password()).andExpect(status().isOk());
    }

    @Test
    void rejectsWrongPasswordAndUnknownUserIdentically() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        login(user.username(), "not-the-password").andExpect(status().isUnauthorized());
        login("no-such-person", user.password()).andExpect(status().isUnauthorized());
    }

    @Test
    void refusesSuspendedUsers() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        jdbcTemplate.update("UPDATE users SET suspended_at = ? WHERE username = ?",
            OffsetDateTime.now(), user.username());

        login(user.username(), user.password()).andExpect(status().isUnauthorized());
    }

    @Test
    void requiresAuthenticationForProtectedEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenBuysANewAccessTokenButCannotBeUsedAsOne() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        JsonNode tokens = objectMapper.readTree(login(user.username(), user.password())
            .andReturn().getResponse().getContentAsString());
        String refreshToken = tokens.get("refreshToken").asText();

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshToken))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", "garbage"))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void updatesDeviseTrackableColumnsOnSuccessfulLogin() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");

        login(user.username(), user.password()).andExpect(status().isOk());
        Map<String, Object> first = trackable(user.username());
        assertThat(first.get("sign_in_count")).isEqualTo(1);
        assertThat(first.get("current_sign_in_at")).isNotNull();

        login(user.username(), user.password()).andExpect(status().isOk());
        Map<String, Object> second = trackable(user.username());
        assertThat(second.get("sign_in_count")).isEqualTo(2);
        assertThat(second.get("last_sign_in_at")).isEqualTo(first.get("current_sign_in_at"));
    }

    @Test
    void leavesTheRailsHashUntouchedSoRailsCanStillLogTheUserIn() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        login(user.username(), user.password()).andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT encrypted_password, password_salt FROM users WHERE username = ?", user.username());
        assertThat(row.get("encrypted_password")).isEqualTo(user.encryptedPassword());
        assertThat(row.get("password_salt")).isEqualTo(user.passwordSalt());
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

    private Map<String, Object> trackable(String username) {
        return jdbcTemplate.queryForMap(
            "SELECT sign_in_count, current_sign_in_at, last_sign_in_at FROM users WHERE username = ?", username);
    }
}
