package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fatfreecrm.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class FfcrmJwtAuthenticationConverterTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final FfcrmJwtAuthenticationConverter converter = new FfcrmJwtAuthenticationConverter(userRepository);

    @Test
    void reloadsTheUserAndBuildsTheAuthenticatedPrincipalFromTheDatabaseRow() {
        when(userRepository.findById(42L))
            .thenReturn(Optional.of(FfcrmUserDetailsTest.user(42L, "legacy_plain", false, Instant.EPOCH, null)));

        FfcrmAuthenticationToken token = converter.convert(jwt("42", true));

        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getName()).isEqualTo("42");
        assertThat(token.getAuthenticatedUser()).isEqualTo(new AuthenticatedUser(42L, "legacy_plain", false));
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
    }

    @Test
    void adminAuthorityComesFromTheDatabaseNotFromTheTokenClaim() {
        when(userRepository.findById(7L))
            .thenReturn(Optional.of(FfcrmUserDetailsTest.user(7L, "legacy_admin", true, Instant.EPOCH, null)));

        FfcrmAuthenticationToken token = converter.convert(jwt("7", false));

        assertThat(token.getAuthenticatedUser().admin()).isTrue();
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void rejectsTokensWhoseSubjectIsNotANumericUserId() {
        assertThatThrownBy(() -> converter.convert(jwt("legacy_plain", false)))
            .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void rejectsTokensForUsersThatNoLongerExist() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> converter.convert(jwt("404", false)))
            .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void rejectsTokensForUnconfirmedUsers() {
        when(userRepository.findById(1L))
            .thenReturn(Optional.of(FfcrmUserDetailsTest.user(1L, "pending", false, null, null)));

        assertThatThrownBy(() -> converter.convert(jwt("1", false)))
            .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void rejectsTokensForSuspendedUsers() {
        when(userRepository.findById(2L))
            .thenReturn(Optional.of(FfcrmUserDetailsTest.user(2L, "banned", false, Instant.EPOCH, Instant.EPOCH)));

        assertThatThrownBy(() -> converter.convert(jwt("2", false)))
            .isInstanceOf(InvalidBearerTokenException.class);
    }

    private static Jwt jwt(String subject, boolean adminClaim) {
        return Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .subject(subject)
            .claim("username", "whatever")
            .claim("admin", adminClaim)
            .claim("typ", "access")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    }
}
