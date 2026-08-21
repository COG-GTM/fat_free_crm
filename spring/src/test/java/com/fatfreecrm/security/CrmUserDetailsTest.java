package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Group;
import com.fatfreecrm.domain.User;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

/**
 * Authority mapping mirrors the Rails permission model: every user is a plain
 * user, {@code admin} maps to the CanCanCan admin ability, and group
 * membership (Shared access) is carried as {@code GROUP_<name>} authorities.
 */
class CrmUserDetailsTest {

    private static User user(boolean admin) {
        User user = new User();
        user.setUsername("someone");
        user.setEmail("someone@example.com");
        user.setEncryptedPassword("hash");
        user.setPasswordSalt("salt");
        user.setAdmin(admin);
        return user;
    }

    private static Group group(String name) {
        Group group = new Group();
        group.setName(name);
        return group;
    }

    private static List<String> authorityNames(CrmUserDetails details) {
        return details.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void everyUserGetsRoleUser() {
        assertThat(authorityNames(new CrmUserDetails(user(false), List.of())))
            .containsExactly("ROLE_USER");
    }

    @Test
    void adminFlagAddsRoleAdmin() {
        assertThat(authorityNames(new CrmUserDetails(user(true), List.of())))
            .containsExactly("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void groupMembershipBecomesGroupAuthorities() {
        List<Group> groups = List.of(group("Sales"), group("Support"));
        assertThat(authorityNames(new CrmUserDetails(user(false), groups)))
            .containsExactly("ROLE_USER", "GROUP_Sales", "GROUP_Support");
    }

    @Test
    void namelessGroupsAreSkippedRatherThanBlowingUp() {
        assertThat(authorityNames(new CrmUserDetails(user(false), List.of(group(null)))))
            .containsExactly("ROLE_USER");
    }

    @Test
    void exposesTheLegacyHashAndSaltNeededForVerification() {
        CrmUserDetails details = new CrmUserDetails(user(false), List.of());
        assertThat(details.getPassword()).isEqualTo("hash");
        assertThat(details.getPasswordSalt()).isEqualTo("salt");
    }

    @Test
    void activeUserPassesAllAccountStatusChecks() {
        CrmUserDetails details = new CrmUserDetails(user(false), List.of());
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
    }

    @Test
    void suspendedUserFailsTheAccountStatusChecks() {
        User suspended = user(false);
        suspended.setSuspendedAt(Instant.now());
        CrmUserDetails details = new CrmUserDetails(suspended, List.of());
        assertThat(details.isEnabled()).isFalse();
        assertThat(details.isAccountNonExpired()).isFalse();
        assertThat(details.isAccountNonLocked()).isFalse();
    }
}
