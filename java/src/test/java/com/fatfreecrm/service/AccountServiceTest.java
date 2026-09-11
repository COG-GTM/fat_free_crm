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
 * {@link AccountService} called directly (bypassing controller validation) so the service's own
 * defensive behaviour is pinned: page/perPage normalisation, the unauthenticated guard, and the
 * owner-visibility rules for {@code Shared} rows.
 *
 * <pre>
 * users:    A (1), B (2, member of group G=100), Z (4)
 * accounts: 10 "Acme"    Public   owned by A
 *           11 "Alpha"   Private  owned by A
 *           13 "Charlie" Shared   owned by Z, permission -> user B
 *           14 "Delta"   Shared   owned by Z, permission -> group G
 *           17 "Golf"    Shared   owned by Z, no permission rows
 * </pre>
 */
class AccountServiceTest extends AbstractIntegrationTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long Z = 4;
    private static final long G = 100;

    private static final CurrentUser USER_A = new CurrentUser(A, false, Set.of());
    private static final CurrentUser USER_B = new CurrentUser(B, false, Set.of(G));
    private static final CurrentUser USER_Z = new CurrentUser(Z, false, Set.of());

    @Autowired
    private AccountService service;

    @BeforeEach
    void seed() {
        seeder.insertUser(A, "alice", false);
        seeder.insertUser(B, "bob", false);
        seeder.insertUser(Z, "zed", false);
        seeder.insertGroup(G, "sales");
        seeder.addUserToGroup(B, G);

        seeder.insertAccount(10, "Acme", A, null, ACCESS_PUBLIC);
        seeder.insertAccount(11, "Alpha", A, null, ACCESS_PRIVATE);
        seeder.insertAccount(13, "Charlie", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Account", 13, B, null);
        seeder.insertAccount(14, "Delta", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Account", 14, null, G);
        seeder.insertAccount(17, "Golf", Z, null, ACCESS_SHARED);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void pageBelowOneIsServedAsPageOne() {
        authenticate(USER_A);

        PageResponse<AccountDto> zero = service.list(0, null, null, "name");
        PageResponse<AccountDto> negative = service.list(-7, null, null, "name");

        assertThat(zero.page()).isEqualTo(1);
        assertThat(negative.page()).isEqualTo(1);
        assertThat(ids(zero)).containsExactly(10L, 11L);
        assertThat(ids(negative)).containsExactly(10L, 11L);
    }

    @Test
    void perPageIsDefaultedAndClampedNotRejected() {
        authenticate(USER_A);

        assertThat(service.list(1, null, null, null).perPage()).isEqualTo(20);
        assertThat(service.list(1, 0, null, null).perPage()).isEqualTo(1);
        assertThat(service.list(1, -5, null, null).perPage()).isEqualTo(1);
        assertThat(service.list(1, 200, null, null).perPage()).isEqualTo(200);
        assertThat(service.list(1, 201, null, null).perPage()).isEqualTo(200);
        assertThat(service.list(1, Integer.MAX_VALUE, null, null).perPage()).isEqualTo(200);
    }

    @Test
    void totalCountReflectsAllVisibleMatchesNotJustThePage() {
        authenticate(USER_A);

        PageResponse<AccountDto> page = service.list(2, 1, null, "name");

        assertThat(ids(page)).containsExactly(11L);
        assertThat(page.totalCount()).isEqualTo(2);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.perPage()).isEqualTo(1);
    }

    @Test
    void blankSortByIsCreatedAtDescAndUnknownSortByIsRejected() {
        authenticate(USER_A);
        seeder.jdbc().update("UPDATE accounts SET created_at = ? WHERE id = 10", LocalDateTime.of(2024, 1, 1, 0, 0));
        seeder.jdbc().update("UPDATE accounts SET created_at = ? WHERE id = 11", LocalDateTime.of(2024, 6, 1, 0, 0));

        assertThat(ids(service.list(1, null, null, null))).containsExactly(11L, 10L);
        assertThat(ids(service.list(1, null, null, "  "))).containsExactly(11L, 10L);
        assertThatIllegalArgumentException().isThrownBy(() -> service.list(1, null, null, "email"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.list(1, null, null, "name DESC"));
    }

    @Test
    void everyEntryPointRequiresAnAuthenticatedUser() {
        assertThatThrownBy(() -> service.list(1, null, null, null)).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> service.get(10)).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> service.autocomplete("a")).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void ownerSeesSharedRowsWithoutPermissionRowsButGranteesNeedOne() {
        authenticate(USER_Z);
        assertThat(ids(service.list(1, null, null, "name"))).containsExactly(10L, 13L, 14L, 17L);
        assertThat(service.get(17).name()).isEqualTo("Golf");

        authenticate(USER_B);
        assertThat(ids(service.list(1, null, null, "name"))).containsExactly(10L, 13L, 14L);
        assertThatThrownBy(() -> service.get(17)).isInstanceOf(ResourceNotFoundException.class);

        authenticate(USER_A);
        assertThatThrownBy(() -> service.get(13)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get(14)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get(17)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void groupGrantsAreEvaluatedAgainstThePrincipalsGroupIds() {
        authenticate(new CurrentUser(B, false, Set.of()));
        assertThat(ids(service.list(1, null, null, "name"))).containsExactly(10L, 13L);
        assertThatThrownBy(() -> service.get(14)).isInstanceOf(ResourceNotFoundException.class);

        authenticate(new CurrentUser(B, false, Set.of(999L)));
        assertThatThrownBy(() -> service.get(14)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void notFoundMessageNamesTheAssetAndId() {
        authenticate(USER_A);

        assertThatThrownBy(() -> service.get(999))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Account with id 999 not found");
    }

    @Test
    void autocompleteOrdersByNameThenIdAndStopsAtTen() {
        authenticate(USER_A);
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
    void autocompleteWithNullTermReturnsEveryVisibleRow() {
        authenticate(USER_B);

        assertThat(service.autocomplete(null).results()).extracting(AutoCompleteResponse.Item::id)
                .containsExactly(10L, 13L, 14L);
    }

    private static void authenticate(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }

    private static List<Long> ids(PageResponse<AccountDto> page) {
        return page.items().stream().map(AccountDto::id).toList();
    }
}
