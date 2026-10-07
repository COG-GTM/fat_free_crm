package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Permission;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.PermissionRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import com.fatfreecrm.security.authz.AccessPolicy;
import com.fatfreecrm.security.authz.CrmPermissionEvaluator;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Edge cases of {@link PermissionService} and {@link CrmPermissionEvaluator} that the Rails-generated matrix
 * does not reach. Expected behavior comes from {@code lib/fat_free_crm/permissions.rb} and
 * {@code app/models/users/ability.rb}.
 */
class PermissionServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PREFIX = "permsvc_";
    private static final String INSERT_USER = """
        INSERT INTO users (username, email, first_name, last_name, encrypted_password, password_salt, admin,
                           confirmed_at, sign_in_count, created_at, updated_at)
        VALUES (?, ?, 'Perm', ?, 'fixed', 'salt', false, now(), 0, now(), now())
        RETURNING id
        """;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private CrmPermissionEvaluator permissionEvaluator;

    @Autowired
    private AccessPolicy accessPolicy;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long ownerId;
    private Long granteeId;
    private Long memberId;
    private Long outsiderId;
    private Long groupId;
    private final List<Long> accountIds = new ArrayList<>();

    @BeforeEach
    void seedActors() {
        ownerId = insertUser("owner");
        granteeId = insertUser("grantee");
        memberId = insertUser("member");
        outsiderId = insertUser("outsider");
        groupId = jdbcTemplate.queryForObject(
            "INSERT INTO groups (name, created_at, updated_at) VALUES (?, now(), now()) RETURNING id",
            Long.class, PREFIX + "group");
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", groupId, memberId);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : accountIds) {
            jdbcTemplate.update("DELETE FROM permissions WHERE asset_type = 'Account' AND asset_id = ?", id);
        }
        jdbcTemplate.update("DELETE FROM permissions WHERE user_id IN (?, ?, ?, ?) OR group_id = ?",
            ownerId, granteeId, memberId, outsiderId, groupId);
        jdbcTemplate.update("DELETE FROM accounts WHERE name LIKE ?", PREFIX + "%");
        jdbcTemplate.update("DELETE FROM groups_users WHERE group_id = ?", groupId);
        jdbcTemplate.update("DELETE FROM groups WHERE id = ?", groupId);
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE ?", PREFIX + "%");
        accountIds.clear();
    }

    @Test
    void revokingSharingOnlyRemovesRowsOfTheSameAssetTypeAndAsset() {
        Account shared = account("shared", "Shared");
        Account other = account("other", "Shared");
        permissionService.setUserIds(shared, List.of(granteeId));
        permissionService.setUserIds(other, List.of(granteeId));
        // Rails deletes Permission.where(asset_id: id, asset_type: self.class.name): a Contact row that happens
        // to share the numeric id must survive.
        Permission contactRow = new Permission();
        contactRow.setUser(userRepository.getReferenceById(granteeId));
        contactRow.setAssetType("Contact");
        contactRow.setAssetId(Math.toIntExact(shared.getId()));
        contactRow.setCreatedAt(Instant.now());
        contactRow.setUpdatedAt(Instant.now());
        permissionRepository.saveAndFlush(contactRow);

        permissionService.setAccess(shared, "Private");

        assertThat(rows(shared.getId())).containsExactly("Contact|" + granteeId);
        assertThat(rows(other.getId())).containsExactly("Account|" + granteeId);
        assertThat(visibleTo(granteeId, shared.getId())).isFalse();
        assertThat(visibleTo(granteeId, other.getId())).isTrue();
    }

    @Test
    void groupIdsOnARecordWhoseInMemoryAccessIsNotSharedRemoveEveryRow() {
        Account account = account("group-private", "Shared");
        permissionService.updateSharing(account, "Shared", List.of(granteeId), List.of(groupId));
        assertThat(rows(account.getId())).hasSize(2);

        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        reloaded.setAccess("Private");
        permissionService.setGroupIds(reloaded, List.of(groupId));

        assertThat(rows(account.getId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT access FROM accounts WHERE id = ?", String.class,
            account.getId())).isEqualTo("Private");
        assertThat(visibleTo(memberId, account.getId())).isFalse();
        assertThat(visibleTo(granteeId, account.getId())).isFalse();
        assertThat(visibleTo(ownerId, account.getId())).isTrue();
    }

    @Test
    void replacingOneKindOfGrantLeavesTheOtherKindUntouched() {
        Account account = account("kinds", "Shared");
        permissionService.updateSharing(account, "Shared", List.of(granteeId), List.of(groupId));

        permissionService.setUserIds(account, List.of(outsiderId));
        assertThat(rows(account.getId())).containsExactlyInAnyOrder("Account|" + outsiderId, "Account|group");
        assertThat(visibleTo(granteeId, account.getId())).isFalse();
        assertThat(visibleTo(outsiderId, account.getId())).isTrue();
        assertThat(visibleTo(memberId, account.getId())).isTrue();

        permissionService.setGroupIds(account, List.of());
        assertThat(rows(account.getId())).containsExactly("Account|" + outsiderId);
        assertThat(visibleTo(memberId, account.getId())).isFalse();
        assertThat(visibleTo(outsiderId, account.getId())).isTrue();
    }

    @Test
    void staleFieldsOnADetachedInstanceAreNeverWrittenBack() {
        Account detached = account("detached", "Private");
        jdbcTemplate.update("UPDATE accounts SET name = ? WHERE id = ?", PREFIX + "renamed-elsewhere",
            detached.getId());
        detached.setName(PREFIX + "stale-name");

        permissionService.updateSharing(detached, "Shared", List.of(granteeId), null);

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT name, access FROM accounts WHERE id = ?",
            detached.getId());
        assertThat(row.get("name")).isEqualTo(PREFIX + "renamed-elsewhere");
        assertThat(row.get("access")).isEqualTo("Shared");
        assertThat(detached.getAccess()).isEqualTo("Shared");
        assertThat(visibleTo(granteeId, detached.getId())).isTrue();
    }

    @Test
    void unsavedDeletedAndNullEntitiesAreRejected() {
        assertThatThrownBy(() -> permissionService.setAccess(null, "Shared")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> permissionService.setAccess(new Account(), "Shared"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("persisted");
        assertThatThrownBy(() -> permissionService.setUserIds(new Account(), List.of(granteeId)))
            .isInstanceOf(NullPointerException.class);

        Account gone = account("gone", "Private");
        jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", gone.getId());
        assertThatThrownBy(() -> permissionService.setAccess(gone, "Shared"))
            .isInstanceOf(EntityNotFoundException.class)
            .hasMessageContaining("Account " + gone.getId());
        assertThatThrownBy(() -> permissionService.updateSharing(gone, null, List.of(granteeId), null))
            .isInstanceOf(EntityNotFoundException.class);
        assertThat(rows(gone.getId())).isEmpty();
    }

    @Test
    void updateDestroyAndManageAreAliasesOfReadLikeTheRailsManageRule() {
        Account account = account("aliases", "Private");
        FfcrmAuthenticationToken owner = token(ownerId, "owner");
        FfcrmAuthenticationToken outsider = token(outsiderId, "outsider");

        for (String permission : List.of("read", "update", "destroy", "manage")) {
            assertThat(permissionEvaluator.hasPermission(owner, account.getId(), "Account", permission))
                .as("owner " + permission).isTrue();
            assertThat(permissionEvaluator.hasPermission(outsider, account.getId(), "Account", permission))
                .as("outsider " + permission).isFalse();
            assertThat(permissionEvaluator.hasPermission(owner, account.getId().toString(), "Account", permission))
                .as("owner string id " + permission).isTrue();
        }
        assertThat(permissionEvaluator.hasPermission(owner, account.getId(), "Account", "create")).isFalse();
        assertThat(permissionEvaluator.hasPermission(outsider, account, "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(owner, account, "destroy")).isTrue();
    }

    private Account account(String label, String access) {
        Account account = new Account();
        account.setName(PREFIX + label);
        account.setUser(userRepository.getReferenceById(ownerId));
        account.setAccess(access);
        Account saved = accountRepository.saveAndFlush(account);
        accountIds.add(saved.getId());
        return saved;
    }

    private Long insertUser(String name) {
        return jdbcTemplate.queryForObject(INSERT_USER, Long.class, PREFIX + name, PREFIX + name + "@example.com",
            name);
    }

    private List<String> rows(Long assetId) {
        return jdbcTemplate.queryForList(
            "SELECT asset_type || '|' || COALESCE(user_id::text, 'group') FROM permissions WHERE asset_id = ?"
                + " ORDER BY asset_type, user_id NULLS LAST",
            String.class, assetId);
    }

    private boolean visibleTo(Long userId, Long accountId) {
        Specification<Account> byId = (root, query, cb) -> cb.equal(root.get("id"), accountId);
        AuthenticatedUser user = new AuthenticatedUser(userId, PREFIX + userId, false);
        return accountRepository.exists(byId.and(accessPolicy.accessibleBy(user, Account.class)));
    }

    private static FfcrmAuthenticationToken token(Long id, String name) {
        Jwt jwt = Jwt.withTokenValue("permsvc").header("alg", "none").subject(id.toString()).build();
        return new FfcrmAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_USER"),
            new AuthenticatedUser(id, PREFIX + name, false));
    }
}
