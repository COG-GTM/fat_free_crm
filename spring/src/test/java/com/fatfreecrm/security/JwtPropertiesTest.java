package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

    @Test
    void requiresA32ByteSecretAndIncludesTheEnvironmentVariableInTheError() {
        assertThatThrownBy(() -> new JwtProperties(null, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FFCRM_JWT_SECRET");
        assertThatThrownBy(() -> new JwtProperties("", Duration.ofMinutes(15), Duration.ofDays(14)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FFCRM_JWT_SECRET");
        assertThatThrownBy(() -> new JwtProperties("a".repeat(31), null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FFCRM_JWT_SECRET");
    }

    @Test
    void acceptsA32ByteSecretAndAppliesDefaultTokenTtls() {
        JwtProperties properties = new JwtProperties("a".repeat(32), null, null);

        assertThat(properties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.refreshTokenTtl()).isEqualTo(Duration.ofDays(14));
    }

    @Test
    void rejectsNonPositiveAccessTokenTtls() {
        for (Duration ttl : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThatThrownBy(() -> new JwtProperties("a".repeat(32), ttl, Duration.ofDays(14)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FFCRM_JWT_ACCESS_TTL")
                .hasMessageContaining("positive");
        }
    }

    @Test
    void rejectsNonPositiveRefreshTokenTtls() {
        for (Duration ttl : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThatThrownBy(() -> new JwtProperties("a".repeat(32), Duration.ofMinutes(15), ttl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FFCRM_JWT_REFRESH_TTL")
                .hasMessageContaining("positive");
        }
    }
}
