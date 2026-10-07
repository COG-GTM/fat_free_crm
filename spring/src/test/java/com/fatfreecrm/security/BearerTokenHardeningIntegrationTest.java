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
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Pins the bearer-token trust boundary: claims are never trusted for identity or roles, malformed
 * or unsigned tokens are rejected, and the public login/refresh routes ignore stale bearer headers.
 */
class BearerTokenHardeningIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String JWT_SECRET = "spring-test-secret-with-at-least-32-bytes";
    private static final String OTHER_SECRET = "a-different-secret-that-is-also-32-bytes";
    private static final String INSERT_USER = """
        INSERT INTO users (username, email, first_name, last_name, encrypted_password, password_salt, admin,
                           confirmed_at, suspended_at, sign_in_count, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, now(), now())
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
    void eachAccessTokenResolvesToItsOwnUserAndAdminFlag() throws Exception {
        LegacyUser plain = LegacyAuthFixtures.user("legacy_plain");
        LegacyUser admin = LegacyAuthFixtures.user("legacy_admin");

        me(accessToken(plain))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(userId(plain.username())))
            .andExpect(jsonPath("$.username").value(plain.username()))
            .andExpect(jsonPath("$.email").value(plain.email()))
            .andExpect(jsonPath("$.admin").value(false));
        me(accessToken(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(userId(admin.username())))
            .andExpect(jsonPath("$.username").value(admin.username()))
            .andExpect(jsonPath("$.admin").value(true));
    }

    @Test
    void forgedAdminAndUsernameClaimsAreIgnoredInFavourOfTheDatabaseRow() throws Exception {
        LegacyUser plain = LegacyAuthFixtures.user("legacy_plain");
        Map<String, Object> claims = accessClaims(userId(plain.username()));
        claims.put("admin", true);
        claims.put("username", "legacy_admin");

        me(sign(claims, JWT_SECRET))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("legacy_plain"))
            .andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void rejectsTokensForUnknownOrNonNumericSubjects() throws Exception {
        long unknownId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1000 FROM users", Long.class);
        Map<String, Object> unknown = accessClaims(unknownId);
        Map<String, Object> textual = accessClaims(1L);
        textual.put("sub", "legacy_plain");

        assertUnauthorizedProblem(me(sign(unknown, JWT_SECRET)));
        assertUnauthorizedProblem(me(sign(textual, JWT_SECRET)));
    }

    @Test
    void rejectsTokensWithoutTheAccessTypClaimOrWithAnUnexpectedTyp() throws Exception {
        long id = userId("legacy_plain");
        Map<String, Object> withoutTyp = accessClaims(id);
        withoutTyp.remove("typ");
        Map<String, Object> otherTyp = accessClaims(id);
        otherTyp.put("typ", "id");

        assertUnauthorizedProblem(me(sign(withoutTyp, JWT_SECRET)));
        assertUnauthorizedProblem(me(sign(otherTyp, JWT_SECRET)));
    }

    @Test
    void rejectsUnsignedAndForeignlySignedTokens() throws Exception {
        long id = userId("legacy_plain");
        String header = base64(objectMapper.writeValueAsBytes(Map.of("alg", "none", "typ", "JWT")));
        String payload = base64(objectMapper.writeValueAsBytes(accessClaims(id)));

        assertUnauthorizedProblem(me(header + "." + payload + "."));
        assertUnauthorizedProblem(me(header + "." + payload));
        assertUnauthorizedProblem(me(sign(accessClaims(id), OTHER_SECRET)));
        assertUnauthorizedProblem(me("not.a.jwt"));
        assertUnauthorizedProblem(mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Basic bGVnYWN5X3BsYWluOnBhc3N3b3Jk")));
    }

    @Test
    void rejectsAccessAndRefreshTokensOnceTheUserIsDeletedOrUnconfirmed() throws Exception {
        LegacyUser plain = LegacyAuthFixtures.user("legacy_plain");
        JsonNode tokens = login(plain);
        String access = tokens.path("accessToken").asText();
        String refresh = tokens.path("refreshToken").asText();

        jdbcTemplate.update("UPDATE users SET confirmed_at = NULL WHERE username = ?", plain.username());
        assertUnauthorizedProblem(me(access));
        assertUnauthorizedProblem(postRefresh(refresh));

        jdbcTemplate.update("DELETE FROM users WHERE username = ?", plain.username());
        assertUnauthorizedProblem(me(access));
        assertUnauthorizedProblem(postRefresh(refresh));
    }

    @Test
    void refreshIgnoresAStaleBearerHeaderLikeLogin() throws Exception {
        LegacyUser plain = LegacyAuthFixtures.user("legacy_plain");
        String refresh = login(plain).path("refreshToken").asText();

        // The resolver keys off getServletPath(), which a real container sets to the request path.
        mockMvc.perform(post("/api/v1/auth/refresh")
                .servletPath("/api/v1/auth/refresh")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty());
        mockMvc.perform(post("/api/v1/auth/login")
                .servletPath("/api/v1/auth/login")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", plain.username(), "password", plain.password()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void onlyPostIsPublicOnTheLoginAndRefreshRoutes() throws Exception {
        assertUnauthorizedProblem(mockMvc.perform(get("/api/v1/auth/login")));
        assertUnauthorizedProblem(mockMvc.perform(get("/api/v1/auth/refresh")));
        assertUnauthorizedProblem(mockMvc.perform(get("/api/v1/users/me")));
    }

    @Test
    void malformedLoginBodiesAreBadRequestsThatDoNotTouchTrackableColumns() throws Exception {
        Map<String, Object> before = trackableRow("legacy_plain");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"legacy_plain\",\"password\":"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(400));
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.TEXT_PLAIN)
                .content("username=legacy_plain&password=PlainAsciiPassword42"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        assertThat(trackableRow("legacy_plain")).isEqualTo(before);
    }

    @Test
    void passwordsAreCompareExactlyWithoutTrimmingOrCaseFolding() throws Exception {
        LegacyUser spaces = LegacyAuthFixtures.user("legacy_spaces");
        LegacyUser plain = LegacyAuthFixtures.user("legacy_plain");

        login(spaces.username(), spaces.password().strip()).andExpect(status().isUnauthorized());
        login(plain.username(), plain.password().toLowerCase(java.util.Locale.ROOT))
            .andExpect(status().isUnauthorized());
        login(plain.username(), plain.password() + " ").andExpect(status().isUnauthorized());
        login(plain.username(), plain.password()).andExpect(status().isOk());
    }

    private void assertUnauthorizedProblem(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(401));
    }

    private ResultActions me(String bearerToken) throws Exception {
        return mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken));
    }

    private JsonNode login(LegacyUser user) throws Exception {
        return objectMapper.readTree(login(user.username(), user.password())
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private ResultActions login(String username, String password) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
    }

    private String accessToken(LegacyUser user) throws Exception {
        return login(user).path("accessToken").asText();
    }

    private ResultActions postRefresh(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("refreshToken", token))));
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

    private void deleteFixtureUsers() {
        for (LegacyUser user : LegacyAuthFixtures.users()) {
            jdbcTemplate.update("DELETE FROM users WHERE username = ?", user.username());
        }
    }

    private Map<String, Object> accessClaims(long userId) {
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", Long.toString(userId));
        claims.put("username", "legacy_plain");
        claims.put("admin", false);
        claims.put("typ", "access");
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(900).getEpochSecond());
        claims.put("jti", "hardening-test-token");
        return claims;
    }

    private String sign(Map<String, Object> claims, String secret) throws Exception {
        String header = base64(objectMapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
        String payload = base64(objectMapper.writeValueAsBytes(claims));
        String signingInput = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return signingInput + "." + base64(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
