package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TrackableServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-10-07T12:00:00.123456Z");

    private final UserRepository userRepository = mock(UserRepository.class);
    private final TrackableService service = new TrackableService(userRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void firstSignInCopiesCurrentIntoLastLikeDeviseTrackable() {
        User user = user();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));

        service.recordSuccessfulSignIn(1L, "203.0.113.7");

        assertThat(user.getSignInCount()).isEqualTo(1);
        assertThat(user.getCurrentSignInAt()).isEqualTo(NOW_MICROS);
        assertThat(user.getLastSignInAt()).isEqualTo(NOW_MICROS);
        assertThat(user.getCurrentSignInIp()).isEqualTo("203.0.113.7");
        assertThat(user.getLastSignInIp()).isEqualTo("203.0.113.7");
        assertThat(user.getUpdatedAt()).isEqualTo(NOW_MICROS);
    }

    @Test
    void laterSignInsShiftThePreviousCurrentValuesIntoLast() {
        User user = user();
        Instant previous = Instant.parse("2026-01-01T00:00:00Z");
        user.setCurrentSignInAt(previous);
        user.setLastSignInAt(Instant.parse("2025-12-01T00:00:00Z"));
        user.setCurrentSignInIp("198.51.100.1");
        user.setLastSignInIp("198.51.100.0");
        user.setSignInCount(4);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));

        service.recordSuccessfulSignIn(1L, "203.0.113.7");

        assertThat(user.getSignInCount()).isEqualTo(5);
        assertThat(user.getLastSignInAt()).isEqualTo(previous);
        assertThat(user.getCurrentSignInAt()).isEqualTo(NOW_MICROS);
        assertThat(user.getLastSignInIp()).isEqualTo("198.51.100.1");
        assertThat(user.getCurrentSignInIp()).isEqualTo("203.0.113.7");
    }

    @Test
    void nullRemoteAddressIsStoredAsNullWithoutFailing() {
        User user = user();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));

        service.recordSuccessfulSignIn(1L, null);

        assertThat(user.getSignInCount()).isEqualTo(1);
        assertThat(user.getCurrentSignInIp()).isNull();
        assertThat(user.getLastSignInIp()).isNull();
    }

    @Test
    void previousIpIsKeptAsLastEvenWhenTheNewSignInHasNoAddress() {
        User user = user();
        user.setCurrentSignInAt(Instant.parse("2026-01-01T00:00:00Z"));
        user.setCurrentSignInIp("198.51.100.1");
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));

        service.recordSuccessfulSignIn(1L, null);

        assertThat(user.getLastSignInIp()).isEqualTo("198.51.100.1");
        assertThat(user.getCurrentSignInIp()).isNull();
    }

    @Test
    void missingUserIsANoOp() {
        when(userRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        service.recordSuccessfulSignIn(99L, "203.0.113.7");
    }

    @Test
    void neverTouchesPasswordColumns() {
        User user = user();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));

        service.recordSuccessfulSignIn(1L, "203.0.113.7");

        assertThat(user.getEncryptedPassword()).isEqualTo("hash");
        assertThat(user.getPasswordSalt()).isEqualTo("salt");
    }

    private static User user() {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", 1L);
        user.setUsername("legacy_plain");
        user.setEncryptedPassword("hash");
        user.setPasswordSalt("salt");
        user.setConfirmedAt(Instant.EPOCH);
        return user;
    }
}
