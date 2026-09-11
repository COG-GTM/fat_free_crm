package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** {@link CurrentUserProvider} reads only a {@link CurrentUser} principal from the security context. */
class CurrentUserProviderTest {

    private final CurrentUserProvider provider = new CurrentUserProvider();

    @BeforeEach
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void emptyWhenNoAuthenticationIsPresent() {
        assertThat(provider.currentUser()).isEmpty();
    }

    @Test
    void emptyWhenPrincipalIsNotACurrentUser() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(provider.currentUser()).isEmpty();
    }

    @Test
    void returnsTheCurrentUserPrincipal() {
        CurrentUser user = new CurrentUser(5L, true, Set.of(9L));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority(TrustedHeaderAuthenticationFilter.ROLE_USER))));

        assertThat(provider.currentUser()).contains(user);
        assertThat(provider.requireCurrentUser()).isSameAs(user);
    }

    @Test
    void requireCurrentUserThrowsUnauthenticatedExceptionWhenAbsent() {
        assertThatThrownBy(provider::requireCurrentUser)
                .isInstanceOf(UnauthenticatedException.class)
                .hasMessage("No authenticated user");
    }
}
