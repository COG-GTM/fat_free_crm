package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.User;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins the provider's failure semantics to Devise's: unknown logins and wrong
 * passwords are indistinguishable, and inactive users are only rejected after
 * their password has been verified.
 */
class CrmAuthenticationProviderTest {

    private final CrmUserDetailsService userDetailsService = mock(CrmUserDetailsService.class);
    private final CrmPasswordVerifier passwordVerifier = mock(CrmPasswordVerifier.class);
    private final CrmAuthenticationProvider provider =
        new CrmAuthenticationProvider(userDetailsService, passwordVerifier);

    private static CrmUserDetails details(boolean admin, Instant suspendedAt, Instant deletedAt) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", 42L);
        user.setUsername("legacy_user");
        user.setEmail("legacy_user@example.com");
        user.setEncryptedPassword("stored-hash");
        user.setPasswordSalt("stored-salt");
        user.setAdmin(admin);
        user.setSuspendedAt(suspendedAt);
        user.setDeletedAt(deletedAt);
        return new CrmUserDetails(user, List.of());
    }

    private static Authentication token(String login, String password) {
        return new UsernamePasswordAuthenticationToken(login, password);
    }

    @Test
    void unknownUserFailsWithTheSameExceptionAsAWrongPassword() {
        when(userDetailsService.loadUserByUsername("nobody"))
            .thenThrow(new UsernameNotFoundException("No user for login nobody"));

        assertThatThrownBy(() -> provider.authenticate(token("nobody", "whatever")))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Bad credentials");
    }

    @Test
    void wrongPasswordFailsWithBadCredentials() {
        when(userDetailsService.loadUserByUsername("legacy_user")).thenReturn(details(false, null, null));
        when(passwordVerifier.matches(anyString(), anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> provider.authenticate(token("legacy_user", "wrong")))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Bad credentials");
    }

    @Test
    void suspendedUserWithTheRightPasswordIsRejectedAsDisabled() {
        when(userDetailsService.loadUserByUsername("legacy_user"))
            .thenReturn(details(false, Instant.now(), null));
        when(passwordVerifier.matches("secret", "stored-hash", "stored-salt")).thenReturn(true);

        assertThatThrownBy(() -> provider.authenticate(token("legacy_user", "secret")))
            .isInstanceOf(DisabledException.class);
    }

    @Test
    void softDeletedUserWithTheRightPasswordIsRejectedAsDisabled() {
        when(userDetailsService.loadUserByUsername("legacy_user"))
            .thenReturn(details(false, null, Instant.now()));
        when(passwordVerifier.matches("secret", "stored-hash", "stored-salt")).thenReturn(true);

        assertThatThrownBy(() -> provider.authenticate(token("legacy_user", "secret")))
            .isInstanceOf(DisabledException.class);
    }

    @Test
    void successReturnsAnAuthenticatedTokenWithoutTheRawCredentials() {
        when(userDetailsService.loadUserByUsername("legacy_user")).thenReturn(details(true, null, null));
        when(passwordVerifier.matches("secret", "stored-hash", "stored-salt")).thenReturn(true);

        Authentication result = provider.authenticate(token("legacy_user", "secret"));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getCredentials()).isNull();
        assertThat(result.getPrincipal()).isInstanceOf(CrmUserDetails.class);
        assertThat(result.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .contains("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void supportsOnlyUsernamePasswordTokens() {
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(Authentication.class)).isFalse();
    }
}
