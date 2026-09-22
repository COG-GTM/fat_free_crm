package com.fatfreecrm.service;

import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PRIVATE;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PUBLIC;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_SHARED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.api.dto.AccountDto;
import com.fatfreecrm.api.dto.AutoCompleteResponse;
import com.fatfreecrm.api.dto.PageResponse;
import com.fatfreecrm.security.CurrentUser;
import com.fatfreecrm.security.UnauthenticatedException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link AccountService} against the real repository and a {@link CurrentUser} placed directly in
 * the security context, so the paging / sorting / visibility rules are pinned independently of
 * the controller's parameter validation.
 *
 * <pre>
 * users:    A (1), B (2, member of group G=100), admin C (3), Z (4)
 * accounts: 10 "Acme"    Public   owned by A,               rating 1, created 2024-01-01
 *           11 "Alpha"   Private  owned by A,               rating 5, created 2024-06-01
 *           12 "Bravo"   Private  owned by Z, assigned_to B
 *           13 "Charlie" Shared   owned by Z, permission -> user B
 *           14 "Delta"   Shared   owned by Z, permission -> group G
 *           15 "Echo"    Public   owned by Z, soft-deleted
 *           16 "Foxtrot" Private  owned by Z
 * </pre>
 */
class AccountServiceIT extends AbstractIntegrationTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long C = 3;
    private static final long Z = 4;
    private static final long G = 100;

    private static final CurrentUser USER_A = new CurrentUser(A, false, Set.of());
    private static final CurrentUser USER_B = new CurrentUser(B, false, Set.of(G));
    private static final CurrentUser ADMIN_C = new CurrentUser(C, true, Set.of());

    @Autowired
    private AccountService service;

    @BeforeEach
    void seed() {
        seeder.insertUser(A, "alice", false);
        seeder.insertUser(B, "bob", false);
        seeder.insertUser(C, "carol", true);
        seeder.insertUser(Z, "zed", false);
        seeder.insertGroup(G, "sales");
        seeder.addUserToGroup(B, G);

        seeder.insertAccount(10, "Acme", A, null, ACCESS_PUBLIC);
        seeder.jdbc().update("UPDATE accounts SET rating = 1, created_at = ?, updated_at = ? WHERE id = 10",
                LocalDateTime.of(2024, 1, 1, 10, 0, 0), LocalDateTime.of(2024, 1, 1, 10, 0, 0));
        seeder.insertAccount(11, "Alpha", A, null, ACCESS_PRIVATE);
        seeder.jdbc().update("UPDATE accounts SET rating = 5, created_at = ?, updated_at = ? WHERE id = 11",
                LocalDateTime.of(2024, 6, 1, 10, 0, 0), LocalDateTime.of(2024, 6, 1, 10, 0, 0));
        seeder.insertAccount(12, "Bravo", Z, B, ACCESS_PRIVATE);
        seeder.insertAccount(13, "Charlie", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Account", 13, B, null);
        seeder.insertAccount(14, "Delta", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Account", 14, null, G);
        seeder.insertAccount(15, "Echo", Z, null, ACCESS_PUBLIC, LocalDateTime.now().minusDays(1));
        seeder.insertAccount(16, "Foxtrot", Z, null, ACCESS_PRIVATE);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- list -------------------------------------------------------------------------------

    @Test
    void pageBelowOneIsTreatedAsTheFirstPage() {
        authenticate(ADMIN_C);

        PageResponse<AccountDto> zero = service.list(0, 2, null, "name");
        PageResponse<AccountDto> negative = service.list(-7, 2, null, "name");
        PageResponse<AccountDto> first = service.list(1, 2, null, "name");

        assertThat(ids(zero)).containsExactly(10L, 11L);
        assertThat(ids(negative)).containsExactly(10L, 11L);
        assertThat(ids(first)).containsExactly(10L, 11L);
        assertThat(zero.page()).isEqualTo(1);
        assertThat(negative.page()).isEqualTo(1);
    }

    @Test
    void perPageIsDefaultedAndClampedIntoOneToTwoHundred() {
        authenticate(ADMIN_C);

        assertThat(service.list(1, null, null, null).perPage()).isEqualTo(20);
        assertThat(service.list(1, 0, null, null).perPage()).isEqualTo(1);
        assertThat(service.list(1, -5, null, null).perPage()).isEqualTo(1);
        assertThat(service.list(1, 200, null, null).perPage()).isEqualTo(200);
        assertThat(service.list(1, 201, null, null).perPage()).isEqualTo(200);
        assertThat(service.list(1, Integer.MAX_VALUE, null, null).perPage()).isEqualTo(200);
        assertThat(ids(service.list(1, 0, null, "name"))).containsExactly(10L);
    }

    @Test
    void totalCountCountsOnlyVisibleRowsAcrossAllPages() {
        authenticate(USER_B);

        PageResponse<AccountDto> page = service.list(2, 3, null, "name");

        assertThat(ids(page)).containsExactly(14L);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.perPage()).isEqualTo(3);
        assertThat(page.totalCount()).isEqualTo(4);
    }

    @Test
    void pageBeyondTheLastIsEmptyButKeepsTotalCount() {
        authenticate(USER_A);

        PageResponse<AccountDto> page = service.list(50, 20, null, null);

        assertThat(page.items()).isEmpty();
        assertThat(page.page()).isEqualTo(50);
        assertThat(page.totalCount()).isEqualTo(2);
    }

    @Test
    void nullAndBlankSortByFallBackToRailsDefaultCreatedAtDesc() {
        authenticate(USER_A);

        assertThat(ids(service.list(1, null, null, null))).containsExactly(11L, 10L);
        assertThat(ids(service.list(1, null, null, ""))).containsExactly(11L, 10L);
        assertThat(ids(service.list(1, null, null, "   "))).containsExactly(11L, 10L);
    }

    @Test
    void unknownSortByRaisesIllegalArgumentBeforeQuerying() {
        authenticate(USER_A);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.list(1, null, null, "email"))
                .withMessage("sortBy 'email' " + AccountSort.PATTERN_MESSAGE);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.list(1, null, null, "name DESC"));
    }

    @Test
    void queryAndVisibilityCombineWithAnd() {
        authenticate(USER_A);
        assertThat(ids(service.list(1, null, "foxtrot", null))).isEmpty();
        assertThat(service.list(1, null, "foxtrot", null).totalCount()).isZero();

        authenticate(ADMIN_C);
        assertThat(ids(service.list(1, null, "foxtrot", null))).containsExactly(16L);
    }

    @Test
    void sharedRecordsAreListedForDirectAndGroupGranteesOnly() {
        authenticate(USER_B);
        assertThat(ids(service.list(1, null, null, "name"))).containsExactly(10L, 12L, 13L, 14L);

        authenticate(new CurrentUser(B, false, Set.of()));
        assertThat(ids(service.list(1, null, null, "name"))).containsExactly(10L, 12L, 13L);

        authenticate(USER_A);
        assertThat(ids(service.list(1, null, null, "name"))).containsExactly(10L, 11L);
    }

    // ---- get --------------------------------------------------------------------------------

    @Test
    void getReturnsVisibleRowsIncludingSharedAndAssignedOnes() {
        authenticate(USER_B);

        assertThat(service.get(10).name()).isEqualTo("Acme");
        assertThat(service.get(12).name()).isEqualTo("Bravo");
        assertThat(service.get(13).name()).isEqualTo("Charlie");
        assertThat(service.get(14).name()).isEqualTo("Delta");
    }

    @Test
    void getRaisesTheSameNotFoundForMissingSoftDeletedAndInvisibleIds() {
        authenticate(USER_A);

        assertThatThrownBy(() -> service.get(999))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Account with id 999 not found");
        assertThatThrownBy(() -> service.get(15))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Account with id 15 not found");
        assertThatThrownBy(() -> service.get(16))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Account with id 16 not found");
    }

    @Test
    void adminsCanGetPrivateRowsButNotSoftDeletedOnes() {
        authenticate(ADMIN_C);

        assertThat(service.get(16).name()).isEqualTo("Foxtrot");
        assertThatThrownBy(() -> service.get(15)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- autocomplete -----------------------------------------------------------------------

    @Test
    void autocompleteOrdersByNameThenIdAndStopsAtTen() {
        authenticate(ADMIN_C);
        for (int i = 1; i <= 12; i++) {
            seeder.insertAccount(200 + i, "Same", Z, null, ACCESS_PUBLIC);
        }

        AutoCompleteResponse response = service.autocomplete("same");

        assertThat(response.results()).hasSize(AccountService.AUTOCOMPLETE_LIMIT);
        assertThat(response.results()).extracting(AutoCompleteResponse.Item::id)
                .containsExactly(201L, 202L, 203L, 204L, 205L, 206L, 207L, 208L, 209L, 210L);
        assertThat(response.results()).extracting(AutoCompleteResponse.Item::text).containsOnly("Same");
    }

    @Test
    void autocompleteRespectsVisibilityAndBlankTermMatchesEverythingVisible() {
        authenticate(USER_A);
        assertThat(service.autocomplete(null).results()).extracting(AutoCompleteResponse.Item::id)
                .containsExactly(10L, 11L);
        assertThat(service.autocomplete("").results()).extracting(AutoCompleteResponse.Item::id)
                .containsExactly(10L, 11L);
        assertThat(service.autocomplete("foxtrot").results()).isEmpty();

        authenticate(USER_B);
        assertThat(service.autocomplete("").results()).extracting(AutoCompleteResponse.Item::id)
                .containsExactly(10L, 12L, 13L, 14L);
    }

    // ---- authentication guard ---------------------------------------------------------------

    @Test
    void everyOperationRejectsAMissingCurrentUserBeforeTouchingTheDatabase() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.list(1, null, null, null)).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> service.get(10)).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> service.autocomplete("a")).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void aPrincipalThatIsNotACurrentUserIsTreatedAsUnauthenticated() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));

        assertThatThrownBy(() -> service.get(10)).isInstanceOf(UnauthenticatedException.class);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static void authenticate(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }

    private static List<Long> ids(PageResponse<AccountDto> page) {
        return page.items().stream().map(AccountDto::id).toList();
    }
}
