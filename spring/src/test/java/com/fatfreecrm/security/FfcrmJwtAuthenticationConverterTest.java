package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Rails re-checks {@code active_for_authentication?} on every request; the converter must reload the
 * user from the database for every bearer token and refuse inactive or unknown subjects.
 */
@ExtendWith(MockitoExtension.class)
class FfcrmJwtAuthenticationConverterTest {

    private static final Instant CONFIRMED = Instant.parse("2024-01-01T00:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Test
    void rejectsNonNumericSubjectsWithoutQueryingTheDatabase() {
        FfcrmJwtAuthenticationConverter converter = new FfcrmJwtAuthenticationConverter(userRepository);

        assertThatThrownBy(() -> converter.convert(jwt("legacy_plain", false)))
            .isInstanceOf(InvalidBearerTokenException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    void rejectsSubjectsWithNoMatchingUserRow() {
        FfcrmJwtAuthenticationConverter converter = new FfcrmJwtAuthenticationConverter(userRepository);
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> converter.convert(jwt("404", false)))
            .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void rejectsUnconfirmedUsersEvenWithAValidToken() {
        FfcrmJwtAuthenticationConverter converter = new FfcrmJwtAuthenticationConverter(userRepository);
        when(userRepository.findById(5L))
            .thenReturn(Optional.of(FfcrmUserDetailsTest.user(5L, "unconfirmed", false, null, null)));

        assertThatThrownBy(() -> converter.convert(jwt("5", false)))
            .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void rejectsSuspendedUsersEvenWithAValidToken() {
        FfcrmJwtAuthenticationConverter converter = new FfcrmJwtAuthenticationConverter(userRepository);
        when(userRepository.findById(6L))
            .thenReturn(Optional.of(FfcrmUserDetailsTest.user(6L, "suspended", false, CONFIRMED, Instant.now())));

        assertThatThrownBy(() -> converter.convert(jwt("6", false)))
            .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void grantsRoleUserOnlyToNonAdminsAndCarriesTheDatabaseIdentity() {
        FfcrmJwtAuthenticationConverter converter = new FfcrmJwtAuthenticationConverter(userRepository);
        Jwt jwt = jwt("7", false);
        when(userRepository.findById(7L))
            .thenReturn(Optional.of(FfcrmUserDetailsTest.user(7L, "legacy_plain", false, CONFIRMED, null)));

        FfcrmAuthenticationToken token = converter.convert(jwt);

        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getName()).isEqualTo("7");
        assertThat(token.getToken()).isSameAs(jwt);
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
        assertThat(token.getAuthenticatedUser()).isEqualTo(new AuthenticatedUser(7L, "legacy_plain", false));
    }

    @Test
    void grantsRoleAdminFromTheDatabaseFlagNotFromTheTokenClaims() {
        FfcrmJwtAuthenticationConverter converter = new FfcrmJwtAuthenticationConverter(userRepository);
        User user = FfcrmUserDetailsTest.user(8L, "legacy_admin", true, CONFIRMED, null);
        when(userRepository.findById(8L)).thenReturn(Optional.of(user));

        FfcrmAuthenticationToken token = converter.convert(jwt("8", false));

        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        assertThat(token.getAuthenticatedUser().admin()).isTrue();
    }

    @Test
    void ignoresForgedAdminAndUsernameClaimsInFavourOfTheCurrentRow() {
        FfcrmJwtAuthenticationConverter converter = new FfcrmJwtAuthenticationConverter(userRepository);
        when(userRepository.findById(9L))
            .thenReturn(Optional.of(FfcrmUserDetailsTest.user(9L, "legacy_plain", false, CONFIRMED, null)));

        FfcrmAuthenticationToken token = converter.convert(Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .subject("9")
            .claim("username", "somebody_else")
            .claim("admin", true)
            .claim("typ", "access")
            .build());

        assertThat(token.getAuthenticatedUser()).isEqualTo(new AuthenticatedUser(9L, "legacy_plain", false));
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
    }

    private static Jwt jwt(String subject, boolean admin) {
        return Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .subject(subject)
            .claim("username", "ignored")
            .claim("admin", admin)
            .claim("typ", "access")
            .build();
    }
}
