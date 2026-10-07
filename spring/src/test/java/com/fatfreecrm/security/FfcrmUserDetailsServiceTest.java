package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Rails resolves the login with {@code lower(username) = :value OR lower(email) = :value} after
 * Devise downcases the {@code email} authentication key; the Java side must normalize the same way
 * before hitting the repository because the JPQL query compares against an already-lowercased value.
 */
@ExtendWith(MockitoExtension.class)
class FfcrmUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void lowercasesAndStripsTheLoginBeforeQueryingAndLimitsTheLookupToOneRow() {
        FfcrmUserDetailsService service = new FfcrmUserDetailsService(userRepository);
        User user = FfcrmUserDetailsTest.user(3L, "Legacy_MixedCase", false, Instant.now(), null);
        when(userRepository.findByLogin(eq("legacy_mixedcase"), any(Limit.class))).thenReturn(List.of(user));

        FfcrmUserDetails details = service.loadUserByUsername("  Legacy_MixedCase  ");

        assertThat(details.getUserId()).isEqualTo(3L);
        assertThat(details.getUsername()).isEqualTo("Legacy_MixedCase");
        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(userRepository).findByLogin(eq("legacy_mixedcase"), limit.capture());
        assertThat(limit.getValue().isLimited()).isTrue();
        assertThat(limit.getValue().max()).isEqualTo(1);
    }

    @Test
    void lowercasesEmailLoginsIncludingNonAsciiLetters() {
        FfcrmUserDetailsService service = new FfcrmUserDetailsService(userRepository);
        User user = FfcrmUserDetailsTest.user(4L, "legacy_unicode", false, Instant.now(), null);
        when(userRepository.findByLogin(eq("ünïcode.user@example.com"), any(Limit.class)))
            .thenReturn(List.of(user));

        assertThat(service.loadUserByUsername("ÜNÏCODE.User@Example.COM").getUserId()).isEqualTo(4L);
    }

    @Test
    void throwsUsernameNotFoundWhenNoRowMatchesSoDaoProviderReportsBadCredentials() {
        FfcrmUserDetailsService service = new FfcrmUserDetailsService(userRepository);
        when(userRepository.findByLogin(anyString(), any(Limit.class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.loadUserByUsername("nobody"))
            .isInstanceOf(UsernameNotFoundException.class)
            .hasMessageNotContaining("nobody");
    }

    @Test
    void usesTheFirstRowWhenTheRepositoryReturnsSeveralMatches() {
        FfcrmUserDetailsService service = new FfcrmUserDetailsService(userRepository);
        User first = FfcrmUserDetailsTest.user(1L, "first", false, Instant.now(), null);
        User second = FfcrmUserDetailsTest.user(2L, "second", false, Instant.now(), null);
        when(userRepository.findByLogin(anyString(), any(Limit.class))).thenReturn(List.of(first, second));

        assertThat(service.loadUserByUsername("first").getUserId()).isEqualTo(1L);
    }
}
