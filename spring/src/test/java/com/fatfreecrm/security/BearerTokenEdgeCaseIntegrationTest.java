package com.fatfreecrm.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Access-control edge cases for the bearer-token boundary beyond the happy path and the expired/tampered
 * cases already covered by {@link AuthenticationIntegrationTest}.
 */
class BearerTokenEdgeCaseIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String JWT_SECRET = "spring-test-secret-with-at-least-32-bytes";
    private static final String UNAUTHENTICATED_DETAIL = "Authentication is required to access this resource.";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private long plainUserId;
    private long adminUserId;

    @BeforeEach
    void insertUsers() {
        removeUsers();
        plainUserId = insert(LegacyAuthFixtures.user("legacy_plain"));
        adminUserId = insert(LegacyAuthFixtures.user("legacy_admin"));
    }

    @AfterEach
    void removeUsers() {
        jdbcTemplate.update("DELETE FROM users WHERE username IN ('legacy_plain', 'legacy_admin')");
    }

    @Test
    void rejectsAccessTokensWithoutATypClaim() throws Exception {
        Map<String, Object> claims = claims(plainUserId);
        claims.remove("typ");

        assertUnauthenticated(me(sign(claims)));
    }

    @Test
    void rejectsAccessTokensWithAnUnknownTypClaim() throws Exception {
        Map<String, Object> claims = claims(plainUserId);
        claims.put("typ", "id");

        assertUnauthenticated(me(sign(claims)));
    }

    @Test
    void rejectsAccessTokensWhoseSubjectIsNotAUserId() throws Exception {
        Map<String, Object> claims = claims(plainUserId);
        claims.put("sub", "legacy_plain");

        assertUnauthenticated(me(sign(claims)));
    }

    @Test
    void rejectsAccessTokensForDeletedUsers() throws Exception {
        String token = sign(claims(plainUserId));
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", plainUserId);

        assertUnauthenticated(me(token));
    }

    @Test
    void rejectsAccessTokensOnceTheUserIsNoLongerConfirmed() throws Exception {
        String token = sign(claims(plainUserId));
        me(token).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE users SET confirmed_at = NULL WHERE id = ?", plainUserId);

        assertUnauthenticated(me(token));
    }

    @Test
    void rejectsUnsignedTokensAndTokensSignedWithAnotherSecret() throws Exception {
        Map<String, Object> claims = claims(plainUserId);
        String unsigned = base64(objectMapper.writeValueAsBytes(Map.of("alg", "none", "typ", "JWT")))
            + "." + base64(objectMapper.writeValueAsBytes(claims)) + ".";
        assertUnauthenticated(me(unsigned));

        assertUnauthenticated(me(sign(claims, "a-completely-different-secret-of-32-bytes!")));
    }

    @Test
    void rejectsNonBearerAuthorizationSchemesAndMalformedBearerHeaders() throws Exception {
        LegacyUser plain = LegacyAuthFixtures.user("legacy_plain");
        String basic = Base64.getEncoder().encodeToString(
            (plain.username() + ":" + plain.password()).getBytes(StandardCharsets.UTF_8));

        assertUnauthenticated(mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)));
        assertUnauthenticated(mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer")));
        assertUnauthenticated(mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer not a token")));
        assertUnauthenticated(mockMvc.perform(get("/api/v1/users/me")
            .queryParam("access_token", sign(claims(plainUserId)))));
    }

    @Test
    void onlyPostIsPublicOnTheLoginAndRefreshRoutes() throws Exception {
        assertUnauthenticated(mockMvc.perform(get("/api/v1/auth/login")));
        assertUnauthenticated(mockMvc.perform(get("/api/v1/auth/refresh")));
        assertUnauthenticated(mockMvc.perform(post("/api/v1/auth/logout")));
    }

    /**
     * The bearer-token resolver bypass keys off {@code HttpServletRequest#getServletPath()}, which the embedded
     * container populates with the full path for the default {@code /} DispatcherServlet mapping. MockMvc leaves
     * it empty unless set explicitly, so these requests set it the way the container would.
     */
    @Test
    void loginAndRefreshIgnoreAnInvalidBearerHeaderBecauseTheBodyCarriesTheCredential() throws Exception {
        LegacyUser plain = LegacyAuthFixtures.user("legacy_plain");
        String body = mockMvc.perform(post("/api/v1/auth/login")
                .servletPath("/api/v1/auth/login")
                .header(HttpHeaders.AUTHORIZATION, "Bearer garbage")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", plain.username(), "password", plain.password()))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String refreshToken = objectMapper.readTree(body).path("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/refresh")
                .servletPath("/api/v1/auth/refresh")
                .header(HttpHeaders.AUTHORIZATION, "Bearer garbage")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void authenticatedRequestsToUnknownRoutesGetNotFoundRatherThanUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + sign(claims(plainUserId))))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void adminFlagInTheCurrentUserResponseComesFromTheDatabaseNotTheToken() throws Exception {
        Map<String, Object> forgedAdmin = claims(plainUserId);
        forgedAdmin.put("admin", true);
        forgedAdmin.put("username", "legacy_admin");
        me(sign(forgedAdmin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(plainUserId))
            .andExpect(jsonPath("$.username").value("legacy_plain"))
            .andExpect(jsonPath("$.admin").value(false));

        Map<String, Object> demotedAdmin = claims(adminUserId);
        demotedAdmin.put("admin", false);
        me(sign(demotedAdmin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(adminUserId))
            .andExpect(jsonPath("$.username").value("legacy_admin"))
            .andExpect(jsonPath("$.admin").value(true));
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static void assertUnauthenticated(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.title").value("Unauthorized"))
            .andExpect(jsonPath("$.detail").value(UNAUTHENTICATED_DETAIL));
    }

    private long insert(LegacyUser user) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users (username, email, first_name, last_name, encrypted_password, password_salt, admin,
                               confirmed_at, suspended_at, sign_in_count, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 0, now(), now())
            RETURNING id
            """,
            Long.class,
            user.username(), user.email(), user.firstName(), user.lastName(),
            user.encryptedPassword(), user.passwordSalt(), user.admin(), Timestamp.from(Instant.now()));
    }

    private static Map<String, Object> claims(long userId) {
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", Long.toString(userId));
        claims.put("username", "legacy_plain");
        claims.put("admin", false);
        claims.put("typ", "access");
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(300).getEpochSecond());
        claims.put("jti", "edge-case-token");
        return claims;
    }

    private String sign(Map<String, Object> claims) throws Exception {
        return sign(claims, JWT_SECRET);
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
