package com.fatfreecrm.security;

import com.fatfreecrm.service.TrackableService;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

@Component
public class TrackableAuthenticationListener {

    private final TrackableService trackableService;

    public TrackableAuthenticationListener(TrackableService trackableService) {
        this.trackableService = trackableService;
    }

    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        if (event.getAuthentication() instanceof UsernamePasswordAuthenticationToken authentication
            && authentication.getPrincipal() instanceof FfcrmUserDetails userDetails) {
            trackableService.recordSuccessfulSignIn(userDetails.getUserId(), remoteAddress(authentication));
        }
    }

    private String remoteAddress(UsernamePasswordAuthenticationToken authentication) {
        if (authentication.getDetails() instanceof WebAuthenticationDetails details) {
            return details.getRemoteAddress();
        }
        return null;
    }
}
