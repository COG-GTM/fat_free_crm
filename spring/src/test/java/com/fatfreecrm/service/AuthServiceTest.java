package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fatfreecrm.api.dto.CurrentUserResponse;
import com.fatfreecrm.api.dto.TokenResponse;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.FfcrmUserDetails;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.security.JwtTokenService.TokenPair;
import com.fatfreecrm.support.TestUsers;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final TokenPair PAIR = new TokenPair("access-token", "refresh-token", 900);

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtTokenService jwtTokenService;

    @InjectMocks
    private AuthService authService;

    @Test
    void loginAuthenticatesWithTheRemoteAddressAndIssuesABearerTokenPair() {
        User user = TestUsers.activeUser(42L, "legacy_plain", false);
        FfcrmUserDetails details = new FfcrmUserDetails(user);
        given(authenticationManager.authenticate(any()))
            .willReturn(UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
        given(userRepository.findById(42L)).willReturn(Optional.of(user));
        given(jwtTokenService.issue(user)).willReturn(PAIR);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");

        TokenResponse response = authService.login("legacy_plain", "secret", request);

        assertThat(response).isEqualTo(new TokenResponse("access-token", "refresh-token", "Bearer", 900));
        ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
        verify(authenticationManager).authenticate(captor.capture());
        Authentication submitted = captor.getValue();
        assertThat(submitted).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(submitted.isAuthenticated()).isFalse();
        assertThat(submitted.getName()).isEqualTo("legacy_plain");
        assertThat(submitted.getCredentials()).isEqualTo("secret");
        assertThat(submitted.getDetails()).isInstanceOf(WebAuthenticationDetails.class);
        assertThat(((WebAuthenticationDetails) submitted.getDetails()).getRemoteAddress()).isEqualTo("203.0.113.7");
    }

    @Test
    void loginPropagatesAuthenticationFailuresWithoutIssuingTokens() {
        given(authenticationManager.authenticate(any()))
            .willThrow(new BadCredentialsException("Bad credentials"))
            .willThrow(new DisabledException("User is disabled"));

        assertThatThrownBy(() -> authService.login("legacy_plain", "wrong", new MockHttpServletRequest()))
            .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> authService.login("legacy_unconfirmed", "secret", new MockHttpServletRequest()))
            .isInstanceOf(DisabledException.class);

        verify(jwtTokenService, never()).issue(any());
    }

    @Test
    void loginRejectsUsersDeletedBetweenAuthenticationAndTokenIssuance() {
        FfcrmUserDetails details = new FfcrmUserDetails(TestUsers.activeUser(42L, "legacy_plain", false));
        given(authenticationManager.authenticate(any()))
            .willReturn(UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
        given(userRepository.findById(42L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("legacy_plain", "secret", new MockHttpServletRequest()))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Invalid credentials.");
        verify(jwtTokenService, never()).issue(any());
    }

    @Test
    void refreshIssuesANewPairForAnActiveUser() {
        User user = TestUsers.activeUser(42L, "legacy_plain", false);
        given(jwtTokenService.verifyRefresh("refresh-token")).willReturn(refreshJwt("42"));
        given(userRepository.findById(42L)).willReturn(Optional.of(user));
        given(jwtTokenService.issue(user)).willReturn(PAIR);

        assertThat(authService.refresh("refresh-token"))
            .isEqualTo(new TokenResponse("access-token", "refresh-token", "Bearer", 900));
    }

    @Test
    void refreshMapsInvalidTokensToBadCredentials() {
        given(jwtTokenService.verifyRefresh("broken")).willThrow(new BadJwtException("expired"));
        given(jwtTokenService.verifyRefresh("non-numeric")).willReturn(refreshJwt("legacy_plain"));

        assertThatThrownBy(() -> authService.refresh("broken"))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Invalid credentials.");
        assertThatThrownBy(() -> authService.refresh("non-numeric"))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Invalid credentials.");
        verify(jwtTokenService, never()).issue(any());
    }

    @Test
    void refreshRejectsMissingUnconfirmedAndSuspendedUsers() {
        given(jwtTokenService.verifyRefresh("missing")).willReturn(refreshJwt("1"));
        given(jwtTokenService.verifyRefresh("unconfirmed")).willReturn(refreshJwt("2"));
        given(jwtTokenService.verifyRefresh("suspended")).willReturn(refreshJwt("3"));
        given(userRepository.findById(1L)).willReturn(Optional.empty());
        given(userRepository.findById(2L)).willReturn(Optional.of(TestUsers.unconfirmedUser(2L, "legacy_unconfirmed")));
        given(userRepository.findById(3L)).willReturn(Optional.of(TestUsers.suspendedUser(3L, "legacy_suspended")));

        for (String token : new String[] {"missing", "unconfirmed", "suspended"}) {
            assertThatThrownBy(() -> authService.refresh(token))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials.");
        }
        verify(jwtTokenService, never()).issue(any());
    }

    @Test
    void currentUserReturnsTheRailsProfileFieldsOfTheReloadedRow() {
        User user = TestUsers.activeUser(42L, "legacy_admin", true);
        user.setEmail("legacy_admin@example.com");
        user.setFirstName("Legacy");
        user.setLastName("Admin");
        given(userRepository.findById(42L)).willReturn(Optional.of(user));

        CurrentUserResponse response = authService.currentUser(new AuthenticatedUser(42L, "stale-name", false));

        assertThat(response).isEqualTo(
            new CurrentUserResponse(42L, "legacy_admin", "legacy_admin@example.com", "Legacy", "Admin", true));
    }

    @Test
    void currentUserRejectsMissingUnconfirmedAndSuspendedUsers() {
        given(userRepository.findById(1L)).willReturn(Optional.empty());
        given(userRepository.findById(2L)).willReturn(Optional.of(TestUsers.unconfirmedUser(2L, "legacy_unconfirmed")));
        given(userRepository.findById(3L)).willReturn(Optional.of(TestUsers.suspendedUser(3L, "legacy_suspended")));

        for (long id : new long[] {1L, 2L, 3L}) {
            assertThatThrownBy(() -> authService.currentUser(new AuthenticatedUser(id, "user", false)))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials.");
        }
    }

    private static Jwt refreshJwt(String subject) {
        return Jwt.withTokenValue("refresh-token")
            .header("alg", "HS256")
            .subject(subject)
            .claim("typ", "refresh")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
    }
}
