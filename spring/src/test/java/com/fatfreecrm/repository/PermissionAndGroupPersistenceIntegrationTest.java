package com.fatfreecrm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Group;
import com.fatfreecrm.domain.Permission;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Round-trips the {@code permissions} and {@code groups} mappings against the
 * real Flyway-baselined Rails schema, including the integer-keyed
 * {@code groups_users} join that cannot be a JPA association.
 */
@ActiveProfiles("test")
class PermissionAndGroupPersistenceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM permissions");
        jdbcTemplate.update("DELETE FROM groups_users");
        jdbcTemplate.update("DELETE FROM groups");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void permissionRoundTripsThroughTheRailsColumns() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Permission permission = new Permission();
        permission.setUserId(11);
        permission.setGroupId(22);
        permission.setAssetType("Account");
        permission.setAssetId(33);
        permission.setCreatedAt(now);
        permission.setUpdatedAt(now);

        Long id = permissionRepository.saveAndFlush(permission).getId();
        Permission reloaded = permissionRepository.findById(id).orElseThrow();

        assertThat(reloaded.getUserId()).isEqualTo(11);
        assertThat(reloaded.getGroupId()).isEqualTo(22);
        assertThat(reloaded.getAssetType()).isEqualTo("Account");
        assertThat(reloaded.getAssetId()).isEqualTo(33);
        assertThat(reloaded.getCreatedAt()).isEqualTo(now);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void permissionFindersResolveThePolymorphicAssetPair() {
        permissionRepository.saveAndFlush(permission(1, 100, "Account", 5));
        permissionRepository.saveAndFlush(permission(2, 200, "Contact", 5));
        permissionRepository.saveAndFlush(permission(1, 300, "Account", 6));

        assertThat(permissionRepository.findByAssetTypeAndAssetId("Account", 5))
            .hasSize(1)
            .allSatisfy(p -> assertThat(p.getUserId()).isEqualTo(1));
        assertThat(permissionRepository.findByUserId(1)).hasSize(2);
        assertThat(permissionRepository.findByGroupIdIn(List.of(100, 200))).hasSize(2);
        assertThat(permissionRepository.findByAssetTypeAndAssetId("Lead", 5)).isEmpty();
    }

    @Test
    void groupRoundTripsAndIsFoundByName() {
        Group group = new Group();
        group.setName("marketing");
        Long id = groupRepository.saveAndFlush(group).getId();

        assertThat(groupRepository.findById(id).orElseThrow().getName()).isEqualTo("marketing");
        assertThat(groupRepository.findByName("marketing")).isPresent();
        assertThat(groupRepository.findByName("nope")).isEmpty();
    }

    @Test
    void findGroupsForUserReadsTheIntegerKeyedJoinTableInIdOrder() {
        Group first = groupRepository.saveAndFlush(named("alpha"));
        Group second = groupRepository.saveAndFlush(named("beta"));
        groupRepository.saveAndFlush(named("unrelated"));

        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", second.getId(), 42L);
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", first.getId(), 42L);

        List<Group> groups = groupRepository.findGroupsForUser(42L);

        assertThat(groups).extracting(Group::getName).containsExactly("alpha", "beta");
        assertThat(groupRepository.findGroupsForUser(99L)).isEmpty();
    }

    private static Permission permission(int userId, int groupId, String assetType, int assetId) {
        Permission permission = new Permission();
        permission.setUserId(userId);
        permission.setGroupId(groupId);
        permission.setAssetType(assetType);
        permission.setAssetId(assetId);
        return permission;
    }

    private static Group named(String name) {
        Group group = new Group();
        group.setName(name);
        return group;
    }
}
