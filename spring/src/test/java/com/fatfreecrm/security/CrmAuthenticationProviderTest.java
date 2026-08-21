package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.User;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * The provider must behave like Devise: unknown logins and wrong passwords are
 * indistinguishable to the caller, and account state is only revealed after the
 * password has been verified.
 */
class CrmAuthenticationProviderTest {

    private CrmUserDetailsService userDetailsService;
    private CrmPasswordVerifier passwordVerifier;
    private CrmAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        userDetailsService = mock(CrmUserDetailsService.class);
        passwordVerifier = mock(CrmPasswordVerifier.class);
        provider = new CrmAuthenticationProvider(userDetailsService, passwordVerifier);
    }

    private static CrmUserDetails details(boolean admin, Instant suspendedAt) {
        User user = new User();
        user.setUsername("someone");
        user.setEncryptedPassword("hash");
        user.setPasswordSalt("salt");
        user.setAdmin(admin);
        user.setSuspendedAt(suspendedAt);
        return new CrmUserDetails(user, List.of());
    }

    private static Authentication token(String login, String password) {
        return UsernamePasswordAuthenticationToken.unauthenticated(login, password);
    }

    @Test
    void unknownUserBecomesBadCredentialsSoLoginsDoNotLeak() {
        when(userDetailsService.loadUserByUsername("ghost"))
            .thenThrow(new UsernameNotFoundException("No user for login ghost"));

        assertThatThrownBy(() -> provider.authenticate(token("ghost", "whatever")))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void wrongPasswordIsRejected() {
        when(userDetailsService.loadUserByUsername("someone")).thenReturn(details(false, null));
        when(passwordVerifier.matches("wrong", "hash", "salt")).thenReturn(false);

        assertThatThrownBy(() -> provider.authenticate(token("someone", "wrong")))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void suspendedUserIsRejectedEvenWithTheRightPassword() {
        when(userDetailsService.loadUserByUsername("someone")).thenReturn(details(false, Instant.now()));
        when(passwordVerifier.matches(anyString(), anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> provider.authenticate(token("someone", "correct")))
            .isInstanceOf(DisabledException.class);
    }

    @Test
    void successfulAuthenticationCarriesTheUserAndItsAuthorities() {
        when(userDetailsService.loadUserByUsername("someone")).thenReturn(details(true, null));
        when(passwordVerifier.matches("correct", "hash", "salt")).thenReturn(true);

        Authentication result = provider.authenticate(token("someone", "correct"));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isInstanceOf(CrmUserDetails.class);
        assertThat(result.getCredentials()).isNull();
        assertThat(result.getAuthorities().stream().map(GrantedAuthority::getAuthority))
            .containsExactly("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void onlySupportsUsernamePasswordTokens() {
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(Authentication.class)).isFalse();
    }
}
