package com.fatfreecrm.security;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class FfcrmJwtAuthenticationConverter implements Converter<Jwt, FfcrmAuthenticationToken> {

    private final UserRepository userRepository;

    public FfcrmJwtAuthenticationConverter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public FfcrmAuthenticationToken convert(Jwt jwt) {
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new InvalidBearerTokenException("Invalid access token", exception);
        }
        User user = userRepository.findById(userId)
            .filter(found -> found.getConfirmedAt() != null && found.getSuspendedAt() == null)
            .orElseThrow(() -> new InvalidBearerTokenException("Invalid access token"));

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (user.isAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        AuthenticatedUser authenticatedUser = new AuthenticatedUser(user.getId(), user.getUsername(), user.isAdmin());
        return new FfcrmAuthenticationToken(jwt, authorities, authenticatedUser);
    }
}
