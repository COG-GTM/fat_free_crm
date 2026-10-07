package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.support.TestUsers;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

@ExtendWith(MockitoExtension.class)
class FfcrmJwtAuthenticationConverterTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private FfcrmJwtAuthenticationConverter converter;

    @Test
    void reloadsTheUserAndDerivesAuthoritiesFromTheDatabaseNotTheClaims() {
        given(userRepository.findById(42L)).willReturn(Optional.of(TestUsers.activeUser(42L, "legacy_plain", false)));
        Jwt forged = jwt("42").claim("admin", true).claim("username", "legacy_admin").build();

        FfcrmAuthenticationToken token = converter.convert(forged);

        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getName()).isEqualTo("42");
        assertThat(token.getToken()).isSameAs(forged);
        assertThat(token.getAuthenticatedUser()).isEqualTo(new AuthenticatedUser(42L, "legacy_plain", false));
        assertThat(token.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_USER");
    }

    @Test
    void grantsRoleAdminWhenTheDatabaseRowIsAdmin() {
        given(userRepository.findById(7L)).willReturn(Optional.of(TestUsers.activeUser(7L, "legacy_admin", true)));

        FfcrmAuthenticationToken token = converter.convert(jwt("7").claim("admin", false).build());

        assertThat(token.getAuthenticatedUser().admin()).isTrue();
        assertThat(token.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void rejectsNonNumericSubjectsWithoutQueryingTheDatabase() {
        assertThatThrownBy(() -> converter.convert(jwt("legacy_plain").build()))
            .isInstanceOf(InvalidBearerTokenException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    void rejectsTokensWhoseUserNoLongerExists() {
        given(userRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> converter.convert(jwt("99").build()))
            .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void rejectsUnconfirmedAndSuspendedUsersOnEveryRequestLikeRailsActiveForAuthentication() {
        given(userRepository.findById(1L)).willReturn(Optional.of(TestUsers.unconfirmedUser(1L, "legacy_unconfirmed")));
        given(userRepository.findById(2L)).willReturn(Optional.of(TestUsers.suspendedUser(2L, "legacy_suspended")));

        assertThatThrownBy(() -> converter.convert(jwt("1").build())).isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> converter.convert(jwt("2").build())).isInstanceOf(InvalidBearerTokenException.class);
    }

    private static Jwt.Builder jwt(String subject) {
        return Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .subject(subject)
            .claim("typ", "access")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(900));
    }
}
