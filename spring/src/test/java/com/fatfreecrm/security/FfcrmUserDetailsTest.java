package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.User;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins how a Rails {@code users} row is projected onto Spring Security's {@link UserDetails} contract.
 * Rails: {@code active_for_authentication?} is {@code confirmed? && !suspended?}.
 */
class FfcrmUserDetailsTest {

    private static final Instant CONFIRMED = Instant.parse("2024-01-01T00:00:00Z");

    @Test
    void exposesRailsPasswordColumnsAsAnAuthlogicPrefixedEncodedPassword() {
        User user = user(7L, "legacy_plain", false, CONFIRMED, null);
        user.setEncryptedPassword("abc123");
        user.setPasswordSalt("L_PQcwgmR-fy6LaQQevh");

        FfcrmUserDetails details = new FfcrmUserDetails(user);

        assertThat(details.getPassword()).isEqualTo("{authlogic-sha512}abc123$L_PQcwgmR-fy6LaQQevh");
        assertThat(details.getUserId()).isEqualTo(7L);
        assertThat(details.getUsername()).isEqualTo("legacy_plain");
    }

    @Test
    void treatsNullPasswordColumnsAsEmptyStringsSoMatchingFailsInsteadOfThrowing() {
        User user = user(7L, "legacy_plain", false, CONFIRMED, null);

        FfcrmUserDetails details = new FfcrmUserDetails(user);

        assertThat(details.getPassword()).isEqualTo("{authlogic-sha512}$");
        assertThat(new AuthlogicSha512PasswordEncoder(1).matches("anything", "$")).isFalse();
    }

    @Test
    void confirmedUnsuspendedUserIsEnabledAndUnlocked() {
        FfcrmUserDetails details = new FfcrmUserDetails(user(1L, "active", false, CONFIRMED, null));

        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
    }

    @Test
    void unconfirmedUserIsDisabledLikeRailsConfirmable() {
        FfcrmUserDetails details = new FfcrmUserDetails(user(1L, "unconfirmed", false, null, null));

        assertThat(details.isEnabled()).isFalse();
        assertThat(details.isAccountNonLocked()).isTrue();
    }

    @Test
    void suspendedUserIsLockedLikeRailsSuspendedAt() {
        FfcrmUserDetails details = new FfcrmUserDetails(user(1L, "suspended", false, CONFIRMED, Instant.now()));

        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonLocked()).isFalse();
    }

    @Test
    void grantsRoleUserToEveryoneAndRoleAdminOnlyToRailsAdmins() {
        FfcrmUserDetails member = new FfcrmUserDetails(user(1L, "member", false, CONFIRMED, null));
        FfcrmUserDetails admin = new FfcrmUserDetails(user(2L, "admin", true, CONFIRMED, null));

        assertThat(member.isAdmin()).isFalse();
        assertThat(member.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_USER");
        assertThat(admin.isAdmin()).isTrue();
        assertThat(admin.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void eraseCredentialsDropsThePasswordHashFromThePrincipal() {
        User user = user(1L, "member", false, CONFIRMED, null);
        user.setEncryptedPassword("abc");
        user.setPasswordSalt("salt");
        FfcrmUserDetails details = new FfcrmUserDetails(user);

        details.eraseCredentials();

        assertThat(details.getPassword()).isNull();
        assertThat(details.getUsername()).isEqualTo("member");
    }

    static User user(Long id, String username, boolean admin, Instant confirmedAt, Instant suspendedAt) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setUsername(username);
        user.setAdmin(admin);
        user.setConfirmedAt(confirmedAt);
        user.setSuspendedAt(suspendedAt);
        return user;
    }
}
