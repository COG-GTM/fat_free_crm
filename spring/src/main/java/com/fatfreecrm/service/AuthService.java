package com.fatfreecrm.service;

import com.fatfreecrm.api.dto.CurrentUserResponse;
import com.fatfreecrm.api.dto.TokenResponse;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.FfcrmUserDetails;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.security.JwtTokenService.TokenPair;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtTokenService jwtTokenService;

    public AuthService(
        AuthenticationManager authenticationManager,
        UserRepository userRepository,
        JwtTokenService jwtTokenService
    ) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.jwtTokenService = jwtTokenService;
    }

    public TokenResponse login(String username, String password, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authenticationRequest =
            UsernamePasswordAuthenticationToken.unauthenticated(username, password);
        authenticationRequest.setDetails(new WebAuthenticationDetails(request));
        Authentication authentication = authenticationManager.authenticate(authenticationRequest);
        FfcrmUserDetails userDetails = (FfcrmUserDetails) authentication.getPrincipal();
        User user = userRepository.findById(userDetails.getUserId())
            .orElseThrow(() -> new BadCredentialsException("Invalid credentials."));
        return toResponse(jwtTokenService.issue(user));
    }

    public TokenResponse refresh(String refreshToken) {
        try {
            Jwt jwt = jwtTokenService.verifyRefresh(refreshToken);
            long userId = Long.parseLong(jwt.getSubject());
            Optional<User> user = userRepository.findById(userId);
            User activeUser = user
                .filter(found -> found.getConfirmedAt() != null && found.getSuspendedAt() == null)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials."));
            return toResponse(jwtTokenService.issue(activeUser));
        } catch (JwtException | NumberFormatException exception) {
            throw new BadCredentialsException("Invalid credentials.", exception);
        }
    }

    public CurrentUserResponse currentUser(AuthenticatedUser authenticatedUser) {
        User user = userRepository.findById(authenticatedUser.id())
            .filter(found -> found.getConfirmedAt() != null && found.getSuspendedAt() == null)
            .orElseThrow(() -> new BadCredentialsException("Invalid credentials."));
        return new CurrentUserResponse(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getFirstName(),
            user.getLastName(),
            user.isAdmin()
        );
    }

    private TokenResponse toResponse(TokenPair pair) {
        return new TokenResponse(pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresIn());
    }
}
