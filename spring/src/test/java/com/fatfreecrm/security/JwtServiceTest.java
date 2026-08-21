package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtServiceTest {

    private static final String SECRET = "unit-test-hmac-secret-that-is-long-enough";

    private final CrmUserDetails user = userDetails();

    private static CrmUserDetails userDetails() {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", 7L);
        user.setUsername("legacy_user");
        user.setEmail("legacy_user@example.com");
        return new CrmUserDetails(user, java.util.List.of());
    }

    private static SecurityProperties properties(String secret, Duration accessTtl) {
        SecurityProperties properties = new SecurityProperties();
        properties.getJwt().setSecret(secret);
        properties.getJwt().setAccessTokenTtl(accessTtl);
        properties.getJwt().setRefreshTokenTtl(Duration.ofHours(1));
        return properties;
    }

    private static JwtService service() {
        return new JwtService(properties(SECRET, Duration.ofMinutes(5)));
    }

    @Test
    void rejectsSecretsTooShortForHs256() {
        SecurityProperties properties = properties("too-short", Duration.ofMinutes(5));

        assertThatThrownBy(() -> new JwtService(properties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void accessTokenCarriesSubjectAndUserId() {
        JwtService service = service();

        Claims claims = service.parse(service.issueAccessToken(user), JwtService.TOKEN_TYPE_ACCESS);

        assertThat(claims.getSubject()).isEqualTo("legacy_user");
        assertThat(claims.get("uid", Number.class).longValue()).isEqualTo(7L);
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void refreshTokenIsNotAcceptedAsAnAccessTokenAndViceVersa() {
        JwtService service = service();
        String access = service.issueAccessToken(user);
        String refresh = service.issueRefreshToken(user);

        assertThatThrownBy(() -> service.parse(refresh, JwtService.TOKEN_TYPE_ACCESS))
            .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service.parse(access, JwtService.TOKEN_TYPE_REFRESH))
            .isInstanceOf(JwtException.class);
        assertThat(service.parse(refresh, JwtService.TOKEN_TYPE_REFRESH).getSubject()).isEqualTo("legacy_user");
    }

    @Test
    void rejectsTokensSignedWithAnotherKey() {
        String foreign = service().issueAccessToken(user);
        JwtService other = new JwtService(properties("a-completely-different-secret-key-value",
            Duration.ofMinutes(5)));

        assertThatThrownBy(() -> other.parse(foreign, JwtService.TOKEN_TYPE_ACCESS))
            .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredTokens() {
        JwtService service = new JwtService(properties(SECRET, Duration.ofSeconds(-30)));
        String expired = service.issueAccessToken(user);

        assertThatThrownBy(() -> service.parse(expired, JwtService.TOKEN_TYPE_ACCESS))
            .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTamperedTokens() {
        JwtService service = service();
        String token = service.issueAccessToken(user);
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "AAAA";

        assertThatThrownBy(() -> service.parse(tampered, JwtService.TOKEN_TYPE_ACCESS))
            .isInstanceOf(JwtException.class);
    }
}
