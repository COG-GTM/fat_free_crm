package com.fatfreecrm.security;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fatfreecrm.service.TrackableService;
import com.fatfreecrm.support.TestUsers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

@ExtendWith(MockitoExtension.class)
class TrackableAuthenticationListenerTest {

    @Mock
    private TrackableService trackableService;

    @InjectMocks
    private TrackableAuthenticationListener listener;

    @Test
    void recordsPasswordSignInsWithTheRequestRemoteAddress() {
        FfcrmUserDetails details = new FfcrmUserDetails(TestUsers.activeUser(42L, "legacy_plain", false));
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
            details, null, details.getAuthorities());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        authentication.setDetails(new WebAuthenticationDetails(request));

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(authentication));

        verify(trackableService).recordSuccessfulSignIn(42L, "203.0.113.7");
    }

    @Test
    void recordsPasswordSignInsWithoutWebDetailsUsingANullAddress() {
        FfcrmUserDetails details = new FfcrmUserDetails(TestUsers.activeUser(42L, "legacy_plain", false));
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
            details, null, details.getAuthorities());

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(authentication));

        verify(trackableService).recordSuccessfulSignIn(42L, null);
    }

    @Test
    void ignoresBearerTokenAuthenticationsSoApiRequestsAreNotTracked() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject("42").build();
        FfcrmAuthenticationToken bearer = new FfcrmAuthenticationToken(
            jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")), new AuthenticatedUser(42L, "legacy_plain", false));
        JwtAuthenticationToken plainJwt = new JwtAuthenticationToken(jwt);

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(bearer));
        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(plainJwt));

        verifyNoInteractions(trackableService);
    }

    @Test
    void ignoresPasswordAuthenticationsWhosePrincipalIsNotAFfcrmUser() {
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
            "legacy_plain", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(authentication));

        verifyNoInteractions(trackableService);
    }
}
