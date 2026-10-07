package com.fatfreecrm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Group;
import com.fatfreecrm.domain.Permission;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class UserRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PREFIX = "repo_it_";
    private static final String INSERT_USER = """
        INSERT INTO users (username, email, encrypted_password, password_salt, admin, confirmed_at,
                           sign_in_count, created_at, updated_at)
        VALUES (?, ?, 'hash', 'salt', ?, now(), 0, now(), now())
        RETURNING id
        """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM permissions WHERE user_id IN (SELECT id FROM users WHERE username LIKE ?)",
            PREFIX + "%");
        jdbcTemplate.update("DELETE FROM groups_users WHERE user_id IN (SELECT id FROM users WHERE username LIKE ?)",
            PREFIX + "%");
        jdbcTemplate.update("DELETE FROM groups WHERE name LIKE ?", PREFIX + "%");
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE ? OR email LIKE ?", PREFIX + "%", PREFIX + "%");
    }

    @Test
    void findByLoginMatchesLowercasedUsernameOrEmailAndExpectsTheCallerToNormalize() {
        long id = insertUser(PREFIX + "MixedCase", PREFIX + "Mixed@Example.COM", false);

        assertThat(userRepository.findByLogin(PREFIX.toLowerCase() + "mixedcase", Limit.of(1)))
            .extracting(User::getId).containsExactly(id);
        assertThat(userRepository.findByLogin(PREFIX + "mixed@example.com", Limit.of(1)))
            .extracting(User::getId).containsExactly(id);
        assertThat(userRepository.findByLogin(PREFIX + "MixedCase", Limit.of(1))).isEmpty();
        assertThat(userRepository.findByLogin(" " + PREFIX + "mixedcase", Limit.of(1))).isEmpty();
        assertThat(userRepository.findByLogin(PREFIX + "mixedcase%", Limit.of(1))).isEmpty();
    }

    @Test
    void findByLoginReturnsTheLowestIdFirstWhenAUsernameAndAnEmailBothMatchLikeRailsFirst() {
        String login = PREFIX + "shared";
        long byUsername = insertUser(login, PREFIX + "shared_owner@example.com", false);
        long byEmail = insertUser(PREFIX + "other", login, false);

        List<User> ordered = userRepository.findByLogin(login, Limit.of(10));
        assertThat(ordered).extracting(User::getId).containsExactly(byUsername, byEmail);
        assertThat(userRepository.findByLogin(login, Limit.of(1))).extracting(User::getId).containsExactly(byUsername);
    }

    @Test
    void findByIdForUpdateLoadsTheRowInsideATransactionAndIsEmptyForUnknownIds() {
        long id = insertUser(PREFIX + "lockme", PREFIX + "lockme@example.com", true);

        Optional<User> locked = transactionTemplate.execute(status -> userRepository.findByIdForUpdate(id));
        Optional<User> missing = transactionTemplate.execute(status -> userRepository.findByIdForUpdate(id + 100_000));

        assertThat(locked).isPresent();
        assertThat(locked.orElseThrow().getUsername()).isEqualTo(PREFIX + "lockme");
        assertThat(locked.orElseThrow().isAdmin()).isTrue();
        assertThat(missing).isEmpty();
    }

    @Test
    void trackableInstantsRoundTripThroughTimestampWithoutTimeZoneColumnsAsUtc() {
        long id = insertUser(PREFIX + "clock", PREFIX + "clock@example.com", false);
        Instant signedInAt = Instant.parse("2026-03-04T05:06:07.123456Z");

        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findByIdForUpdate(id).orElseThrow();
            user.setCurrentSignInAt(signedInAt);
            user.setLastSignInAt(signedInAt);
            user.setCurrentSignInIp("203.0.113.7");
            user.setSignInCount(1);
        });

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT current_sign_in_at, last_sign_in_at, current_sign_in_ip, sign_in_count, encrypted_password "
                + "FROM users WHERE id = ?", id);
        assertThat(((Timestamp) row.get("current_sign_in_at")).toInstant()).isEqualTo(signedInAt);
        assertThat(((Timestamp) row.get("last_sign_in_at")).toInstant()).isEqualTo(signedInAt);
        assertThat(row.get("current_sign_in_ip")).isEqualTo("203.0.113.7");
        assertThat(row.get("sign_in_count")).isEqualTo(1);
        assertThat(row.get("encrypted_password")).isEqualTo("hash");
        assertThat(userRepository.findById(id).orElseThrow().getCurrentSignInAt()).isEqualTo(signedInAt);
    }

    @Test
    void groupAndPermissionAssociationsMapTheRailsJoinTables() {
        long userId = insertUser(PREFIX + "member", PREFIX + "member@example.com", false);
        long outsiderId = insertUser(PREFIX + "outsider", PREFIX + "outsider@example.com", false);
        long groupId = jdbcTemplate.queryForObject(
            "INSERT INTO groups (name, created_at, updated_at) VALUES (?, now(), now()) RETURNING id",
            Long.class, PREFIX + "group");
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", groupId, userId);
        long permissionId = jdbcTemplate.queryForObject("""
            INSERT INTO permissions (user_id, group_id, asset_type, asset_id, created_at, updated_at)
            VALUES (?, ?, 'Account', 123, now(), now()) RETURNING id
            """, Long.class, userId, groupId);

        transactionTemplate.executeWithoutResult(status -> {
            User member = userRepository.findById(userId).orElseThrow();
            assertThat(member.getGroups()).extracting(Group::getName).containsExactly(PREFIX + "group");
            assertThat(member.getPermissions()).extracting(Permission::getAssetType).containsExactly("Account");
            assertThat(member.getPermissions()).extracting(Permission::getAssetId).containsExactly(123);

            User outsider = userRepository.findById(outsiderId).orElseThrow();
            assertThat(outsider.getGroups()).isEmpty();
            assertThat(outsider.getPermissions()).isEmpty();

            Group group = groupRepository.findById(groupId).orElseThrow();
            assertThat(group.getUsers()).extracting(User::getId).containsExactly(userId);
            assertThat(group.getPermissions()).extracting(Permission::getId).containsExactly(permissionId);

            Permission permission = permissionRepository.findById(permissionId).orElseThrow();
            assertThat(permission.getUser().getId()).isEqualTo(userId);
            assertThat(permission.getGroup().getId()).isEqualTo(groupId);
        });
    }

    @Test
    void permissionsWithoutAGroupLoadWithANullGroupLikeRailsOptionalBelongsTo() {
        long userId = insertUser(PREFIX + "solo", PREFIX + "solo@example.com", false);
        long permissionId = jdbcTemplate.queryForObject("""
            INSERT INTO permissions (user_id, asset_type, asset_id, created_at, updated_at)
            VALUES (?, 'Contact', 7, now(), now()) RETURNING id
            """, Long.class, userId);

        transactionTemplate.executeWithoutResult(status -> {
            Permission permission = permissionRepository.findById(permissionId).orElseThrow();
            assertThat(permission.getGroup()).isNull();
            assertThat(permission.getUser().getId()).isEqualTo(userId);
        });
    }

    private long insertUser(String username, String email, boolean admin) {
        return jdbcTemplate.queryForObject(INSERT_USER, Long.class, username, email, admin);
    }
}
