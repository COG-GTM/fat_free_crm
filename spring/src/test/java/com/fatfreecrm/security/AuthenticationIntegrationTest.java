package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import com.fatfreecrm.support.LegacyAuthFixtures;
import com.fatfreecrm.support.LegacyAuthFixtures.LegacyUser;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class AuthenticationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String JWT_SECRET = "spring-test-secret-with-at-least-32-bytes";
    private static final String INSERT_USER = """
        INSERT INTO users (username, email, first_name, last_name, encrypted_password, password_salt, admin,
                           confirmed_at, suspended_at, sign_in_count, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, now(), TIMESTAMP '2020-01-01 00:00:00')
        """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void insertRailsFixtureUsers() {
        deleteFixtureUsers();
        Instant now = Instant.now();
        for (LegacyUser user : LegacyAuthFixtures.users()) {
            jdbcTemplate.update(
                INSERT_USER,
                user.username(),
                user.email(),
                user.firstName(),
                user.lastName(),
                user.encryptedPassword(),
                user.passwordSalt(),
                user.admin(),
                user.confirmed() ? Timestamp.from(now) : null,
                user.suspended() ? Timestamp.from(now) : null
            );
        }
    }

    @AfterEach
    void removeRailsFixtureUsers() {
        deleteFixtureUsers();
    }

    @Test
    void authenticatesEveryConfirmedUnsuspendedRailsFixturePassword() throws Exception {
        for (LegacyUser user : LegacyAuthFixtures.users()) {
            if (user.confirmed() && !user.suspended()) {
                login(user.username(), user.password())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.expiresIn").value(900));
            }
        }

        login("  legacy_mixedcase  ", LegacyAuthFixtures.user("Legacy_MixedCase").password())
            .andExpect(status().isOk());
        login(
            " " + LegacyAuthFixtures.user("Legacy_MixedCase").email().toUpperCase(java.util.Locale.ROOT) + " ",
            LegacyAuthFixtures.user("Legacy_MixedCase").password()
        ).andExpect(status().isOk());
        login(
            LegacyAuthFixtures.user("legacy_plain").email(),
            LegacyAuthFixtures.user("legacy_plain").password()
        ).andExpect(status().isOk());

        login("legacy_plain", LegacyAuthFixtures.user("legacy_plain").password(), "Bearer invalid")
            .andExpect(status().isOk());
    }

    @Test
    void returnsIdenticalProblemBodiesForBadPasswordUnknownSuspendedAndUnconfirmedUsers() throws Exception {
        LegacyUser plain = LegacyAuthFixtures.user("legacy_plain");
        LegacyUser suspended = LegacyAuthFixtures.user("legacy_suspended");
        LegacyUser unconfirmed = LegacyAuthFixtures.user("legacy_unconfirmed");
        List<String> bodies = List.of(
            login("legacy_plain", plain.password() + "wrong").andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn().getResponse().getContentAsString(),
            login("unknown_user", plain.password()).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn().getResponse().getContentAsString(),
            login(suspended.username(), suspended.password()).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn().getResponse().getContentAsString(),
            login(unconfirmed.username(), unconfirmed.password()).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn().getResponse().getContentAsString()
        );

        JsonNode expected = objectMapper.readTree(bodies.getFirst());
        for (String body : bodies) {
            assertThat(objectMapper.readTree(body)).isEqualTo(expected);
        }
        assertThat(expected.path("detail").asText()).isEqualTo("Invalid credentials.");
    }

    @Test
    void validatesRequiredLoginFieldsAsProblemDetails() throws Exception {
        for (String body : List.of(
            "{\"username\":\"legacy_plain\"}",
            "{\"password\":\"password\"}",
            "{\"username\":\" \",\"password\":\"password\"}",
            "{\"username\":\"legacy_plain\",\"password\":\" \"}"
        )) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\" \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void getsCurrentUserFromTheAccessTokenAndReturnsTheExpectedFields() throws Exception {
        LegacyUser admin = LegacyAuthFixtures.user("legacy_admin");
        JsonNode tokens = objectMapper.readTree(login(admin.username(), admin.password())
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.path("accessToken").asText()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.username").value(admin.username()))
            .andExpect(jsonPath("$.email").value(admin.email()))
            .andExpect(jsonPath("$.firstName").value(admin.firstName()))
            .andExpect(jsonPath("$.lastName").value(admin.lastName()))
            .andExpect(jsonPath("$.admin").value(true));
    }

    @Test
    void refreshesTokensAndRejectsAccessTokensAsRefreshTokensAndRefreshBearerTokens() throws Exception {
        LegacyUser user = LegacyAuthFixtures.user("legacy_plain");
        JsonNode original = objectMapper.readTree(login(user.username(), user.password())
            .andReturn().getResponse().getContentAsString());
        JsonNode refreshed = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "refreshToken", original.path("refreshToken").asText()
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andReturn().getResponse().getContentAsString());
        assertThat(refreshed.path("accessToken").asText()).isNotEqualTo(original.path("accessToken").asText());
        assertThat(refreshed.path("refreshToken").asText()).isNotBlank();
        mockMvc.perform(get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshed.path("accessToken").asText()))
            .andExpect(status().isOk());

        postRefresh(original.path("accessToken").asText()).andExpect(status().isUnauthorized());
        String expiredRefresh = signedToken(
            userId(user.username()),
            "refresh",
            Instant.now().minusSeconds(120),
            Instant.now().minusSeconds(180)
        );
        postRefresh(expiredRefresh).andExpect(status().isUnauthorized());
        postRefresh(flipLastCharacter(original.path("refreshToken").asText()))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshed.path("refreshToken").asText()))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        jdbcTemplate.update("UPDATE users SET suspended_at = now() WHERE username = ?", user.username());
        postRefresh(original.path("refreshToken").asText()).andExpect(status().isUnauthorized());
    }

    @Test
    void requiresAuthenticationForLogoutAndReturnsNoContentWhenAuthenticated() throws Exception {
        LegacyUser user = LegacyAuthFixtures.user("legacy_plain");
        JsonNode tokens = objectMapper.readTree(login(user.username(), user.password())
            .andReturn().getResponse().getContentAsString());

        mockMvc.perform(post("/api/v1/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.path("accessToken").asText()))
            .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/logout"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void rejectsExpiredTamperedAndNewlySuspendedUserTokens() throws Exception {
        LegacyUser user = LegacyAuthFixtures.user("legacy_plain");
        JsonNode tokens = objectMapper.readTree(login(user.username(), user.password())
            .andReturn().getResponse().getContentAsString());
        String token = tokens.path("accessToken").asText();
        String tampered = flipLastCharacter(token);
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
            .andExpect(status().isUnauthorized());

        String expired = signedToken(
            userId(user.username()),
            "access",
            Instant.now().minusSeconds(120),
            Instant.now().minusSeconds(180)
        );
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
            .andExpect(status().isUnauthorized());

        jdbcTemplate.update("UPDATE users SET suspended_at = now() WHERE username = ?", user.username());
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void tracksSuccessfulSignInsAndDoesNotTrackBearerRequestsOrFailedLogins() throws Exception {
        LegacyUser user = LegacyAuthFixtures.user("legacy_plain");
        String ip = "203.0.113.7";
        Map<String, Object> beforeLogin = trackableRow(user.username());
        String originalHash = (String) beforeLogin.get("encrypted_password");
        String originalSalt = (String) beforeLogin.get("password_salt");
        MvcResult firstLogin = login(user.username(), user.password(), "203.0.113.7, 198.51.100.5")
            .andExpect(status().isOk())
            .andReturn();
        Map<String, Object> first = trackableRow(user.username());
        assertThat(first.get("sign_in_count")).isEqualTo(1);
        assertThat(first.get("current_sign_in_ip")).isEqualTo(ip);
        assertThat(first.get("last_sign_in_ip")).isEqualTo(ip);
        assertThat(first.get("current_sign_in_at")).isNotNull();
        assertThat(first.get("last_sign_in_at")).isEqualTo(first.get("current_sign_in_at"));
        assertThat(timestamp(first.get("updated_at"))).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(timestamp(first.get("current_sign_in_at")))
            .isBetween(Instant.now().minusSeconds(30), Instant.now().plusSeconds(1));
        assertThat(first.get("encrypted_password")).isEqualTo(originalHash);
        assertThat(first.get("password_salt")).isEqualTo(originalSalt);

        String accessToken = objectMapper.readTree(firstLogin.getResponse().getContentAsString())
            .path("accessToken")
            .asText();
        login(user.username(), user.password(), "198.51.100.8").andExpect(status().isOk());
        Map<String, Object> second = trackableRow(user.username());
        assertThat(second.get("sign_in_count")).isEqualTo(2);
        assertThat(second.get("last_sign_in_at")).isEqualTo(first.get("current_sign_in_at"));
        assertThat(second.get("last_sign_in_ip")).isEqualTo(ip);
        assertThat(second.get("current_sign_in_ip")).isEqualTo("198.51.100.8");

        mockMvc.perform(get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
            .andExpect(status().isOk());
        assertThat(trackableRow(user.username()).get("sign_in_count")).isEqualTo(2);

        Map<String, Object> beforeFailure = trackableRow(user.username());
        login(user.username(), user.password() + "wrong").andExpect(status().isUnauthorized());
        Map<String, Object> afterFailure = trackableRow(user.username());
        assertThat(afterFailure).isEqualTo(beforeFailure);
        assertThat(afterFailure.get("encrypted_password")).isEqualTo(user.encryptedPassword());
        assertThat(afterFailure.get("password_salt")).isEqualTo(user.passwordSalt());
    }

    private ResultActions login(String username, String password) throws Exception {
        return login(username, password, null);
    }

    private ResultActions login(String username, String password, String forwardedFor) throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        var request = post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return mockMvc.perform(request);
    }

    private ResultActions postRefresh(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("refreshToken", token))));
    }

    private String accessToken(String username, String password) throws Exception {
        MvcResult result = login(username, password)
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .path("accessToken")
            .asText();
    }

    private long userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private Map<String, Object> trackableRow(String username) {
        return jdbcTemplate.queryForMap("""
            SELECT sign_in_count, current_sign_in_at, last_sign_in_at, current_sign_in_ip, last_sign_in_ip,
                   updated_at, encrypted_password, password_salt
            FROM users WHERE username = ?
            """, username);
    }

    private Instant timestamp(Object value) {
        return ((Timestamp) value).toInstant();
    }

    private void deleteFixtureUsers() {
        for (LegacyUser user : LegacyAuthFixtures.users()) {
            jdbcTemplate.update("DELETE FROM users WHERE username = ?", user.username());
        }
    }

    private String flipLastCharacter(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        char first = token.charAt(signatureStart);
        return token.substring(0, signatureStart) + (first == 'A' ? 'B' : 'A')
            + token.substring(signatureStart + 1);
    }

    private String signedToken(long userId, String type, Instant expiresAt, Instant issuedAt) throws Exception {
        Map<String, Object> claims = Map.of(
            "sub", Long.toString(userId),
            "username", "legacy_plain",
            "admin", false,
            "typ", type,
            "iat", issuedAt.getEpochSecond(),
            "exp", expiresAt.getEpochSecond(),
            "jti", "expired-test-token"
        );
        String header = base64(objectMapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
        String payload = base64(objectMapper.writeValueAsBytes(claims));
        String signingInput = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII))
        );
    }

    private String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
