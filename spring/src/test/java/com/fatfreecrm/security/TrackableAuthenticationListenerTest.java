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

class TrackableAuthenticationListenerTest {

    private final TrackableService trackableService = mock(TrackableService.class);
    private final TrackableAuthenticationListener listener = new TrackableAuthenticationListener(trackableService);

    @Test
    void recordsPasswordLoginsWithTheRemoteAddressFromTheWebDetails() {
        FfcrmUserDetails principal = new FfcrmUserDetails(
            FfcrmUserDetailsTest.user(5L, "legacy_plain", false, Instant.EPOCH, null));
        UsernamePasswordAuthenticationToken authentication =
            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        authentication.setDetails(new WebAuthenticationDetails(request));

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(authentication));

        verify(trackableService).recordSuccessfulSignIn(5L, "203.0.113.9");
    }

    @Test
    void recordsANullAddressWhenNoWebDetailsArePresent() {
        FfcrmUserDetails principal = new FfcrmUserDetails(
            FfcrmUserDetailsTest.user(6L, "legacy_plain", false, Instant.EPOCH, null));
        UsernamePasswordAuthenticationToken authentication =
            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(authentication));

        verify(trackableService).recordSuccessfulSignIn(6L, null);
    }

    @Test
    void ignoresBearerTokenAuthenticationsSoApiRequestsDoNotCountAsSignIns() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject("5").build();
        FfcrmAuthenticationToken bearer = new FfcrmAuthenticationToken(
            jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")), new AuthenticatedUser(5L, "legacy_plain", false));

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(bearer));

        verifyNoInteractions(trackableService);
    }

    @Test
    void ignoresPasswordAuthenticationsWhosePrincipalIsNotAFfcrmUser() {
        UsernamePasswordAuthenticationToken authentication =
            UsernamePasswordAuthenticationToken.authenticated("someone", null, List.of());

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(authentication));

        verifyNoInteractions(trackableService);
    }
}
