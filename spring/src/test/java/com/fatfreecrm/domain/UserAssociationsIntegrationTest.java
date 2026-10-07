package com.fatfreecrm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.repository.GroupRepository;
import com.fatfreecrm.repository.PermissionRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the new JPA mappings against the Rails schema: {@code users}, {@code groups},
 * {@code groups_users} and {@code permissions} round-trip and link the way Rails wrote them.
 */
class UserAssociationsIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String USERNAME = "assoc_user";
    private static final String GROUP_NAME = "assoc-group";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM permissions WHERE asset_type = 'Account' AND asset_id = 4242");
        jdbcTemplate.update(
            "DELETE FROM groups_users WHERE group_id IN (SELECT id FROM groups WHERE name = ?)", GROUP_NAME);
        jdbcTemplate.update("DELETE FROM groups WHERE name = ?", GROUP_NAME);
        jdbcTemplate.update("DELETE FROM users WHERE username = ?", USERNAME);
    }

    @Test
    void savesANewUserThroughJpaAndReadsItBackWithRailsColumnNames() {
        Instant confirmedAt = Instant.parse("2024-02-03T04:05:06.123456Z");
        User user = new User();
        user.setUsername(USERNAME);
        user.setEmail(USERNAME + "@example.com");
        user.setFirstName("Assoc");
        user.setLastName("User");
        user.setEncryptedPassword("0".repeat(128));
        user.setPasswordSalt("salt-salt-salt-salt-");
        user.setConfirmedAt(confirmedAt);
        user.setCreatedAt(confirmedAt);
        user.setUpdatedAt(confirmedAt);

        User saved = userRepository.saveAndFlush(user);

        assertThat(saved.getId()).isNotNull();
        Map<String, Object> row = jdbcTemplate.queryForMap("""
            SELECT username, email, encrypted_password, password_salt, admin, sign_in_count, confirmed_at,
                   suspended_at, subscribe_to_comment_replies, receive_assigned_notifications
            FROM users WHERE id = ?
            """, saved.getId());
        assertThat(row.get("username")).isEqualTo(USERNAME);
        assertThat(row.get("email")).isEqualTo(USERNAME + "@example.com");
        assertThat(row.get("encrypted_password")).isEqualTo("0".repeat(128));
        assertThat(row.get("password_salt")).isEqualTo("salt-salt-salt-salt-");
        assertThat(row.get("admin")).isEqualTo(false);
        assertThat(row.get("sign_in_count")).isEqualTo(0);
        assertThat(row.get("suspended_at")).isNull();
        assertThat(((java.sql.Timestamp) row.get("confirmed_at")).toInstant()).isEqualTo(confirmedAt);

        User reloaded = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getConfirmedAt().truncatedTo(ChronoUnit.MICROS)).isEqualTo(confirmedAt);
        assertThat(reloaded.getSignInCount()).isZero();
        assertThat(reloaded.isAdmin()).isFalse();
        assertThat(reloaded.getCurrentSignInAt()).isNull();
    }

    @Test
    void loadsRailsGroupMembershipsAndPermissionsThroughTheMappedAssociations() {
        long userId = insertUser();
        long groupId = jdbcTemplate.queryForObject(
            "INSERT INTO groups (name, created_at, updated_at) VALUES (?, now(), now()) RETURNING id",
            Long.class, GROUP_NAME);
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", groupId, userId);
        long userPermissionId = jdbcTemplate.queryForObject("""
            INSERT INTO permissions (user_id, asset_type, asset_id, created_at, updated_at)
            VALUES (?, 'Account', 4242, now(), now()) RETURNING id
            """, Long.class, userId);
        long groupPermissionId = jdbcTemplate.queryForObject("""
            INSERT INTO permissions (group_id, asset_type, asset_id, created_at, updated_at)
            VALUES (?, 'Account', 4242, now(), now()) RETURNING id
            """, Long.class, groupId);

        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findById(userId).orElseThrow();
            assertThat(user.getGroups()).extracting(Group::getName).containsExactly(GROUP_NAME);
            assertThat(user.getPermissions()).extracting(Permission::getId).containsExactly(userPermissionId);
            assertThatThrownBy(() -> user.getGroups().clear()).isInstanceOf(UnsupportedOperationException.class);

            Group group = groupRepository.findById(groupId).orElseThrow();
            assertThat(group.getUsers()).extracting(User::getUsername).containsExactly(USERNAME);
            assertThat(group.getPermissions()).extracting(Permission::getId).containsExactly(groupPermissionId);

            Permission userPermission = permissionRepository.findById(userPermissionId).orElseThrow();
            assertThat(userPermission.getUser().getUsername()).isEqualTo(USERNAME);
            assertThat(userPermission.getGroup()).isNull();
            assertThat(userPermission.getAssetType()).isEqualTo("Account");
            assertThat(userPermission.getAssetId()).isEqualTo(4242);

            Permission groupPermission = permissionRepository.findById(groupPermissionId).orElseThrow();
            assertThat(groupPermission.getGroup().getName()).isEqualTo(GROUP_NAME);
            assertThat(groupPermission.getUser()).isNull();
        });
    }

    @Test
    void usersWithoutMembershipsExposeEmptyAssociations() {
        long userId = insertUser();

        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findById(userId).orElseThrow();
            assertThat(user.getGroups()).isEmpty();
            assertThat(user.getPermissions()).isEmpty();
        });
    }

    private long insertUser() {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users (username, email, encrypted_password, password_salt, admin, confirmed_at,
                               sign_in_count, created_at, updated_at)
            VALUES (?, ?, 'hash', 'salt', false, now(), 0, now(), now()) RETURNING id
            """, Long.class, USERNAME, USERNAME + "@example.com");
    }
}
