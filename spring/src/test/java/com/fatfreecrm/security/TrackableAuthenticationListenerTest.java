package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins the Devise {@code trackable} column rotation: {@code current_*} moves to
 * {@code last_*} on each sign-in, exactly as the Rails UI expects to read it.
 */
class TrackableAuthenticationListenerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final TrackableAuthenticationListener listener = new TrackableAuthenticationListener(userRepository);

    private static User user() {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", 7L);
        user.setUsername("legacy_user");
        return user;
    }

    private static AuthenticationSuccessEvent successEventFor(User user) {
        CrmUserDetails principal = new CrmUserDetails(user, List.of());
        return new AuthenticationSuccessEvent(
            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    @Test
    void firstSignInSetsBothCurrentAndLastColumns() {
        User user = user();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        listener.onAuthenticationSuccess(successEventFor(user));

        assertThat(user.getSignInCount()).isEqualTo(1);
        assertThat(user.getCurrentSignInAt()).isNotNull();
        assertThat(user.getLastSignInAt()).isEqualTo(user.getCurrentSignInAt());
        assertThat(user.getUpdatedAt()).isEqualTo(user.getCurrentSignInAt());
    }

    @Test
    void repeatSignInRotatesCurrentIntoLast() {
        User user = user();
        Instant previous = Instant.parse("2020-01-01T00:00:00Z");
        user.setSignInCount(4);
        user.setCurrentSignInAt(previous);
        user.setCurrentSignInIp("10.0.0.1");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        listener.onAuthenticationSuccess(successEventFor(user));

        assertThat(user.getSignInCount()).isEqualTo(5);
        assertThat(user.getLastSignInAt()).isEqualTo(previous);
        assertThat(user.getLastSignInIp()).isEqualTo("10.0.0.1");
        assertThat(user.getCurrentSignInAt()).isAfter(previous);
    }

    @Test
    void ignoresAuthenticationsWithoutACrmPrincipal() {
        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(
            UsernamePasswordAuthenticationToken.authenticated("some-string-principal", null, List.of())));

        verifyNoInteractions(userRepository);
    }

    @Test
    void missingUserRowIsANoOpRatherThanAnError() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());

        listener.onAuthenticationSuccess(successEventFor(user()));
    }
}
