package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.security.JwtTokenService.TokenPair;
import com.fatfreecrm.support.TestUsers;
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

    private static final String SECRET = "unit-test-secret-with-at-least-32-bytes";
    private static final String OTHER_SECRET = "another-unit-test-secret-with-32-bytes";
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private final SecretKey key = secretKey(SECRET);
    private final JwtProperties properties = new JwtProperties(SECRET, Duration.ofMinutes(15), Duration.ofDays(14));
    private final JwtTokenService service = new JwtTokenService(properties, key, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void issuesAccessAndRefreshTokensCarryingTheDocumentedClaims() {
        User user = TestUsers.activeUser(42L, "legacy_admin", true);

        TokenPair pair = service.issue(user);

        assertThat(pair.expiresIn()).isEqualTo(900);
        Jwt access = decodeWithoutValidation(pair.accessToken());
        assertThat(access.getHeaders()).containsEntry("alg", "HS256");
        assertThat(access.getClaims().keySet())
            .containsExactlyInAnyOrder("sub", "username", "admin", "typ", "iat", "exp", "jti");
        assertThat(access.getSubject()).isEqualTo("42");
        assertThat(access.getClaimAsString("username")).isEqualTo("legacy_admin");
        assertThat(access.getClaimAsBoolean("admin")).isTrue();
        assertThat(access.getClaimAsString("typ")).isEqualTo("access");
        assertThat(access.getIssuedAt()).isEqualTo(NOW);
        assertThat(access.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(access.getId()).isNotBlank();

        Jwt refresh = decodeWithoutValidation(pair.refreshToken());
        assertThat(refresh.getSubject()).isEqualTo("42");
        assertThat(refresh.getClaimAsString("typ")).isEqualTo("refresh");
        assertThat(refresh.getClaimAsBoolean("admin")).isTrue();
        assertThat(refresh.getIssuedAt()).isEqualTo(NOW);
        assertThat(refresh.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
        assertThat(refresh.getId()).isNotBlank().isNotEqualTo(access.getId());
    }

    @Test
    void reportsTheAdminFlagAndNumericSubjectOfNonAdminUsers() {
        Jwt access = decodeWithoutValidation(
            service.issue(TestUsers.activeUser(7L, "legacy_plain", false)).accessToken());

        assertThat(access.getSubject()).isEqualTo("7");
        assertThat(access.getClaimAsBoolean("admin")).isFalse();
        assertThat(access.getClaimAsString("username")).isEqualTo("legacy_plain");
    }

    @Test
    void usesTheConfiguredTtlsAndExposesTheAccessTtlAsExpiresIn() {
        JwtProperties custom = new JwtProperties(SECRET, Duration.ofMinutes(5), Duration.ofHours(2));
        JwtTokenService customService = new JwtTokenService(custom, key, Clock.fixed(NOW, ZoneOffset.UTC));

        TokenPair pair = customService.issue(TestUsers.activeUser(1L, "legacy_plain", false));

        assertThat(pair.expiresIn()).isEqualTo(300);
        assertThat(decodeWithoutValidation(pair.accessToken()).getExpiresAt())
            .isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(decodeWithoutValidation(pair.refreshToken()).getExpiresAt())
            .isEqualTo(NOW.plus(Duration.ofHours(2)));
    }

    @Test
    void issuesAUniqueJtiForEveryToken() {
        User user = TestUsers.activeUser(1L, "legacy_plain", false);

        TokenPair first = service.issue(user);
        TokenPair second = service.issue(user);

        assertThat(decodeWithoutValidation(first.accessToken()).getId())
            .isNotEqualTo(decodeWithoutValidation(second.accessToken()).getId());
        assertThat(decodeWithoutValidation(first.refreshToken()).getId())
            .isNotEqualTo(decodeWithoutValidation(second.refreshToken()).getId());
    }

    @Test
    void verifyRefreshAcceptsRefreshTokensAndRejectsAccessTokens() {
        TokenPair pair = service.issue(TestUsers.activeUser(42L, "legacy_plain", false));

        Jwt refresh = service.verifyRefresh(pair.refreshToken());

        assertThat(refresh.getSubject()).isEqualTo("42");
        assertThat(refresh.getClaimAsString("typ")).isEqualTo("refresh");
        assertThatThrownBy(() -> service.verifyRefresh(pair.accessToken())).isInstanceOf(JwtException.class);
    }

    @Test
    void verifyRefreshRejectsTokensSignedWithAnotherSecret() {
        JwtTokenService otherService = new JwtTokenService(
            new JwtProperties(OTHER_SECRET, null, null),
            secretKey(OTHER_SECRET),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        TokenPair foreign = otherService.issue(TestUsers.activeUser(42L, "legacy_plain", false));

        assertThatThrownBy(() -> service.verifyRefresh(foreign.refreshToken())).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service.verifyRefresh("not-a-jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void verifyRefreshRejectsExpiredRefreshTokens() {
        Clock fifteenDaysAgo = Clock.fixed(NOW.minus(Duration.ofDays(15)), ZoneOffset.UTC);
        JwtTokenService pastService = new JwtTokenService(properties, key, fifteenDaysAgo);
        TokenPair expired = pastService.issue(TestUsers.activeUser(42L, "legacy_plain", false));

        assertThatThrownBy(() -> service.verifyRefresh(expired.refreshToken())).isInstanceOf(JwtException.class);
    }

    private Jwt decodeWithoutValidation(String token) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        return decoder.decode(token);
    }

    private static SecretKey secretKey(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
