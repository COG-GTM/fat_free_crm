package com.fatfreecrm.api;

import com.fatfreecrm.api.dto.CurrentUserResponse;
import com.fatfreecrm.api.dto.LoginRequest;
import com.fatfreecrm.api.dto.RefreshRequest;
import com.fatfreecrm.api.dto.TokenResponse;
import com.fatfreecrm.security.CrmUserDetails;
import com.fatfreecrm.security.CrmUserDetailsService;
import com.fatfreecrm.security.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Token endpoints for programmatic clients. The Rails UI keeps using Devise
 * cookie sessions until the Phase 7 cutover, so these live alongside it rather
 * than replacing it.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final CrmUserDetailsService userDetailsService;
    private final JwtService jwtService;

    public AuthController(AuthenticationManager authenticationManager,
                          CrmUserDetailsService userDetailsService,
                          JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.login(), request.password()));
        } catch (AuthenticationException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid login or password", e);
        }
        CrmUserDetails user = (CrmUserDetails) authentication.getPrincipal();
        return ResponseEntity.ok(tokensFor(user));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        try {
            String username = jwtService.parse(request.refreshToken(), JwtService.TOKEN_TYPE_REFRESH).getSubject();
            CrmUserDetails user = userDetailsService.loadUserByUsername(username);
            if (!user.isEnabled()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not active");
            }
            return ResponseEntity.ok(tokensFor(user));
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token", e);
        }
    }

    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal CrmUserDetails user) {
        List<String> authorities = user.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .toList();
        return new CurrentUserResponse(user.getId(), user.getUsername(), user.getEmail(), authorities);
    }

    private TokenResponse tokensFor(CrmUserDetails user) {
        return TokenResponse.bearer(
            jwtService.issueAccessToken(user),
            jwtService.issueRefreshToken(user),
            jwtService.accessTokenTtl().toSeconds());
    }
}
