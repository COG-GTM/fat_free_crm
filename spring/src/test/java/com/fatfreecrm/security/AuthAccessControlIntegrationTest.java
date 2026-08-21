package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.repository.UserRepository;
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
 * Access-control and input-validation coverage for the auth endpoints beyond
 * the happy path: soft-deleted users, role-gated admin routes, group-derived
 * authorities read from the Rails {@code groups_users} join table, and
 * malformed login payloads.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthAccessControlIntegrationTest extends AbstractPostgresIntegrationTest {

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

    @Autowired
    private UserRepository userRepository;

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
    void refusesSoftDeletedUsersLikeRailsDoes() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE username = ?",
            OffsetDateTime.now(), user.username());

        login(user.username(), user.password()).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshIsRefusedForAUserSuspendedAfterLogin() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        String refreshToken = objectMapper.readTree(login(user.username(), user.password())
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).get("refreshToken").asText();

        jdbcTemplate.update("UPDATE users SET suspended_at = ? WHERE username = ?",
            OffsetDateTime.now(), user.username());

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void emailLoginIsCaseInsensitiveLikeDevise() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        login(user.email().toUpperCase(Locale.ROOT), user.password()).andExpect(status().isOk());
    }

    @Test
    void groupMembershipFromTheRailsJoinTableBecomesAGroupAuthority() throws Exception {
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        jdbcTemplate.update("INSERT INTO groups (name, created_at, updated_at) VALUES ('sales', now(), now())");
        jdbcTemplate.update("""
            INSERT INTO groups_users (group_id, user_id)
            SELECT g.id, u.id FROM groups g, users u WHERE g.name = 'sales' AND u.username = ?
            """, user.username());

        mockMvc.perform(get("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authorities").value(org.hamcrest.Matchers.hasItem("GROUP_sales")));
    }

    @Test
    void adminRoutesAreForbiddenForNonAdminsButNotForAdmins() throws Exception {
        String userToken = accessToken(RailsUserFixtures.user("legacy_user"));
        String adminToken = accessToken(RailsUserFixtures.user("legacy_admin"));

        mockMvc.perform(get("/api/v1/admin/anything")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
            .andExpect(status().isForbidden());
        // No admin controllers exist yet, so an admin token falls through to 404
        // rather than being rejected by the role check.
        mockMvc.perform(get("/api/v1/admin/anything")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void adminRoutesRequireAuthenticationAtAll() throws Exception {
        mockMvc.perform(get("/api/v1/admin/anything")).andExpect(status().isUnauthorized());
    }

    @Test
    void blankOrMissingLoginFieldsAreRejectedAsBadRequests() throws Exception {
        login("", "whatever").andExpect(status().isBadRequest());
        LegacyUser user = RailsUserFixtures.user("legacy_user");
        login(user.username(), "").andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void userLookupRoundTripsThroughTheRailsSchema() {
        LegacyUser fixture = RailsUserFixtures.user("legacy_user");

        var byUsername = userRepository.findByLogin(fixture.username().toLowerCase(Locale.ROOT));
        assertThat(byUsername).isNotEmpty();
        assertThat(byUsername.getFirst().getEncryptedPassword()).isEqualTo(fixture.encryptedPassword());
        assertThat(byUsername.getFirst().getPasswordSalt()).isEqualTo(fixture.passwordSalt());

        var byEmail = userRepository.findByLogin(fixture.email().toLowerCase(Locale.ROOT));
        assertThat(byEmail).isNotEmpty();
        assertThat(byEmail.getFirst().getUsername()).isEqualTo(fixture.username());
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
