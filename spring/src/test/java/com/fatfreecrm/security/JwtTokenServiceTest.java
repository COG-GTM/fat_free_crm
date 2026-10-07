package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.security.JwtTokenService.TokenPair;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class JwtTokenServiceTest {

    private static final String SECRET = "spring-test-secret-with-at-least-32-bytes";
    private static final Instant ISSUED_AT = Instant.parse("2026-01-01T12:00:00Z");

    private final JwtProperties properties = new JwtProperties(SECRET, Duration.ofMinutes(15), Duration.ofDays(14));
    private final SecretKey key = key(SECRET);
    private final User user = FfcrmUserDetailsTest.user(42L, "legacy_admin", true, ISSUED_AT, null);

    @Test
    void issuesAccessAndRefreshTokensWithTheDocumentedClaimsAndTtls() {
        JwtTokenService service = new JwtTokenService(properties, key, Clock.fixed(ISSUED_AT, ZoneOffset.UTC));

        TokenPair pair = service.issue(user);
        Jwt access = rawClaims(key, pair.accessToken());
        Jwt refresh = rawClaims(key, pair.refreshToken());

        assertThat(pair.expiresIn()).isEqualTo(900);
        assertThat(access.getHeaders()).containsEntry("alg", "HS256");
        assertThat(access.getSubject()).isEqualTo("42");
        assertThat(access.getClaimAsString("username")).isEqualTo("legacy_admin");
        assertThat(access.getClaimAsBoolean("admin")).isTrue();
        assertThat(access.getClaimAsString("typ")).isEqualTo("access");
        assertThat(access.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(access.getExpiresAt()).isEqualTo(ISSUED_AT.plus(15, ChronoUnit.MINUTES));
        assertThat(access.getId()).isNotBlank();

        assertThat(refresh.getSubject()).isEqualTo("42");
        assertThat(refresh.getClaimAsString("typ")).isEqualTo("refresh");
        assertThat(refresh.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(refresh.getExpiresAt()).isEqualTo(ISSUED_AT.plus(14, ChronoUnit.DAYS));
        assertThat(refresh.getId()).isNotBlank().isNotEqualTo(access.getId());
    }

    @Test
    void reflectsConfiguredTtlsInExpiryAndExpiresIn() {
        JwtProperties custom = new JwtProperties(SECRET, Duration.ofMinutes(5), Duration.ofHours(1));
        JwtTokenService service = new JwtTokenService(custom, key, Clock.fixed(ISSUED_AT, ZoneOffset.UTC));

        TokenPair pair = service.issue(user);

        assertThat(pair.expiresIn()).isEqualTo(300);
        assertThat(rawClaims(key, pair.accessToken()).getExpiresAt()).isEqualTo(ISSUED_AT.plus(5, ChronoUnit.MINUTES));
        assertThat(rawClaims(key, pair.refreshToken()).getExpiresAt()).isEqualTo(ISSUED_AT.plus(1, ChronoUnit.HOURS));
    }

    @Test
    void everyIssuedTokenCarriesAFreshJtiAndReflectsTheNonAdminFlag() {
        User member = FfcrmUserDetailsTest.user(7L, "legacy_plain", false, ISSUED_AT, null);
        JwtTokenService service = new JwtTokenService(properties, key, Clock.fixed(ISSUED_AT, ZoneOffset.UTC));

        Jwt first = rawClaims(key, service.issue(member).accessToken());
        Jwt second = rawClaims(key, service.issue(member).accessToken());

        assertThat(first.getClaimAsBoolean("admin")).isFalse();
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void verifyRefreshAcceptsCurrentRefreshTokensOnly() {
        JwtTokenService service = new JwtTokenService(properties, key, Clock.systemUTC());
        TokenPair pair = service.issue(user);

        assertThat(service.verifyRefresh(pair.refreshToken()).getSubject()).isEqualTo("42");
        assertThatThrownBy(() -> service.verifyRefresh(pair.accessToken())).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service.verifyRefresh("not.a.jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void verifyRefreshRejectsTokensSignedWithAnotherSecret() {
        JwtTokenService service = new JwtTokenService(properties, key, Clock.systemUTC());
        JwtTokenService other = new JwtTokenService(
            new JwtProperties("another-secret-that-is-also-32-bytes-long", null, null),
            key("another-secret-that-is-also-32-bytes-long"),
            Clock.systemUTC()
        );

        String foreignRefresh = other.issue(user).refreshToken();

        assertThatThrownBy(() -> service.verifyRefresh(foreignRefresh)).isInstanceOf(JwtException.class);
    }

    @Test
    void verifyRefreshRejectsExpiredRefreshTokens() {
        Instant longAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        JwtTokenService issuer = new JwtTokenService(properties, key, Clock.fixed(longAgo, ZoneOffset.UTC));
        JwtTokenService verifier = new JwtTokenService(properties, key, Clock.systemUTC());

        String expiredRefresh = issuer.issue(user).refreshToken();

        assertThatThrownBy(() -> verifier.verifyRefresh(expiredRefresh)).isInstanceOf(JwtException.class);
    }

    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private static Jwt rawClaims(SecretKey key, String token) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        return decoder.decode(token);
    }
}
