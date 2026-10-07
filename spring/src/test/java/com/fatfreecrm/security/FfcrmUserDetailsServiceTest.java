package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.support.TestUsers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@ExtendWith(MockitoExtension.class)
class FfcrmUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private FfcrmUserDetailsService service;

    @Test
    void stripsAndDowncasesTheLoginBeforeQueryingLikeDeviseCaseInsensitiveKeys() {
        given(userRepository.findByLogin("legacy_mixedcase", Limit.of(1)))
            .willReturn(List.of(TestUsers.activeUser(3L, "Legacy_MixedCase", false)));

        FfcrmUserDetails details = service.loadUserByUsername("  Legacy_MixedCase\t\n");

        assertThat(details.getUserId()).isEqualTo(3L);
        assertThat(details.getUsername()).isEqualTo("Legacy_MixedCase");
        verify(userRepository).findByLogin("legacy_mixedcase", Limit.of(1));
    }

    @Test
    void acceptsAnEmailAddressAsTheLogin() {
        given(userRepository.findByLogin("legacy.mixed@example.com", Limit.of(1)))
            .willReturn(List.of(TestUsers.activeUser(3L, "Legacy_MixedCase", false)));

        assertThat(service.loadUserByUsername(" LEGACY.MIXED@EXAMPLE.COM ").getUserId()).isEqualTo(3L);
    }

    @Test
    void throwsUsernameNotFoundWhenNoRowMatches() {
        given(userRepository.findByLogin(anyString(), eq(Limit.of(1)))).willReturn(List.of());

        assertThatThrownBy(() -> service.loadUserByUsername("missing"))
            .isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> service.loadUserByUsername("   "))
            .isInstanceOf(UsernameNotFoundException.class);
        verify(userRepository).findByLogin("", Limit.of(1));
    }

    @Test
    void returnsTheFirstRowWhenTheQueryYieldsSeveralMatches() {
        given(userRepository.findByLogin("shared", Limit.of(1))).willReturn(List.of(
            TestUsers.activeUser(1L, "shared", false),
            TestUsers.activeUser(2L, "other", false)
        ));

        assertThat(service.loadUserByUsername("shared").getUserId()).isEqualTo(1L);
    }
}
