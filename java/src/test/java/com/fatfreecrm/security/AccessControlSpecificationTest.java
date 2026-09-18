package com.fatfreecrm.security;

import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PRIVATE;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PUBLIC;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_SHARED;
import static com.fatfreecrm.security.AccessControlSpecifications.visibleTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.ContactRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link AccessControlSpecifications#visibleTo} must reproduce Rails {@code Account.my(user)}.
 *
 * <pre>
 * users:    A (1), B (2, member of group G=100), admin C (3), Z (4)
 * accounts: 10 Public   owned by A
 *           11 Private  owned by A
 *           12 Private  owned by Z, assigned_to B
 *           13 Shared   owned by Z, permission -> user B
 *           14 Shared   owned by Z, permission -> group G
 *           15 Public   owned by Z, soft-deleted
 *           16 Private  owned by Z (nobody but Z / admin)
 * </pre>
 */
class AccessControlSpecificationTest extends AbstractIntegrationTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long C = 3;
    private static final long Z = 4;
    private static final long G = 100;

    private static final CurrentUser USER_A = new CurrentUser(A, false, Set.of());
    private static final CurrentUser USER_B = new CurrentUser(B, false, Set.of(G));
    private static final CurrentUser ADMIN_C = new CurrentUser(C, true, Set.of());
    private static final CurrentUser USER_Z = new CurrentUser(Z, false, Set.of());

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ContactRepository contactRepository;

    @BeforeEach
    void seed() {
        seeder.insertUser(A, "alice", false);
        seeder.insertUser(B, "bob", false);
        seeder.insertUser(C, "carol", true);
        seeder.insertUser(Z, "zed", false);
        seeder.insertGroup(G, "sales");
        seeder.addUserToGroup(B, G);

        seeder.insertAccount(10, "public-by-a", A, null, ACCESS_PUBLIC);
        seeder.insertAccount(11, "private-by-a", A, null, ACCESS_PRIVATE);
        seeder.insertAccount(12, "private-assigned-b", Z, B, ACCESS_PRIVATE);
        seeder.insertAccount(13, "shared-user-b", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Account", 13, B, null);
        seeder.insertAccount(14, "shared-group-g", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Account", 14, null, G);
        seeder.insertAccount(15, "deleted-public", Z, null, ACCESS_PUBLIC, LocalDateTime.now().minusDays(1));
        seeder.insertAccount(16, "private-by-z", Z, null, ACCESS_PRIVATE);
    }

    @Test
    void ownerSeesPublicAndOwnRecordsOnly() {
        assertThat(visibleAccountIds(USER_A)).containsExactly(10L, 11L);
    }

    @Test
    void assigneeAndPermissionGranteeSeesAssignedAndSharedRecords() {
        assertThat(visibleAccountIds(USER_B)).containsExactly(10L, 12L, 13L, 14L);
    }

    @Test
    void adminSeesEverythingExceptSoftDeleted() {
        assertThat(visibleAccountIds(ADMIN_C)).containsExactly(10L, 11L, 12L, 13L, 14L, 16L);
    }

    @Test
    void ownerOfSharedRecordsSeesThemWithoutPermissionRows() {
        assertThat(visibleAccountIds(USER_Z)).containsExactly(10L, 12L, 13L, 14L, 16L);
    }

    @Test
    void softDeletedRowsAreNeverReturnedEvenById() {
        assertThat(accountRepository.findById(15L)).isEmpty();
        assertThat(accountRepository.findAll()).extracting(Account::getId).doesNotContain(15L);
    }

    @Test
    void permissionsAreScopedByAssetType() {
        seeder.insertContact(13, "same-id", "as-account", Z, null, ACCESS_SHARED);
        seeder.insertContact(30, "shared-contact", "for-b", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Contact", 30, B, null);

        List<Long> contactIds = contactRepository.findAll(visibleTo(USER_B, "Contact")).stream()
                .map(Contact::getId).sorted().toList();
        assertThat(contactIds).containsExactly(30L);
    }

    @Test
    void groupMembershipIsTakenFromCurrentUserGroupIds() {
        CurrentUser bWithoutGroups = new CurrentUser(B, false, Set.of());
        assertThat(visibleAccountIds(bWithoutGroups)).containsExactly(10L, 12L, 13L);
    }

    private List<Long> visibleAccountIds(CurrentUser user) {
        return accountRepository.findAll(visibleTo(user, "Account")).stream()
                .map(Account::getId).sorted().toList();
    }
}
