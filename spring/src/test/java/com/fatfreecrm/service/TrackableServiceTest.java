package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Mirrors Devise's {@code Trackable#update_tracked_fields}: {@code last_* = old current || new current},
 * {@code current_* = new}, {@code sign_in_count += 1}.
 */
@ExtendWith(MockitoExtension.class)
class TrackableServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-04T05:06:07.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-03-04T05:06:07.123456Z");
    private static final Instant EARLIER = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Test
    void firstSignInCopiesTheNewValuesIntoBothCurrentAndLastColumns() {
        TrackableService service = new TrackableService(userRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        User user = user(7L);
        user.setUpdatedAt(EARLIER);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

        service.recordSuccessfulSignIn(7L, "203.0.113.7");

        assertThat(user.getSignInCount()).isEqualTo(1);
        assertThat(user.getCurrentSignInAt()).isEqualTo(NOW_MICROS);
        assertThat(user.getLastSignInAt()).isEqualTo(NOW_MICROS);
        assertThat(user.getCurrentSignInIp()).isEqualTo("203.0.113.7");
        assertThat(user.getLastSignInIp()).isEqualTo("203.0.113.7");
        assertThat(user.getUpdatedAt()).isEqualTo(NOW_MICROS);
    }

    @Test
    void subsequentSignInRotatesCurrentIntoLastAndIncrementsTheCounter() {
        TrackableService service = new TrackableService(userRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        User user = user(7L);
        user.setSignInCount(4);
        user.setCurrentSignInAt(EARLIER);
        user.setLastSignInAt(EARLIER.minusSeconds(3600));
        user.setCurrentSignInIp("198.51.100.1");
        user.setLastSignInIp("198.51.100.0");
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

        service.recordSuccessfulSignIn(7L, "203.0.113.7");

        assertThat(user.getSignInCount()).isEqualTo(5);
        assertThat(user.getLastSignInAt()).isEqualTo(EARLIER);
        assertThat(user.getCurrentSignInAt()).isEqualTo(NOW_MICROS);
        assertThat(user.getLastSignInIp()).isEqualTo("198.51.100.1");
        assertThat(user.getCurrentSignInIp()).isEqualTo("203.0.113.7");
    }

    @Test
    void keepsAPreviousIpInLastWhenTheNewRequestHasNoRemoteAddress() {
        TrackableService service = new TrackableService(userRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        User user = user(7L);
        user.setCurrentSignInAt(EARLIER);
        user.setCurrentSignInIp("198.51.100.1");
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

        service.recordSuccessfulSignIn(7L, null);

        assertThat(user.getLastSignInIp()).isEqualTo("198.51.100.1");
        assertThat(user.getCurrentSignInIp()).isNull();
    }

    @Test
    void firstSignInWithoutARemoteAddressLeavesBothIpColumnsNull() {
        TrackableService service = new TrackableService(userRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        User user = user(7L);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

        service.recordSuccessfulSignIn(7L, null);

        assertThat(user.getSignInCount()).isEqualTo(1);
        assertThat(user.getCurrentSignInIp()).isNull();
        assertThat(user.getLastSignInIp()).isNull();
    }

    @Test
    void doesNothingWhenTheUserRowIsGone() {
        TrackableService service = new TrackableService(userRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(userRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        service.recordSuccessfulSignIn(99L, "203.0.113.7");

        verify(userRepository).findByIdForUpdate(99L);
    }

    private static User user(Long id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setUsername("legacy_plain");
        return user;
    }
}
