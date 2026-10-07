package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.security.JwtTokenService.TokenPair;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtTokenServiceTest {

    private static final String SECRET = "spring-test-secret-with-at-least-32-bytes";
    private static final Instant NOW = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);

    private final JwtProperties properties = new JwtProperties(SECRET, Duration.ofMinutes(15), Duration.ofDays(14));
    private final JwtTokenService service =
        new JwtTokenService(properties, key(SECRET), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void issuesHs256AccessAndRefreshTokensWithTheDocumentedClaims() throws Exception {
        User user = FfcrmUserDetailsTest.user(42L, "legacy_admin", true, Instant.EPOCH, null);

        TokenPair pair = service.issue(user);

        assertThat(pair.expiresIn()).isEqualTo(900);
        SignedJWT access = SignedJWT.parse(pair.accessToken());
        SignedJWT refresh = SignedJWT.parse(pair.refreshToken());
        assertThat(access.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
        assertThat(refresh.getHeader().getAlgorithm().getName()).isEqualTo("HS256");

        JWTClaimsSet accessClaims = access.getJWTClaimsSet();
        assertThat(accessClaims.getSubject()).isEqualTo("42");
        assertThat(accessClaims.getStringClaim("username")).isEqualTo("legacy_admin");
        assertThat(accessClaims.getBooleanClaim("admin")).isTrue();
        assertThat(accessClaims.getStringClaim("typ")).isEqualTo("access");
        assertThat(accessClaims.getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(accessClaims.getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(accessClaims.getJWTID()).isNotBlank();

        JWTClaimsSet refreshClaims = refresh.getJWTClaimsSet();
        assertThat(refreshClaims.getSubject()).isEqualTo("42");
        assertThat(refreshClaims.getStringClaim("typ")).isEqualTo("refresh");
        assertThat(refreshClaims.getBooleanClaim("admin")).isTrue();
        assertThat(refreshClaims.getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofDays(14)));
        assertThat(refreshClaims.getJWTID()).isNotEqualTo(accessClaims.getJWTID());
    }

    @Test
    void expiresInFollowsTheConfiguredAccessTokenTtl() {
        JwtTokenService shortLived = new JwtTokenService(
            new JwtProperties(SECRET, Duration.ofMinutes(2), Duration.ofHours(1)),
            key(SECRET),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThat(shortLived.issue(FfcrmUserDetailsTest.user(1L, "u", false, Instant.EPOCH, null)).expiresIn())
            .isEqualTo(120);
    }

    @Test
    void everyIssuedTokenHasAUniqueJti() throws Exception {
        User user = FfcrmUserDetailsTest.user(1L, "u", false, Instant.EPOCH, null);

        String first = SignedJWT.parse(service.issue(user).accessToken()).getJWTClaimsSet().getJWTID();
        String second = SignedJWT.parse(service.issue(user).accessToken()).getJWTClaimsSet().getJWTID();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void verifyRefreshAcceptsRefreshTokensAndExposesTheSubject() {
        TokenPair pair = service.issue(FfcrmUserDetailsTest.user(99L, "u", false, Instant.EPOCH, null));

        Jwt jwt = service.verifyRefresh(pair.refreshToken());

        assertThat(jwt.getSubject()).isEqualTo("99");
        assertThat(jwt.getClaimAsString("typ")).isEqualTo("refresh");
    }

    @Test
    void verifyRefreshRejectsAccessTokensEvenThoughTheyAreValidlySigned() {
        TokenPair pair = service.issue(FfcrmUserDetailsTest.user(99L, "u", false, Instant.EPOCH, null));

        assertThatThrownBy(() -> service.verifyRefresh(pair.accessToken())).isInstanceOf(JwtException.class);
    }

    @Test
    void verifyRefreshRejectsTokensSignedWithADifferentSecret() {
        JwtTokenService other = new JwtTokenService(
            properties,
            key("another-secret-that-is-also-at-least-32-bytes"),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        TokenPair pair = other.issue(FfcrmUserDetailsTest.user(99L, "u", false, Instant.EPOCH, null));

        assertThatThrownBy(() -> service.verifyRefresh(pair.refreshToken())).isInstanceOf(JwtException.class);
    }

    @Test
    void verifyRefreshRejectsExpiredRefreshTokens() {
        JwtTokenService past = new JwtTokenService(
            properties,
            key(SECRET),
            Clock.fixed(NOW.minus(Duration.ofDays(15)), ZoneOffset.UTC)
        );
        TokenPair pair = past.issue(FfcrmUserDetailsTest.user(99L, "u", false, Instant.EPOCH, null));

        assertThatThrownBy(() -> service.verifyRefresh(pair.refreshToken())).isInstanceOf(JwtException.class);
    }

    @Test
    void verifyRefreshRejectsGarbage() {
        assertThatThrownBy(() -> service.verifyRefresh("not.a.jwt")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service.verifyRefresh("")).isInstanceOf(JwtException.class);
    }

    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
