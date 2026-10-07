package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.support.LegacyAuthFixtures;
import com.fatfreecrm.support.LegacyAuthFixtures.LegacyUser;
import com.fatfreecrm.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

class FfcrmUserDetailsTest {

    @Test
    void mirrorsRailsActiveForAuthenticationForConfirmedUnsuspendedUsers() {
        FfcrmUserDetails details = new FfcrmUserDetails(TestUsers.activeUser(42L, "legacy_plain", false));

        assertThat(details.getUserId()).isEqualTo(42L);
        assertThat(details.getUsername()).isEqualTo("legacy_plain");
        assertThat(details.isAdmin()).isFalse();
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
        assertThat(details.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_USER");
    }

    @Test
    void disablesUnconfirmedUsersLikeRailsConfirmable() {
        FfcrmUserDetails details = new FfcrmUserDetails(TestUsers.unconfirmedUser(1L, "legacy_unconfirmed"));

        assertThat(details.isEnabled()).isFalse();
        assertThat(details.isAccountNonLocked()).isTrue();
    }

    @Test
    void locksSuspendedUsersLikeRailsSuspendedAt() {
        FfcrmUserDetails details = new FfcrmUserDetails(TestUsers.suspendedUser(1L, "legacy_suspended"));

        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonLocked()).isFalse();
    }

    @Test
    void grantsRoleAdminOnlyFromTheUsersAdminColumn() {
        FfcrmUserDetails admin = new FfcrmUserDetails(TestUsers.activeUser(2L, "legacy_admin", true));

        assertThat(admin.isAdmin()).isTrue();
        assertThat(admin.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_USER", "ROLE_ADMIN");
        assertThatThrownBy(() -> admin.getAuthorities().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void encodesTheLegacyHashAndSaltInMemoryUsingTheDelegatingEncoderId() {
        LegacyUser fixture = LegacyAuthFixtures.user("legacy_dollar");
        User user = TestUsers.activeUser(3L, fixture.username(), false);
        user.setEncryptedPassword(fixture.encryptedPassword());
        user.setPasswordSalt(fixture.passwordSalt());

        FfcrmUserDetails details = new FfcrmUserDetails(user);

        assertThat(details.getPassword())
            .isEqualTo("{authlogic-sha512}" + fixture.encryptedPassword() + "$" + fixture.passwordSalt());
        PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder(LegacyAuthFixtures.stretches());
        assertThat(encoder.matches(fixture.password(), details.getPassword())).isTrue();
        assertThat(encoder.matches(fixture.password() + "wrong", details.getPassword())).isFalse();
        // DelegatingPasswordEncoder flags legacy hashes for upgrade; nothing wires a
        // UserDetailsPasswordService, so AuthenticationIntegrationTest pins that no rehash happens.
        assertThat(encoder.upgradeEncoding(details.getPassword())).isTrue();
    }

    @Test
    void treatsMissingPasswordColumnsAsEmptyAndNeverMatchesThem() {
        User user = TestUsers.activeUser(4L, "legacy_blank", false);
        user.setEncryptedPassword(null);
        user.setPasswordSalt(null);

        FfcrmUserDetails details = new FfcrmUserDetails(user);

        assertThat(details.getPassword()).isEqualTo("{authlogic-sha512}$");
        PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder(20);
        assertThat(encoder.matches("", details.getPassword())).isFalse();
        assertThat(encoder.matches("password", details.getPassword())).isFalse();
    }

    @Test
    void erasesCredentialsAfterAuthentication() {
        FfcrmUserDetails details = new FfcrmUserDetails(TestUsers.activeUser(5L, "legacy_plain", false));

        details.eraseCredentials();

        assertThat(details.getPassword()).isNull();
        assertThat(details.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_USER");
    }
}
