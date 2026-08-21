package com.fatfreecrm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Group;
import com.fatfreecrm.domain.Permission;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Exercises the Group/Permission repositories against the real Rails schema,
 * including the legacy {@code groups_users} join table with its
 * {@code integer} foreign keys that cannot be a JPA association.
 */
@ActiveProfiles("test")
class GroupAndPermissionRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long userId;
    private long otherUserId;
    private long salesGroupId;
    private long supportGroupId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM permissions");
        jdbcTemplate.update("DELETE FROM groups_users");
        jdbcTemplate.update("DELETE FROM groups");
        jdbcTemplate.update("DELETE FROM users");
        userId = insertUser("member");
        otherUserId = insertUser("loner");
        salesGroupId = insertGroup("Sales");
        supportGroupId = insertGroup("Support");
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", salesGroupId, userId);
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", supportGroupId, userId);
    }

    private long insertUser(String username) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject("""
            INSERT INTO users (username, email, encrypted_password, password_salt, admin,
                               sign_in_count, created_at, updated_at)
            VALUES (?, ?, 'hash', 'salt', false, 0, now(), now()) RETURNING id
            """, Long.class, username, username + "@example.com"));
    }

    private long insertGroup(String name) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
            "INSERT INTO groups (name, created_at, updated_at) VALUES (?, now(), now()) RETURNING id",
            Long.class, name));
    }

    @Test
    void findGroupsForUserReadsTheLegacyJoinTableInIdOrder() {
        List<Group> groups = groupRepository.findGroupsForUser(userId);
        assertThat(groups).extracting(Group::getName).containsExactly("Sales", "Support");
    }

    @Test
    void findGroupsForUserReturnsNothingForNonMembers() {
        assertThat(groupRepository.findGroupsForUser(otherUserId)).isEmpty();
        assertThat(groupRepository.findGroupsForUser(-1L)).isEmpty();
    }

    @Test
    void findByNameMatchesExactly() {
        assertThat(groupRepository.findByName("Sales")).isPresent();
        assertThat(groupRepository.findByName("sales")).isEmpty();
        assertThat(groupRepository.findByName("Nope")).isEmpty();
    }

    @Test
    void permissionRoundTripsThroughTheRailsPermissionsTable() {
        Permission permission = new Permission();
        permission.setUserId((int) userId);
        permission.setAssetType("Account");
        permission.setAssetId(42);
        Permission saved = permissionRepository.saveAndFlush(permission);

        Permission reloaded = permissionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getUserId()).isEqualTo((int) userId);
        assertThat(reloaded.getGroupId()).isNull();
        assertThat(reloaded.getAssetType()).isEqualTo("Account");
        assertThat(reloaded.getAssetId()).isEqualTo(42);
    }

    @Test
    void permissionFindersFilterByAssetUserAndGroups() {
        insertPermission((int) userId, null, "Account", 42);
        insertPermission(null, (int) salesGroupId, "Account", 42);
        insertPermission((int) otherUserId, null, "Contact", 7);

        assertThat(permissionRepository.findByAssetTypeAndAssetId("Account", 42)).hasSize(2);
        assertThat(permissionRepository.findByAssetTypeAndAssetId("Account", 999)).isEmpty();
        assertThat(permissionRepository.findByUserId((int) userId))
            .singleElement()
            .satisfies(p -> assertThat(p.getAssetType()).isEqualTo("Account"));
        assertThat(permissionRepository.findByGroupIdIn(List.of((int) salesGroupId, (int) supportGroupId)))
            .singleElement()
            .satisfies(p -> assertThat(p.getGroupId()).isEqualTo((int) salesGroupId));
        assertThat(permissionRepository.findByGroupIdIn(List.of())).isEmpty();
    }

    private void insertPermission(Integer permUserId, Integer groupId, String assetType, Integer assetId) {
        jdbcTemplate.update("""
            INSERT INTO permissions (user_id, group_id, asset_type, asset_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, now(), now())
            """, permUserId, groupId, assetType, assetId);
    }
}
