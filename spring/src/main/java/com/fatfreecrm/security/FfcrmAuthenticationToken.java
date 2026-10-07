package com.fatfreecrm.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Carries the current database user alongside the validated JWT for downstream controllers.
 */
public final class FfcrmAuthenticationToken extends JwtAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final AuthenticatedUser authenticatedUser;

    public FfcrmAuthenticationToken(
        Jwt jwt,
        Collection<? extends GrantedAuthority> authorities,
        AuthenticatedUser authenticatedUser
    ) {
        super(jwt, authorities, authenticatedUser.id().toString());
        this.authenticatedUser = authenticatedUser;
    }

    public AuthenticatedUser getAuthenticatedUser() {
        return authenticatedUser;
    }
}
