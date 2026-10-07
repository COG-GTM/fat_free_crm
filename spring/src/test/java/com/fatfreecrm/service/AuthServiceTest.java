package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fatfreecrm.api.dto.CurrentUserResponse;
import com.fatfreecrm.api.dto.TokenResponse;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.FfcrmUserDetails;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.security.JwtTokenService.TokenPair;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant CONFIRMED = Instant.parse("2024-01-01T00:00:00Z");
    private static final TokenPair PAIR = new TokenPair("access-token", "refresh-token", 900);

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtTokenService jwtTokenService;

    @Test
    void loginAuthenticatesWithTheRequestAddressAndMapsTheTokenPairToTheResponse() {
        AuthService service = new AuthService(authenticationManager, userRepository, jwtTokenService);
        User user = user(7L, "legacy_plain", false, CONFIRMED, null);
        FfcrmUserDetails details = new FfcrmUserDetails(user);
        when(authenticationManager.authenticate(any()))
            .thenReturn(UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(jwtTokenService.issue(user)).thenReturn(PAIR);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");

        TokenResponse response = service.login("legacy_plain", "PlainAsciiPassword42", request);

        assertThat(response).isEqualTo(new TokenResponse("access-token", "refresh-token", "Bearer", 900));
        ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
        verify(authenticationManager).authenticate(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(captor.getValue().isAuthenticated()).isFalse();
        assertThat(captor.getValue().getPrincipal()).isEqualTo("legacy_plain");
        assertThat(captor.getValue().getCredentials()).isEqualTo("PlainAsciiPassword42");
        assertThat(captor.getValue().getDetails()).isInstanceOf(WebAuthenticationDetails.class);
        assertThat(((WebAuthenticationDetails) captor.getValue().getDetails()).getRemoteAddress())
            .isEqualTo("203.0.113.7");
    }

    @Test
    void loginPropagatesAuthenticationFailuresWithoutIssuingTokens() {
        AuthService service = new AuthService(authenticationManager, userRepository, jwtTokenService);
        when(authenticationManager.authenticate(any())).thenThrow(new DisabledException("unconfirmed"));

        assertThatThrownBy(() -> service.login("legacy_unconfirmed", "pw", new MockHttpServletRequest()))
            .isInstanceOf(DisabledException.class);
        verifyNoInteractions(jwtTokenService, userRepository);
    }

    @Test
    void loginFailsWithBadCredentialsWhenTheUserRowVanishesAfterAuthentication() {
        AuthService service = new AuthService(authenticationManager, userRepository, jwtTokenService);
        FfcrmUserDetails details = new FfcrmUserDetails(user(7L, "legacy_plain", false, CONFIRMED, null));
        when(authenticationManager.authenticate(any()))
            .thenReturn(UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("legacy_plain", "pw", new MockHttpServletRequest()))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Invalid credentials.");
        verifyNoInteractions(jwtTokenService);
    }

    @Test
    void refreshIssuesANewPairForAConfirmedUnsuspendedUser() {
        AuthService service = new AuthService(authenticationManager, userRepository, jwtTokenService);
        User user = user(7L, "legacy_plain", false, CONFIRMED, null);
        when(jwtTokenService.verifyRefresh("refresh-token")).thenReturn(jwt("7"));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(jwtTokenService.issue(user)).thenReturn(PAIR);

        assertThat(service.refresh("refresh-token"))
            .isEqualTo(new TokenResponse("access-token", "refresh-token", "Bearer", 900));
        verifyNoInteractions(authenticationManager);
    }

    @Test
    void refreshWrapsTokenValidationFailuresAsBadCredentials() {
        AuthService service = new AuthService(authenticationManager, userRepository, jwtTokenService);
        JwtException cause = new JwtException("expired");
        when(jwtTokenService.verifyRefresh("stale")).thenThrow(cause);

        assertThatThrownBy(() -> service.refresh("stale"))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Invalid credentials.")
            .hasCause(cause);
        verifyNoInteractions(userRepository);
    }

    @Test
    void refreshRejectsNonNumericSubjectsAsBadCredentials() {
        AuthService service = new AuthService(authenticationManager, userRepository, jwtTokenService);
        when(jwtTokenService.verifyRefresh("odd")).thenReturn(jwt("legacy_plain"));

        assertThatThrownBy(() -> service.refresh("odd"))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Invalid credentials.")
            .hasCauseInstanceOf(NumberFormatException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    void refreshRejectsMissingUnconfirmedAndSuspendedUsers() {
        AuthService service = new AuthService(authenticationManager, userRepository, jwtTokenService);
        when(jwtTokenService.verifyRefresh(any())).thenReturn(jwt("7"));
        when(userRepository.findById(7L))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(user(7L, "legacy_unconfirmed", false, null, null)))
            .thenReturn(Optional.of(user(7L, "legacy_suspended", false, CONFIRMED, Instant.now())));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> service.refresh("refresh-token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials.");
        }
        verify(jwtTokenService, never()).issue(any());
    }

    @Test
    void currentUserMapsTheRailsColumnsToTheResponseFields() {
        AuthService service = new AuthService(authenticationManager, userRepository, jwtTokenService);
        User user = user(8L, "legacy_admin", true, CONFIRMED, null);
        user.setEmail("legacy_admin@example.com");
        user.setFirstName("Legacy");
        user.setLastName("legacy_admin");
        when(userRepository.findById(8L)).thenReturn(Optional.of(user));

        CurrentUserResponse response = service.currentUser(new AuthenticatedUser(8L, "legacy_admin", true));

        assertThat(response).isEqualTo(
            new CurrentUserResponse(8L, "legacy_admin", "legacy_admin@example.com", "Legacy", "legacy_admin", true));
    }

    @Test
    void currentUserRejectsUsersThatBecameInactiveSinceTheTokenWasIssued() {
        AuthService service = new AuthService(authenticationManager, userRepository, jwtTokenService);
        when(userRepository.findById(8L))
            .thenReturn(Optional.of(user(8L, "legacy_suspended", false, CONFIRMED, Instant.now())))
            .thenReturn(Optional.of(user(8L, "legacy_unconfirmed", false, null, null)))
            .thenReturn(Optional.empty());
        AuthenticatedUser principal = new AuthenticatedUser(8L, "legacy_suspended", false);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> service.currentUser(principal))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials.");
        }
    }

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue("token").header("alg", "HS256").subject(subject).claim("typ", "refresh").build();
    }

    private static User user(Long id, String username, boolean admin, Instant confirmedAt, Instant suspendedAt) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setUsername(username);
        user.setAdmin(admin);
        user.setConfirmedAt(confirmedAt);
        user.setSuspendedAt(suspendedAt);
        return user;
    }
}
