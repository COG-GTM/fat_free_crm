package com.fatfreecrm.security;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.GroupUserRepository;
import com.fatfreecrm.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * TEMPORARY authentication shim for the strangler phase.
 *
 * <p>Authenticates a request from the trusted gateway header {@value #HEADER} holding the
 * numeric Rails {@code users.id}. The user row is loaded (id, {@code admin} flag) together with
 * its group ids from {@code groups_users}, and a {@link CurrentUser} is installed as the Spring
 * Security principal. Suspended and soft-deleted users are rejected.
 *
 * <p><strong>Security caveat:</strong> this filter trusts the header blindly. The service must
 * only be reachable behind the gateway that authenticates the Devise session and sets/overwrites
 * {@value #HEADER}; it must never be exposed directly. It will be replaced by stateless JWT
 * authentication in a later phase (docs/migration/target-architecture.md §2.4).
 *
 * <p>Requests to {@code /api/**} with a missing, non-numeric or unknown header are rejected with
 * a 401 {@code application/problem+json} response. Requests to other paths pass through
 * unauthenticated so that the permit-all rules in {@code SecurityConfig} (OpenAPI, health) apply.
 */
public class TrustedHeaderAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-User-Id";
    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final UserRepository userRepository;
    private final GroupUserRepository groupUserRepository;
    private final AuthenticationEntryPoint entryPoint;

    public TrustedHeaderAuthenticationFilter(
            UserRepository userRepository,
            GroupUserRepository groupUserRepository,
            AuthenticationEntryPoint entryPoint) {
        this.userRepository = userRepository;
        this.groupUserRepository = groupUserRepository;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        Optional<CurrentUser> user = parseUserId(header).flatMap(this::loadUser);
        if (user.isEmpty()) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response,
                    new UnauthenticatedException("Unknown or invalid " + HEADER + " header"));
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(toAuthentication(user.get()));
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static Optional<Long> parseUserId(String header) {
        try {
            long id = Long.parseLong(header.trim());
            return id > 0 ? Optional.of(id) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<CurrentUser> loadUser(Long id) {
        return userRepository.findById(id)
                .filter(u -> u.getSuspendedAt() == null)
                .map(this::toCurrentUser);
    }

    private CurrentUser toCurrentUser(User user) {
        return new CurrentUser(
                user.getId(),
                user.isAdmin(),
                new HashSet<>(groupUserRepository.findGroupIdsByUserId(user.getId())));
    }

    private static Authentication toAuthentication(CurrentUser user) {
        List<SimpleGrantedAuthority> authorities = user.admin()
                ? List.of(new SimpleGrantedAuthority(ROLE_USER), new SimpleGrantedAuthority(ROLE_ADMIN))
                : List.of(new SimpleGrantedAuthority(ROLE_USER));
        return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
    }
}
