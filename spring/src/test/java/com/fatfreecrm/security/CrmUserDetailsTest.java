package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Group;
import com.fatfreecrm.domain.User;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

/**
 * Pins the mapping from a Rails user row to Spring Security's view of it,
 * including the Rails rule that a suspended or soft-deleted user cannot act.
 */
class CrmUserDetailsTest {

    private static User user() {
        User user = new User();
        user.setUsername("legacy_user");
        user.setEmail("legacy_user@example.com");
        user.setEncryptedPassword("stored-hash");
        user.setPasswordSalt("stored-salt");
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
    void everyUserGetsRoleUserAndOnlyAdminsGetRoleAdmin() {
        assertThat(authorityNames(new CrmUserDetails(user(), List.of())))
            .containsExactly("ROLE_USER");

        User admin = user();
        admin.setAdmin(true);
        assertThat(authorityNames(new CrmUserDetails(admin, List.of())))
            .containsExactly("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void groupMembershipBecomesGroupPrefixedAuthorities() {
        CrmUserDetails details = new CrmUserDetails(user(), List.of(group("sales"), group("support")));

        assertThat(authorityNames(details)).containsExactly("ROLE_USER", "GROUP_sales", "GROUP_support");
    }

    @Test
    void groupsWithoutANameGrantNoAuthority() {
        CrmUserDetails details = new CrmUserDetails(user(), List.of(group(null)));

        assertThat(authorityNames(details)).containsExactly("ROLE_USER");
    }

    @Test
    void carriesTheStoredHashAndSaltNeededForLegacyVerification() {
        CrmUserDetails details = new CrmUserDetails(user(), List.of());

        assertThat(details.getPassword()).isEqualTo("stored-hash");
        assertThat(details.getPasswordSalt()).isEqualTo("stored-salt");
        assertThat(details.getUsername()).isEqualTo("legacy_user");
        assertThat(details.getEmail()).isEqualTo("legacy_user@example.com");
    }

    @Test
    void activeUserIsEnabledAndUnlocked() {
        CrmUserDetails details = new CrmUserDetails(user(), List.of());

        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
    }

    @Test
    void suspendedUserIsDisabledLikeInRails() {
        User suspended = user();
        suspended.setSuspendedAt(Instant.now());
        CrmUserDetails details = new CrmUserDetails(suspended, List.of());

        assertThat(details.isEnabled()).isFalse();
        assertThat(details.isAccountNonLocked()).isFalse();
        assertThat(details.isAccountNonExpired()).isFalse();
    }

    @Test
    void softDeletedUserIsDisabledLikeInRails() {
        User deleted = user();
        deleted.setDeletedAt(Instant.now());
        CrmUserDetails details = new CrmUserDetails(deleted, List.of());

        assertThat(deleted.isActive()).isFalse();
        assertThat(details.isEnabled()).isFalse();
    }
}
