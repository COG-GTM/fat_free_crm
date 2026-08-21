package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.Group;
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
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins the provider's error semantics to Devise's: unknown logins and wrong
 * passwords are indistinguishable, and password verification happens before the
 * suspended/deleted check (Devise's {@code active_for_authentication?} runs
 * after {@code valid_password?}).
 */
class CrmAuthenticationProviderTest {

    private static final int CONFIGURED_STRETCHES = 20;
    private static final String PASSWORD = "CorrectHorse!42";
    private static final String SALT = "per-user-salt";

    private final AuthlogicSha512PasswordEncoder legacyEncoder =
        new AuthlogicSha512PasswordEncoder(CONFIGURED_STRETCHES);
    private final CrmPasswordVerifier verifier = new CrmPasswordVerifier(
        PasswordEncoderFactories.createDelegatingPasswordEncoder(), legacyEncoder);
    private final CrmUserDetailsService userDetailsService = mock(CrmUserDetailsService.class);
    private final CrmAuthenticationProvider provider =
        new CrmAuthenticationProvider(userDetailsService, verifier);

    private CrmUserDetails detailsFor(User user) {
        return new CrmUserDetails(user, List.of());
    }

    private User railsUser() {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", 42L);
        user.setUsername("legacy_user");
        user.setEmail("legacy_user@example.com");
        user.setPasswordSalt(SALT);
        user.setEncryptedPassword(legacyEncoder.digest(PASSWORD, SALT));
        return user;
    }

    private static UsernamePasswordAuthenticationToken token(String login, String password) {
        return new UsernamePasswordAuthenticationToken(login, password);
    }

    @Test
    void authenticatesWithTheCorrectLegacyPassword() {
        when(userDetailsService.loadUserByUsername("legacy_user")).thenReturn(detailsFor(railsUser()));

        Authentication result = provider.authenticate(token("legacy_user", PASSWORD));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(((CrmUserDetails) result.getPrincipal()).getId()).isEqualTo(42L);
        assertThat(result.getAuthorities()).extracting(GrantedAuthority::getAuthority).contains("ROLE_USER");
    }

    @Test
    void unknownLoginAndWrongPasswordRaiseTheSameException() {
        when(userDetailsService.loadUserByUsername("nobody"))
            .thenThrow(new UsernameNotFoundException("No user for login nobody"));
        when(userDetailsService.loadUserByUsername("legacy_user")).thenReturn(detailsFor(railsUser()));

        assertThatThrownBy(() -> provider.authenticate(token("nobody", PASSWORD)))
            .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> provider.authenticate(token("legacy_user", "wrong")))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void refusesSuspendedUsersEvenWithTheCorrectPassword() {
        User suspended = railsUser();
        suspended.setSuspendedAt(Instant.now());
        when(userDetailsService.loadUserByUsername("legacy_user")).thenReturn(detailsFor(suspended));

        assertThatThrownBy(() -> provider.authenticate(token("legacy_user", PASSWORD)))
            .isInstanceOf(DisabledException.class);
    }

    @Test
    void refusesSoftDeletedUsersEvenWithTheCorrectPassword() {
        User deleted = railsUser();
        deleted.setDeletedAt(Instant.now());
        when(userDetailsService.loadUserByUsername("legacy_user")).thenReturn(detailsFor(deleted));

        assertThatThrownBy(() -> provider.authenticate(token("legacy_user", PASSWORD)))
            .isInstanceOf(DisabledException.class);
    }

    @Test
    void wrongPasswordWinsOverDisabledSoAccountStateIsNotLeaked() {
        User suspended = railsUser();
        suspended.setSuspendedAt(Instant.now());
        when(userDetailsService.loadUserByUsername("legacy_user")).thenReturn(detailsFor(suspended));

        assertThatThrownBy(() -> provider.authenticate(token("legacy_user", "wrong")))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void adminAndGroupMembershipMapToAuthorities() {
        User admin = railsUser();
        admin.setAdmin(true);
        Group sales = new Group();
        sales.setName("Sales");
        Group unnamed = new Group();
        when(userDetailsService.loadUserByUsername("legacy_user"))
            .thenReturn(new CrmUserDetails(admin, List.of(sales, unnamed)));

        Authentication result = provider.authenticate(token("legacy_user", PASSWORD));

        assertThat(result.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN", "GROUP_Sales");
    }

    @Test
    void supportsOnlyUsernamePasswordTokens() {
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(Authentication.class)).isFalse();
    }
}
