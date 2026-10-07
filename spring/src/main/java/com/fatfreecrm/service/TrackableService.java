package com.fatfreecrm.service;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackableService {

    private final UserRepository userRepository;
    private final Clock clock;

    public TrackableService(UserRepository userRepository, Clock clock) {
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public void recordSuccessfulSignIn(Long userId, String remoteAddress) {
        userRepository.findByIdForUpdate(userId).ifPresent(user -> updateTrackable(user, remoteAddress));
    }

    private void updateTrackable(User user, String remoteAddress) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        user.setLastSignInAt(user.getCurrentSignInAt() == null ? now : user.getCurrentSignInAt());
        user.setLastSignInIp(user.getCurrentSignInIp() == null ? remoteAddress : user.getCurrentSignInIp());
        user.setCurrentSignInAt(now);
        user.setCurrentSignInIp(remoteAddress);
        user.setSignInCount(user.getSignInCount() + 1);
        user.setUpdatedAt(now);
    }
}
