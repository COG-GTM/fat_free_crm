package com.fatfreecrm.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.Group;
import com.fatfreecrm.domain.Permission;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persistence-layer coverage for the JPA mapping of the Rails {@code users}, {@code groups},
 * {@code groups_users} and {@code permissions} tables introduced for authentication.
 */
class UserRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String INSERT_USER = """
        INSERT INTO users (username, email, encrypted_password, password_salt, admin, confirmed_at,
                           sign_in_count, created_at, updated_at)
        VALUES (?, ?, 'hash', 'salt', false, now(), 0, now(), now())
        RETURNING id
        """;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM permissions WHERE asset_type = 'RepositoryTestAsset'");
        jdbcTemplate.update(
            "DELETE FROM groups_users WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'repo\\_%')");
        jdbcTemplate.update("DELETE FROM groups WHERE name LIKE 'repo\\_%'");
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'repo\\_%' OR username LIKE 'Repo\\_%'");
    }

    @Test
    void findByLoginMatchesLowercasedUsernameOrEmailAgainstMixedCaseColumnsAndOrdersById() {
        long aliasId = insertUser("Repo_Alias", "Repo.Alias@Example.com");
        long otherId = insertUser("repo_other", "repo_alias");

        List<User> byUsername = userRepository.findByLogin("repo_alias", Limit.unlimited());
        assertThat(byUsername).extracting(User::getId).containsExactly(aliasId, otherId);

        assertThat(userRepository.findByLogin("repo_alias", Limit.of(1)))
            .extracting(User::getId).containsExactly(aliasId);
        assertThat(userRepository.findByLogin("repo.alias@example.com", Limit.of(1)))
            .extracting(User::getId).containsExactly(aliasId);
    }

    @Test
    void findByLoginExpectsAnAlreadyNormalizedArgument() {
        insertUser("Repo_Alias", "Repo.Alias@Example.com");

        assertThat(userRepository.findByLogin("Repo_Alias", Limit.of(1))).isEmpty();
        assertThat(userRepository.findByLogin("Repo.Alias@Example.com", Limit.of(1))).isEmpty();
        assertThat(userRepository.findByLogin(" repo_alias", Limit.of(1))).isEmpty();
        assertThat(userRepository.findByLogin("", Limit.of(1))).isEmpty();
    }

    @Test
    void findByIdForUpdateHoldsARowLockUntilTheTransactionEnds() throws Exception {
        long id = insertUser("repo_locked", "repo_locked@example.com");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> contention = new AtomicReference<>();

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            assertThat(userRepository.findByIdForUpdate(id)).isPresent();
            locked.countDown();
            try {
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));

        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            jdbcTemplate.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE NOWAIT", Long.class, id);
        } catch (DataAccessException blocked) {
            contention.set(blocked);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        assertThat(contention.get()).as("row must be locked while findByIdForUpdate's transaction is open").isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE NOWAIT", Long.class, id))
            .isEqualTo(id);
    }

    @Test
    void roundTripsInstantColumnsAsUtcWallClockTimestampsLikeRails() {
        Instant confirmedAt = Instant.parse("2026-03-04T05:06:07.123456Z");
        Instant signedInAt = Instant.parse("2026-03-05T23:59:59.000001Z");
        User user = new User();
        user.setUsername("repo_instants");
        user.setEmail("repo_instants@example.com");
        user.setEncryptedPassword("hash");
        user.setPasswordSalt("salt");
        user.setConfirmedAt(confirmedAt);
        user.setCurrentSignInAt(signedInAt);
        user.setLastSignInAt(signedInAt);
        user.setCreatedAt(confirmedAt);
        user.setUpdatedAt(confirmedAt);

        Long id = userRepository.saveAndFlush(user).getId();

        assertThat(id).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT confirmed_at::text FROM users WHERE id = ?", String.class, id))
            .isEqualTo("2026-03-04 05:06:07.123456");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT current_sign_in_at::text FROM users WHERE id = ?", String.class, id))
            .isEqualTo("2026-03-05 23:59:59.000001");

        User reloaded = userRepository.findById(id).orElseThrow();
        assertThat(reloaded.getConfirmedAt()).isEqualTo(confirmedAt);
        assertThat(reloaded.getCurrentSignInAt()).isEqualTo(signedInAt);
        assertThat(reloaded.getLastSignInAt()).isEqualTo(signedInAt);
        assertThat(reloaded.getCreatedAt()).isEqualTo(confirmedAt);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(confirmedAt);
        assertThat(reloaded.getSuspendedAt()).isNull();
        assertThat(reloaded.getDeletedAt()).isNull();
        assertThat(reloaded.getSignInCount()).isZero();
        assertThat(reloaded.isAdmin()).isFalse();
    }

    @Test
    void mapsGroupsUsersJoinTableAndPermissionsInBothDirections() {
        long userId = insertUser("repo_member", "repo_member@example.com");
        long groupId = jdbcTemplate.queryForObject(
            "INSERT INTO groups (name, created_at, updated_at) VALUES ('repo_group', now(), now()) RETURNING id",
            Long.class);
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", groupId, userId);
        long permissionId = jdbcTemplate.queryForObject("""
            INSERT INTO permissions (user_id, group_id, asset_type, asset_id, created_at, updated_at)
            VALUES (?, ?, 'RepositoryTestAsset', 17, now(), now()) RETURNING id
            """, Long.class, userId, groupId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User user = userRepository.findById(userId).orElseThrow();

            assertThat(user.getGroups()).extracting(Group::getId).containsExactly(groupId);
            Group group = user.getGroups().iterator().next();
            assertThat(group.getName()).isEqualTo("repo_group");
            assertThat(group.getUsers()).extracting(User::getId).containsExactly(userId);

            assertThat(user.getPermissions()).extracting(Permission::getId).containsExactly(permissionId);
            Permission permission = user.getPermissions().iterator().next();
            assertThat(permission.getAssetType()).isEqualTo("RepositoryTestAsset");
            assertThat(permission.getAssetId()).isEqualTo(17);
            assertThat(permission.getUser().getId()).isEqualTo(userId);
            assertThat(permission.getGroup().getId()).isEqualTo(groupId);
            assertThat(group.getPermissions()).extracting(Permission::getId).containsExactly(permissionId);
        });
    }

    @Test
    void userWithoutGroupsOrPermissionsExposesEmptyImmutableCollections() {
        long userId = insertUser("repo_loner", "repo_loner@example.com");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User user = userRepository.findById(userId).orElseThrow();
            assertThat(user.getGroups()).isEmpty();
            assertThat(user.getPermissions()).isEmpty();
            assertThatThrownBy(() -> user.getGroups().add(new Group()))
                .isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test
    void rejectsUsernamesLongerThanTheRailsColumnLimit() {
        User user = new User();
        user.setUsername("repo_" + "x".repeat(28));
        user.setEncryptedPassword("hash");
        user.setPasswordSalt("salt");

        assertThatThrownBy(() -> userRepository.saveAndFlush(user)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private long insertUser(String username, String email) {
        return jdbcTemplate.queryForObject(INSERT_USER, Long.class, username, email);
    }
}
