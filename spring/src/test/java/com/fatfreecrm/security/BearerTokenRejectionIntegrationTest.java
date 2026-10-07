package com.fatfreecrm.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Pins what a bearer token must prove before any {@code /api/v1} request is accepted: the configured
 * secret, the pinned algorithm, an {@code access} type, a numeric subject, and a live confirmed user row.
 */
class BearerTokenRejectionIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String JWT_SECRET = "spring-test-secret-with-at-least-32-bytes";
    private static final String OTHER_SECRET = "a-completely-different-secret-of-32-bytes+";
    private static final String PREFIX = "bearer_it_";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long memberId;
    private long adminId;
    private long unconfirmedId;

    @BeforeEach
    void insertUsers() {
        deleteUsers();
        memberId = insertUser(PREFIX + "member", false, true);
        adminId = insertUser(PREFIX + "admin", true, true);
        unconfirmedId = insertUser(PREFIX + "unconfirmed", false, false);
    }

    @AfterEach
    void deleteUsers() {
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE ?", PREFIX + "%");
    }

    @Test
    void acceptsAProperlySignedAccessTokenAndIdentifiesOnlyItsOwnSubject() throws Exception {
        me(accessToken(memberId, JWT_SECRET, UnaryOperator.identity()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(memberId))
            .andExpect(jsonPath("$.username").value(PREFIX + "member"))
            .andExpect(jsonPath("$.admin").value(false));
        me(accessToken(adminId, JWT_SECRET, UnaryOperator.identity()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(adminId))
            .andExpect(jsonPath("$.admin").value(true));
    }

    @Test
    void adminClaimInTheTokenIsIgnoredInFavourOfTheDatabaseRow() throws Exception {
        String forgedAdmin = accessToken(memberId, JWT_SECRET, claims -> claims.claim("admin", true));

        me(forgedAdmin)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(memberId))
            .andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void usernameClaimInTheTokenCannotImpersonateAnotherUser() throws Exception {
        String forged = accessToken(memberId, JWT_SECRET, claims -> claims.claim("username", PREFIX + "admin"));

        me(forged)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(memberId))
            .andExpect(jsonPath("$.username").value(PREFIX + "member"));
    }

    @Test
    void rejectsTokensSignedWithADifferentSecret() throws Exception {
        expectUnauthorized(me(accessToken(memberId, OTHER_SECRET, UnaryOperator.identity())));
    }

    @Test
    void rejectsUnsignedTokens() throws Exception {
        PlainJWT plain = new PlainJWT(claims(memberId).build());
        expectUnauthorized(me(plain.serialize()));
    }

    @Test
    void rejectsTokensWithoutAnAccessType() throws Exception {
        expectUnauthorized(me(accessToken(memberId, JWT_SECRET, claims -> claims.claim("typ", null))));
        expectUnauthorized(me(accessToken(memberId, JWT_SECRET, claims -> claims.claim("typ", "refresh"))));
        expectUnauthorized(me(accessToken(memberId, JWT_SECRET, claims -> claims.claim("typ", "ACCESS"))));
    }

    @Test
    void rejectsTokensWhoseSubjectIsNotAUserId() throws Exception {
        expectUnauthorized(me(accessToken(memberId, JWT_SECRET, claims -> claims.subject(PREFIX + "member"))));
        expectUnauthorized(me(accessToken(memberId, JWT_SECRET, claims -> claims.subject(null))));
        expectUnauthorized(me(accessToken(memberId, JWT_SECRET, claims -> claims.subject("-1"))));
    }

    @Test
    void rejectsTokensForUsersThatDoNotExistOrAreNotConfirmed() throws Exception {
        long missingId = jdbcTemplate.queryForObject("SELECT max(id) + 1000 FROM users", Long.class);
        expectUnauthorized(me(accessToken(missingId, JWT_SECRET, UnaryOperator.identity())));
        expectUnauthorized(me(accessToken(unconfirmedId, JWT_SECRET, UnaryOperator.identity())));
    }

    @Test
    void rejectsTokensOnceTheUserRowIsDeleted() throws Exception {
        String token = accessToken(memberId, JWT_SECRET, UnaryOperator.identity());
        me(token).andExpect(status().isOk());

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", memberId);

        expectUnauthorized(me(token));
    }

    @Test
    void rejectsNonBearerAuthorizationSchemesAndMalformedBearerValues() throws Exception {
        expectUnauthorized(mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Basic bGVnYWN5X3BsYWluOnBhc3N3b3Jk")));
        expectUnauthorized(mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer")));
        expectUnauthorized(mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer not a token")));
    }

    @Test
    void protectedRoutesOutsideAuthRequireAccessTokensToo() throws Exception {
        String token = accessToken(memberId, JWT_SECRET, UnaryOperator.identity());
        String refresh = accessToken(memberId, JWT_SECRET, claims -> claims.claim("typ", "refresh"));

        expectUnauthorized(mockMvc.perform(post("/api/v1/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + refresh)));
        mockMvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isNoContent());
        expectUnauthorized(mockMvc.perform(get("/api/v1/accounts")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + refresh)));
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void expectUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(401));
    }

    private long insertUser(String username, boolean admin, boolean confirmed) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users (username, email, encrypted_password, password_salt, admin, confirmed_at,
                               sign_in_count, created_at, updated_at)
            VALUES (?, ?, 'hash', 'salt', ?, ?, 0, now(), now())
            RETURNING id
            """, Long.class, username, username + "@example.com", admin,
            confirmed ? Timestamp.from(Instant.now()) : null);
    }

    private static JWTClaimsSet.Builder claims(long userId) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
            .subject(Long.toString(userId))
            .claim("username", "whatever")
            .claim("admin", false)
            .claim("typ", "access")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString());
    }

    private static String accessToken(
        long userId,
        String secret,
        UnaryOperator<JWTClaimsSet.Builder> customizer
    ) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), customizer.apply(claims(userId)).build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
