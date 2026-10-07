package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class FfcrmUserDetailsServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final FfcrmUserDetailsService service = new FfcrmUserDetailsService(userRepository);

    @Test
    void lowercasesTheLoginBeforeQueryingLikeRailsFindForDatabaseAuthentication() {
        User user = FfcrmUserDetailsTest.user(11L, "Legacy_MixedCase", false, Instant.EPOCH, null);
        when(userRepository.findByLogin(eq("legacy.mixed@example.com"), any(Limit.class))).thenReturn(List.of(user));

        FfcrmUserDetails details = service.loadUserByUsername("Legacy.Mixed@Example.COM");

        assertThat(details.getUserId()).isEqualTo(11L);
        assertThat(details.getUsername()).isEqualTo("Legacy_MixedCase");
        verify(userRepository).findByLogin("legacy.mixed@example.com", Limit.of(1));
    }

    @Test
    void stripsSurroundingWhitespaceFromTheLogin() {
        User user = FfcrmUserDetailsTest.user(12L, "legacy_plain", false, Instant.EPOCH, null);
        when(userRepository.findByLogin(eq("legacy_plain"), any(Limit.class))).thenReturn(List.of(user));

        assertThat(service.loadUserByUsername("  legacy_plain\t").getUserId()).isEqualTo(12L);
    }

    @Test
    void onlyAsksTheRepositoryForTheFirstMatchingRow() {
        when(userRepository.findByLogin(any(), any(Limit.class))).thenReturn(List.of(
            FfcrmUserDetailsTest.user(1L, "first", false, Instant.EPOCH, null),
            FfcrmUserDetailsTest.user(2L, "second", false, Instant.EPOCH, null)
        ));

        FfcrmUserDetails details = service.loadUserByUsername("first");

        assertThat(details.getUserId()).isEqualTo(1L);
        verify(userRepository).findByLogin("first", Limit.of(1));
    }

    @Test
    void throwsUsernameNotFoundWithoutLeakingTheLoginWhenNoRowMatches() {
        when(userRepository.findByLogin(any(), any(Limit.class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.loadUserByUsername("ghost@example.com"))
            .isInstanceOf(UsernameNotFoundException.class)
            .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain("ghost"));
    }

    @Test
    void doesNotFilterSuspendedOrUnconfirmedUsersSoTheProviderCanReportThemAsLockedOrDisabled() {
        User suspended = FfcrmUserDetailsTest.user(3L, "suspended", false, Instant.EPOCH, Instant.EPOCH);
        when(userRepository.findByLogin(eq("suspended"), any(Limit.class))).thenReturn(List.of(suspended));

        FfcrmUserDetails details = service.loadUserByUsername("suspended");

        assertThat(details.isAccountNonLocked()).isFalse();
    }
}
