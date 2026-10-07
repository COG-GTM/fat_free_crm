package com.fatfreecrm.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fatfreecrm.service.TrackableService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Devise Trackable only runs on a password sign-in; bearer-token requests must not touch the counters.
 */
class TrackableAuthenticationListenerTest {

    private final TrackableService trackableService = mock(TrackableService.class);
    private final TrackableAuthenticationListener listener = new TrackableAuthenticationListener(trackableService);
    private final FfcrmUserDetails details =
        new FfcrmUserDetails(FfcrmUserDetailsTest.user(7L, "legacy_plain", false, Instant.now(), null));

    @Test
    void recordsPasswordSignInsWithTheRemoteAddressFromTheWebDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        UsernamePasswordAuthenticationToken authentication =
            UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetails(request));

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(authentication));

        verify(trackableService).recordSuccessfulSignIn(7L, "203.0.113.7");
    }

    @Test
    void recordsANullAddressWhenTheAuthenticationHasNoWebDetails() {
        UsernamePasswordAuthenticationToken authentication =
            UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities());

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(authentication));

        verify(trackableService).recordSuccessfulSignIn(7L, null);
    }

    @Test
    void ignoresBearerTokenAuthentications() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject("7").claim("typ", "access").build();
        FfcrmAuthenticationToken bearer = new FfcrmAuthenticationToken(
            jwt,
            List.of(new SimpleGrantedAuthority("ROLE_USER")),
            new AuthenticatedUser(7L, "legacy_plain", false)
        );

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(bearer));

        verifyNoInteractions(trackableService);
    }

    @Test
    void ignoresPasswordAuthenticationsWhosePrincipalIsNotAFfcrmUser() {
        UsernamePasswordAuthenticationToken foreign = UsernamePasswordAuthenticationToken.authenticated(
            "legacy_plain", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(foreign));

        verifyNoInteractions(trackableService);
    }
}
