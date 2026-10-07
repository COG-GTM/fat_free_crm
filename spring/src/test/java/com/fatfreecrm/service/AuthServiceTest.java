package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTest {

    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtTokenService jwtTokenService = mock(JwtTokenService.class);
    private final AuthService service = new AuthService(authenticationManager, userRepository, jwtTokenService);

    @Test
    void loginAuthenticatesWithWebDetailsThenIssuesTokensForTheReloadedUser() {
        User user = user(42L, Instant.EPOCH, null);
        FfcrmUserDetails details = new FfcrmUserDetails(user);
        when(authenticationManager.authenticate(any()))
            .thenReturn(UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(jwtTokenService.issue(user)).thenReturn(new TokenPair("access", "refresh", 900));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");

        TokenResponse response = service.login("legacy_plain", "secret", request);

        assertThat(response).isEqualTo(new TokenResponse("access", "refresh", "Bearer", 900));
        ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
        verify(authenticationManager).authenticate(captor.capture());
        Authentication sent = captor.getValue();
        assertThat(sent.getPrincipal()).isEqualTo("legacy_plain");
        assertThat(sent.getCredentials()).isEqualTo("secret");
        assertThat(sent.isAuthenticated()).isFalse();
        assertThat(sent.getDetails()).isInstanceOf(WebAuthenticationDetails.class);
        assertThat(((WebAuthenticationDetails) sent.getDetails()).getRemoteAddress()).isEqualTo("203.0.113.7");
    }

    @Test
    void loginPropagatesProviderFailuresWithoutIssuingTokens() {
        doThrow(new BadCredentialsException("bad")).when(authenticationManager).authenticate(any());
        assertThatThrownBy(() -> service.login("u", "p", new MockHttpServletRequest()))
            .isInstanceOf(BadCredentialsException.class);

        doThrow(new LockedException("suspended")).when(authenticationManager).authenticate(any());
        assertThatThrownBy(() -> service.login("u", "p", new MockHttpServletRequest()))
            .isInstanceOf(LockedException.class);

        doThrow(new DisabledException("unconfirmed")).when(authenticationManager).authenticate(any());
        assertThatThrownBy(() -> service.login("u", "p", new MockHttpServletRequest()))
            .isInstanceOf(DisabledException.class);

        verify(jwtTokenService, never()).issue(any());
    }

    @Test
    void loginFailsWhenTheAuthenticatedUserRowDisappearedBeforeTokenIssuance() {
        FfcrmUserDetails details = new FfcrmUserDetails(user(42L, Instant.EPOCH, null));
        when(authenticationManager.authenticate(any()))
            .thenReturn(UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("legacy_plain", "secret", new MockHttpServletRequest()))
            .isInstanceOf(BadCredentialsException.class);
        verify(jwtTokenService, never()).issue(any());
    }

    @Test
    void refreshIssuesANewPairForAConfirmedUnsuspendedUser() {
        User user = user(7L, Instant.EPOCH, null);
        when(jwtTokenService.verifyRefresh("refresh")).thenReturn(jwt("7"));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(jwtTokenService.issue(user)).thenReturn(new TokenPair("a2", "r2", 900));

        assertThat(service.refresh("refresh")).isEqualTo(new TokenResponse("a2", "r2", "Bearer", 900));
    }

    @Test
    void refreshMapsInvalidTokensToBadCredentials() {
        when(jwtTokenService.verifyRefresh("broken")).thenThrow(new BadJwtException("expired"));

        assertThatThrownBy(() -> service.refresh("broken"))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Invalid credentials.");
    }

    @Test
    void refreshRejectsANonNumericSubjectAsBadCredentials() {
        when(jwtTokenService.verifyRefresh("odd")).thenReturn(jwt("legacy_plain"));

        assertThatThrownBy(() -> service.refresh("odd")).isInstanceOf(BadCredentialsException.class);
        verify(jwtTokenService, never()).issue(any());
    }

    @Test
    void refreshRejectsDeletedSuspendedAndUnconfirmedUsers() {
        when(jwtTokenService.verifyRefresh("gone")).thenReturn(jwt("1"));
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.refresh("gone")).isInstanceOf(BadCredentialsException.class);

        when(jwtTokenService.verifyRefresh("suspended")).thenReturn(jwt("2"));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, Instant.EPOCH, Instant.EPOCH)));
        assertThatThrownBy(() -> service.refresh("suspended")).isInstanceOf(BadCredentialsException.class);

        when(jwtTokenService.verifyRefresh("unconfirmed")).thenReturn(jwt("3"));
        when(userRepository.findById(3L)).thenReturn(Optional.of(user(3L, null, null)));
        assertThatThrownBy(() -> service.refresh("unconfirmed")).isInstanceOf(BadCredentialsException.class);

        verify(jwtTokenService, never()).issue(any());
    }

    @Test
    void currentUserReturnsTheRailsFieldNamesForTheAuthenticatedRow() {
        User user = user(9L, Instant.EPOCH, null);
        user.setEmail("legacy_admin@example.com");
        user.setFirstName("Legacy");
        user.setLastName("legacy_admin");
        user.setAdmin(true);
        when(userRepository.findById(9L)).thenReturn(Optional.of(user));

        CurrentUserResponse response = service.currentUser(new AuthenticatedUser(9L, "legacy_admin", true));

        assertThat(response).isEqualTo(
            new CurrentUserResponse(9L, "legacy_plain", "legacy_admin@example.com", "Legacy", "legacy_admin", true));
    }

    @Test
    void currentUserRejectsRowsThatBecameSuspendedUnconfirmedOrDeleted() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, Instant.EPOCH, Instant.EPOCH)));
        assertThatThrownBy(() -> service.currentUser(new AuthenticatedUser(1L, "u", false)))
            .isInstanceOf(BadCredentialsException.class);

        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, null, null)));
        assertThatThrownBy(() -> service.currentUser(new AuthenticatedUser(2L, "u", false)))
            .isInstanceOf(BadCredentialsException.class);

        when(userRepository.findById(3L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.currentUser(new AuthenticatedUser(3L, "u", false)))
            .isInstanceOf(BadCredentialsException.class);
    }

    private static User user(Long id, Instant confirmedAt, Instant suspendedAt) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setUsername("legacy_plain");
        user.setEncryptedPassword("hash");
        user.setPasswordSalt("salt");
        user.setConfirmedAt(confirmedAt);
        user.setSuspendedAt(suspendedAt);
        return user;
    }

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .subject(subject)
            .claim("typ", "refresh")
            .build();
    }
}
