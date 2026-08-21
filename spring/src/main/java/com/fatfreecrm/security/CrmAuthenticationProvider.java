package com.fatfreecrm.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Replaces {@code DaoAuthenticationProvider} because Devise's legacy hashes need
 * the per-user {@code password_salt}, which the {@link org.springframework.security.crypto.password.PasswordEncoder}
 * contract has no way to pass through.
 */
@Component
public class CrmAuthenticationProvider implements AuthenticationProvider {

    private final CrmUserDetailsService userDetailsService;
    private final CrmPasswordVerifier passwordVerifier;

    public CrmAuthenticationProvider(CrmUserDetailsService userDetailsService, CrmPasswordVerifier passwordVerifier) {
        this.userDetailsService = userDetailsService;
        this.passwordVerifier = passwordVerifier;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String login = authentication.getName();
        String rawPassword = String.valueOf(authentication.getCredentials());

        CrmUserDetails user;
        try {
            user = userDetailsService.loadUserByUsername(login);
        } catch (UsernameNotFoundException e) {
            // Same response as a wrong password: do not leak which logins exist.
            throw new BadCredentialsException("Bad credentials", e);
        }

        if (!passwordVerifier.matches(rawPassword, user.getPassword(), user.getPasswordSalt())) {
            throw new BadCredentialsException("Bad credentials");
        }
        if (!user.isEnabled()) {
            throw new DisabledException("User is suspended or deleted");
        }

        return UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
