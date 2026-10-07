package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.User;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

class FfcrmUserDetailsTest {

    @Test
    void confirmedUnsuspendedUserIsEnabledAndUnlockedWithTheUserRole() {
        FfcrmUserDetails details = new FfcrmUserDetails(user(7L, "alice", false, Instant.EPOCH, null));

        assertThat(details.getUserId()).isEqualTo(7L);
        assertThat(details.getUsername()).isEqualTo("alice");
        assertThat(details.isAdmin()).isFalse();
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_USER");
    }

    @Test
    void adminUsersAlsoReceiveTheAdminRole() {
        FfcrmUserDetails details = new FfcrmUserDetails(user(1L, "root", true, Instant.EPOCH, null));

        assertThat(details.isAdmin()).isTrue();
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void unconfirmedUserIsDisabledLikeRailsActiveForAuthentication() {
        FfcrmUserDetails details = new FfcrmUserDetails(user(2L, "pending", false, null, null));

        assertThat(details.isEnabled()).isFalse();
        assertThat(details.isAccountNonLocked()).isTrue();
    }

    @Test
    void suspendedUserIsLockedLikeRailsActiveForAuthentication() {
        FfcrmUserDetails details = new FfcrmUserDetails(user(3L, "banned", false, Instant.EPOCH, Instant.EPOCH));

        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonLocked()).isFalse();
    }

    @Test
    void passwordIsTheDelegatingEncoderFormOfTheRailsHashAndSalt() {
        User user = user(4L, "legacy", false, Instant.EPOCH, null);
        user.setEncryptedPassword("abc123");
        user.setPasswordSalt("salty");

        FfcrmUserDetails details = new FfcrmUserDetails(user);

        assertThat(details.getPassword()).isEqualTo("{authlogic-sha512}abc123$salty");
    }

    @Test
    void nullHashAndSaltBecomeEmptyStringsSoTheEncoderRejectsThemInsteadOfThrowing() {
        User user = user(5L, "blank", false, Instant.EPOCH, null);
        user.setEncryptedPassword(null);
        user.setPasswordSalt(null);

        FfcrmUserDetails details = new FfcrmUserDetails(user);

        assertThat(details.getPassword()).isEqualTo("{authlogic-sha512}$");
        assertThat(new AuthlogicSha512PasswordEncoder(20).matches("anything", "$")).isFalse();
    }

    @Test
    void eraseCredentialsDropsThePasswordButKeepsIdentityAndAuthorities() {
        FfcrmUserDetails details = new FfcrmUserDetails(user(6L, "erase", true, Instant.EPOCH, null));

        details.eraseCredentials();

        assertThat(details.getPassword()).isNull();
        assertThat(details.getUserId()).isEqualTo(6L);
        assertThat(details.getAuthorities()).hasSize(2);
    }

    @Test
    void authoritiesAreACopyThatCannotBeMutatedByCallers() {
        FfcrmUserDetails details = new FfcrmUserDetails(user(8L, "copy", false, Instant.EPOCH, null));

        assertThat(details.getAuthorities()).isUnmodifiable();
    }

    static User user(Long id, String username, boolean admin, Instant confirmedAt, Instant suspendedAt) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setUsername(username);
        user.setAdmin(admin);
        user.setConfirmedAt(confirmedAt);
        user.setSuspendedAt(suspendedAt);
        user.setEncryptedPassword("hash");
        user.setPasswordSalt("salt");
        return user;
    }
}
