package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.support.TestUsers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrackableServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-10-07T12:00:00.123456Z");
    private static final Instant EARLIER = Instant.parse("2026-10-01T08:30:00Z");

    @Mock
    private UserRepository userRepository;

    private TrackableService service() {
        return new TrackableService(userRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void firstSignInCopiesCurrentIntoLastLikeDeviseTrackable() {
        User user = TestUsers.activeUser(1L, "legacy_plain", false);
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));

        service().recordSuccessfulSignIn(1L, "203.0.113.7");

        assertThat(user.getSignInCount()).isEqualTo(1);
        assertThat(user.getCurrentSignInAt()).isEqualTo(NOW_MICROS);
        assertThat(user.getLastSignInAt()).isEqualTo(NOW_MICROS);
        assertThat(user.getCurrentSignInIp()).isEqualTo("203.0.113.7");
        assertThat(user.getLastSignInIp()).isEqualTo("203.0.113.7");
        assertThat(user.getUpdatedAt()).isEqualTo(NOW_MICROS);
    }

    @Test
    void subsequentSignInShiftsThePreviousCurrentValuesIntoLast() {
        User user = TestUsers.activeUser(1L, "legacy_plain", false);
        user.setSignInCount(4);
        user.setCurrentSignInAt(EARLIER);
        user.setLastSignInAt(Instant.parse("2026-09-01T00:00:00Z"));
        user.setCurrentSignInIp("198.51.100.1");
        user.setLastSignInIp("192.0.2.1");
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));

        service().recordSuccessfulSignIn(1L, "203.0.113.7");

        assertThat(user.getSignInCount()).isEqualTo(5);
        assertThat(user.getLastSignInAt()).isEqualTo(EARLIER);
        assertThat(user.getCurrentSignInAt()).isEqualTo(NOW_MICROS);
        assertThat(user.getLastSignInIp()).isEqualTo("198.51.100.1");
        assertThat(user.getCurrentSignInIp()).isEqualTo("203.0.113.7");
    }

    @Test
    void acceptsAMissingRemoteAddressAndKeepsThePreviousIpAsLast() {
        User user = TestUsers.activeUser(1L, "legacy_plain", false);
        user.setCurrentSignInAt(EARLIER);
        user.setCurrentSignInIp("198.51.100.1");
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));

        service().recordSuccessfulSignIn(1L, null);

        assertThat(user.getCurrentSignInIp()).isNull();
        assertThat(user.getLastSignInIp()).isEqualTo("198.51.100.1");
        assertThat(user.getSignInCount()).isEqualTo(1);
    }

    @Test
    void neverTouchesPasswordColumns() {
        User user = TestUsers.activeUser(1L, "legacy_plain", false);
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));

        service().recordSuccessfulSignIn(1L, "203.0.113.7");

        assertThat(user.getEncryptedPassword()).isEqualTo("0".repeat(128));
        assertThat(user.getPasswordSalt()).isEqualTo("salt-salt-salt-salt-");
        assertThat(user.getConfirmedAt()).isEqualTo(TestUsers.CONFIRMED_AT);
        assertThat(user.getSuspendedAt()).isNull();
    }

    @Test
    void ignoresUnknownUsersAndOnlyUsesTheLockingLookup() {
        given(userRepository.findByIdForUpdate(99L)).willReturn(Optional.empty());

        service().recordSuccessfulSignIn(99L, "203.0.113.7");

        verify(userRepository).findByIdForUpdate(99L);
        verifyNoMoreInteractions(userRepository);
    }
}
