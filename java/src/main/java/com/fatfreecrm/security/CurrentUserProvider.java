package com.fatfreecrm.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Gives services access to the {@link CurrentUser} placed in the Spring Security context by
 * {@link TrustedHeaderAuthenticationFilter}. Services should depend on this bean rather than on
 * {@link SecurityContextHolder} directly so the authentication mechanism can be swapped for JWT
 * later without touching the service layer.
 */
@Component
public class CurrentUserProvider {

    /**
     * @return the current user, or empty when the request is unauthenticated
     */
    public Optional<CurrentUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    /**
     * @return the current user
     * @throws UnauthenticatedException when no user is authenticated; the security chain
     *         rejects unauthenticated {@code /api/**} requests with 401 before any service is
     *         invoked, so this is a programming-error guard rather than an expected path
     */
    public CurrentUser requireCurrentUser() {
        return currentUser().orElseThrow(() -> new UnauthenticatedException("No authenticated user"));
    }
}
