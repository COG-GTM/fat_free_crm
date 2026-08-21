package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Group;
import com.fatfreecrm.domain.User;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins how a Rails user row maps onto Spring Security's account flags and
 * authorities, mirroring Rails' {@code User#suspended?} / soft-delete
 * semantics and admin/group membership.
 */
class CrmUserDetailsTest {

    private static User user() {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", 7L);
        user.setUsername("legacy_user");
        user.setEmail("legacy_user@example.com");
        user.setEncryptedPassword("hash");
        user.setPasswordSalt("salt");
        return user;
    }

    private static Group group(String name) {
        Group group = new Group();
        group.setName(name);
        return group;
    }

    private static List<String> authorities(CrmUserDetails details) {
        return details.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void regularUserGetsOnlyTheUserRole() {
        assertThat(authorities(new CrmUserDetails(user(), List.of()))).containsExactly("ROLE_USER");
    }

    @Test
    void adminFlagGrantsTheAdminRole() {
        User user = user();
        user.setAdmin(true);
        assertThat(authorities(new CrmUserDetails(user, List.of()))).contains("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void groupMembershipsBecomeGroupAuthorities() {
        List<String> granted = authorities(new CrmUserDetails(user(), List.of(group("sales"), group("support"))));
        assertThat(granted).contains("GROUP_sales", "GROUP_support");
    }

    @Test
    void groupsWithoutANameAreSkippedInsteadOfFailing() {
        List<String> granted = authorities(new CrmUserDetails(user(), List.of(group(null))));
        assertThat(granted).containsExactly("ROLE_USER");
    }

    @Test
    void anUnflaggedUserIsFullyActive() {
        CrmUserDetails details = new CrmUserDetails(user(), List.of());
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
    }

    @Test
    void suspendedAtDisablesTheAccountLikeRailsSuspendedPredicate() {
        User user = user();
        user.setSuspendedAt(Instant.now());
        CrmUserDetails details = new CrmUserDetails(user, List.of());
        assertThat(details.isEnabled()).isFalse();
        assertThat(details.isAccountNonExpired()).isFalse();
        assertThat(details.isAccountNonLocked()).isFalse();
    }

    @Test
    void deletedAtDisablesTheAccountLikeRailsSoftDelete() {
        User user = user();
        user.setDeletedAt(Instant.now());
        assertThat(new CrmUserDetails(user, List.of()).isEnabled()).isFalse();
    }

    @Test
    void carriesTheLegacyHashAndSaltNeededForDeviseVerification() {
        CrmUserDetails details = new CrmUserDetails(user(), List.of());
        assertThat(details.getId()).isEqualTo(7L);
        assertThat(details.getUsername()).isEqualTo("legacy_user");
        assertThat(details.getEmail()).isEqualTo("legacy_user@example.com");
        assertThat(details.getPassword()).isEqualTo("hash");
        assertThat(details.getPasswordSalt()).isEqualTo("salt");
    }
}
