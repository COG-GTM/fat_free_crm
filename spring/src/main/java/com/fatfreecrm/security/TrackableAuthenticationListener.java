package com.fatfreecrm.security;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import java.time.Instant;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps Devise's {@code trackable} columns current so the Rails UI keeps
 * reporting accurate sign-in history while both stacks are live.
 */
@Component
public class TrackableAuthenticationListener {

    private final UserRepository userRepository;

    public TrackableAuthenticationListener(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @EventListener
    @Transactional
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        if (!(event.getAuthentication().getPrincipal() instanceof CrmUserDetails principal)) {
            return;
        }
        userRepository.findById(principal.getId()).ifPresent(user -> track(user, remoteAddress(event)));
    }

    private void track(User user, String remoteAddress) {
        Instant now = Instant.now();
        user.setLastSignInAt(user.getCurrentSignInAt() == null ? now : user.getCurrentSignInAt());
        user.setLastSignInIp(user.getCurrentSignInIp() == null ? remoteAddress : user.getCurrentSignInIp());
        user.setCurrentSignInAt(now);
        user.setCurrentSignInIp(remoteAddress);
        user.setSignInCount(user.getSignInCount() + 1);
        user.setUpdatedAt(now);
    }

    private String remoteAddress(AuthenticationSuccessEvent event) {
        if (event.getAuthentication().getDetails() instanceof WebAuthenticationDetails details) {
            return details.getRemoteAddress();
        }
        return null;
    }
}
