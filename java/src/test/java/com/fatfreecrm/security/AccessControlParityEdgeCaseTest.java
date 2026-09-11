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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * Edge cases of {@link AccessControlSpecifications#visibleTo} derived from the Rails
 * {@code Ability} (app/models/users/ability.rb) rather than from the Java implementation:
 *
 * <ul>
 *   <li>{@code can :manage, entities, access: 'Public'} is an exact string match — NULL or
 *       differently-cased values grant nothing.</li>
 *   <li>{@code can :manage, klass, id: p.asset_id} is granted for every permissions row whose
 *       grantee matches, regardless of the asset's {@code access} column.</li>
 *   <li>A permissions row with neither {@code user_id} nor {@code group_id} grants nobody.</li>
 *   <li>Membership of any one of the user's groups is enough.</li>
 *   <li>{@code accessible_by} yields each record once even when several rules match.</li>
 *   <li>{@code Model.my(user).find(id)} on an invisible record is a miss (Rails 404).</li>
 * </ul>
 * The same matrix is run against {@code contacts} to prove the Specification is not Account-specific.
 */
class AccessControlParityEdgeCaseTest extends AbstractIntegrationTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long C = 3;
    private static final long Z = 4;
    private static final long G1 = 100;
    private static final long G2 = 101;

    private static final CurrentUser USER_A = new CurrentUser(A, false, Set.of());
    private static final CurrentUser USER_B = new CurrentUser(B, false, Set.of(G1, G2));
    private static final CurrentUser ADMIN_C = new CurrentUser(C, true, Set.of());

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ContactRepository contactRepository;

    @BeforeEach
    void seedUsers() {
        seeder.insertUser(A, "alice", false);
        seeder.insertUser(B, "bob", false);
        seeder.insertUser(C, "carol", true);
        seeder.insertUser(Z, "zed", false);
        seeder.insertGroup(G1, "sales");
        seeder.insertGroup(G2, "support");
        seeder.addUserToGroup(B, G1);
        seeder.addUserToGroup(B, G2);
    }

    // ---- access column semantics ------------------------------------------------------------

    @Test
    void nullAccessIsNotPublic() {
        seeder.insertAccount(10, "null-access", Z, null, null);

        assertThat(visibleAccountIds(USER_A)).isEmpty();
        assertThat(visibleAccountIds(ADMIN_C)).containsExactly(10L);
    }

    @Test
    void accessMatchIsCaseSensitiveLikeRails() {
        seeder.insertAccount(10, "lowercase-public", Z, null, "public");
        seeder.insertAccount(11, "uppercase-public", Z, null, "PUBLIC");

        assertThat(visibleAccountIds(USER_A)).isEmpty();
    }

    @Test
    void ownerAndAssigneeSeePrivateRecordsWithAnyAccessValue() {
        seeder.insertAccount(10, "owned-null-access", A, null, null);
        seeder.insertAccount(11, "assigned-private", Z, A, ACCESS_PRIVATE);

        assertThat(visibleAccountIds(USER_A)).containsExactly(10L, 11L);
    }

    // ---- permission rows -----------------------------------------------------------------------

    @Test
    void permissionRowGrantsAccessRegardlessOfAccessColumn() {
        seeder.insertAccount(10, "private-with-perm", Z, null, ACCESS_PRIVATE);
        seeder.insertPermission("Account", 10, A, null);

        assertThat(visibleAccountIds(USER_A)).containsExactly(10L);
    }

    @Test
    void permissionRowWithoutGranteeGrantsNobody() {
        seeder.insertAccount(10, "orphan-perm", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Account", 10, null, null);

        assertThat(visibleAccountIds(USER_A)).isEmpty();
        assertThat(visibleAccountIds(USER_B)).isEmpty();
    }

    @Test
    void sharedRecordWithoutAnyPermissionIsVisibleOnlyToOwnerAssigneeAndAdmin() {
        seeder.insertAccount(10, "shared-no-perms", Z, null, ACCESS_SHARED);

        assertThat(visibleAccountIds(USER_A)).isEmpty();
        assertThat(visibleAccountIds(USER_B)).isEmpty();
        assertThat(visibleAccountIds(new CurrentUser(Z, false, Set.of()))).containsExactly(10L);
        assertThat(visibleAccountIds(ADMIN_C)).containsExactly(10L);
    }

    @Test
    void anyOfTheUsersGroupsGrantsAccess() {
        seeder.insertAccount(10, "shared-g1", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Account", 10, null, G1);
        seeder.insertAccount(11, "shared-g2", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Account", 11, null, G2);
        seeder.insertAccount(12, "shared-other-group", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Account", 12, null, 999L);

        assertThat(visibleAccountIds(USER_B)).containsExactly(10L, 11L);
        assertThat(visibleAccountIds(new CurrentUser(B, false, Set.of(G2)))).containsExactly(11L);
    }

    @Test
    void permissionForAnotherUserDoesNotLeak() {
        seeder.insertAccount(10, "shared-for-a", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Account", 10, A, null);

        assertThat(visibleAccountIds(USER_B)).isEmpty();
    }

    @Test
    void permissionOnSoftDeletedRecordDoesNotResurrectIt() {
        seeder.insertAccount(10, "deleted-shared", Z, null, ACCESS_SHARED, LocalDateTime.now().minusDays(1));
        seeder.insertPermission("Account", 10, A, null);

        assertThat(visibleAccountIds(USER_A)).isEmpty();
        assertThat(visibleAccountIds(ADMIN_C)).isEmpty();
    }

    // ---- query shape -------------------------------------------------------------------------------

    @Test
    void recordMatchingSeveralRulesIsReturnedOnce() {
        seeder.insertAccount(10, "multi-match", A, A, ACCESS_PUBLIC);
        seeder.insertPermission("Account", 10, A, null);
        seeder.insertPermission("Account", 10, null, G1);
        seeder.insertPermission("Account", 10, B, null);

        assertThat(visibleAccountIds(USER_A)).containsExactly(10L);
        assertThat(visibleAccountIds(USER_B)).containsExactly(10L);
        assertThat(accountRepository.count(visibleTo(USER_B, "Account"))).isEqualTo(1);
    }

    @Test
    void specificationWorksForCountAndPagedQueries() {
        for (long id = 10; id < 20; id++) {
            seeder.insertAccount(id, "public-" + id, Z, null, ACCESS_PUBLIC);
        }
        for (long id = 20; id < 25; id++) {
            seeder.insertAccount(id, "private-" + id, Z, null, ACCESS_PRIVATE);
        }
        seeder.insertAccount(30, "shared-g1", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Account", 30, null, G1);

        Specification<Account> spec = visibleTo(USER_B, "Account");
        assertThat(accountRepository.count(spec)).isEqualTo(11);

        var page = accountRepository.findAll(spec, PageRequest.of(0, 4, Sort.by("id")));
        assertThat(page.getTotalElements()).isEqualTo(11);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getContent()).extracting(Account::getId).containsExactly(10L, 11L, 12L, 13L);

        var last = accountRepository.findAll(spec, PageRequest.of(2, 4, Sort.by("id")));
        assertThat(last.getContent()).extracting(Account::getId).containsExactly(18L, 19L, 30L);
    }

    @Test
    void lookupByIdThroughTheSpecificationMissesInvisibleRecords() {
        seeder.insertAccount(10, "public", Z, null, ACCESS_PUBLIC);
        seeder.insertAccount(11, "private", Z, null, ACCESS_PRIVATE);

        assertThat(accountRepository.findOne(accountsVisibleTo(USER_A).and(withId(10L)))).isPresent();
        assertThat(accountRepository.findOne(accountsVisibleTo(USER_A).and(withId(11L)))).isEmpty();
        assertThat(accountRepository.findOne(accountsVisibleTo(ADMIN_C).and(withId(11L)))).isPresent();
        assertThat(accountRepository.findById(11L)).isPresent();
    }

    // ---- contacts get the same treatment ------------------------------------------------------

    @Test
    void contactVisibilityMatrixMatchesAccounts() {
        seeder.insertContact(10, "public", "by-a", A, null, ACCESS_PUBLIC);
        seeder.insertContact(11, "private", "by-a", A, null, ACCESS_PRIVATE);
        seeder.insertContact(12, "private", "assigned-b", Z, B, ACCESS_PRIVATE);
        seeder.insertContact(13, "shared", "user-b", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Contact", 13, B, null);
        seeder.insertContact(14, "shared", "group-g1", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Contact", 14, null, G1);
        seeder.insertContact(15, "deleted", "public", Z, null, ACCESS_PUBLIC, LocalDateTime.now().minusDays(1));
        seeder.insertContact(16, "private", "by-z", Z, null, ACCESS_PRIVATE);
        seeder.insertPermission("Account", 16, B, null);

        assertThat(visibleContactIds(USER_A)).containsExactly(10L, 11L);
        assertThat(visibleContactIds(USER_B)).containsExactly(10L, 12L, 13L, 14L);
        assertThat(visibleContactIds(ADMIN_C)).containsExactly(10L, 11L, 12L, 13L, 14L, 16L);
        assertThat(visibleContactIds(new CurrentUser(Z, false, Set.of()))).containsExactly(10L, 12L, 13L, 14L, 16L);
    }

    @Test
    void softDeletedContactsAreHiddenEvenById() {
        seeder.insertContact(15, "deleted", "public", A, null, ACCESS_PUBLIC, LocalDateTime.now().minusDays(1));

        assertThat(contactRepository.findById(15L)).isEmpty();
        assertThat(contactRepository.count()).isZero();
        assertThat(visibleContactIds(ADMIN_C)).isEmpty();
    }

    private List<Long> visibleAccountIds(CurrentUser user) {
        return accountRepository.findAll(visibleTo(user, "Account")).stream()
                .map(Account::getId).sorted().toList();
    }

    private List<Long> visibleContactIds(CurrentUser user) {
        return contactRepository.findAll(visibleTo(user, "Contact")).stream()
                .map(Contact::getId).sorted().toList();
    }

    private static Specification<Account> accountsVisibleTo(CurrentUser user) {
        return AccessControlSpecifications.visibleTo(user, "Account");
    }

    private static Specification<Account> withId(long id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }
}
