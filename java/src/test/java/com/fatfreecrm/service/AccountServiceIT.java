package com.fatfreecrm.service;

import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PRIVATE;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PUBLIC;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_SHARED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.api.dto.AccountDto;
import com.fatfreecrm.api.dto.AutoCompleteResponse;
import com.fatfreecrm.api.dto.PageResponse;
import com.fatfreecrm.config.PaginationProperties;
import com.fatfreecrm.security.CurrentUser;
import com.fatfreecrm.security.UnauthenticatedException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link AccountService} against the real repository and PostgreSQL, bypassing the HTTP layer.
 * Pins the parts of the service contract the controller never lets a request reach (unauthenticated
 * callers, {@code page < 1}, {@code perPage <= 0}, bad {@code sortBy}) plus the query semantics that
 * must match Rails {@code Account.my(current_user).text_search(query)} and
 * {@code ApplicationController#auto_complete}: literal LIKE-wildcard matching, whitespace-only
 * terms, {@code Shared} rows gated by {@code permissions}, soft-deleted rows hidden everywhere,
 * and the {@code name ASC, id ASC} autocomplete order.
 */
class AccountServiceIT extends AbstractIntegrationTest {

    private static final long ALICE = 1;
    private static final long BOB = 2;
    private static final long ADMIN = 3;
    private static final long OWNER = 4;
    private static final long SALES = 100;

    @Autowired
    private AccountService service;

    @Autowired
    private PaginationProperties pagination;

    @BeforeEach
    void seed() {
        seeder.insertUser(ALICE, "alice", false);
        seeder.insertUser(BOB, "bob", false);
        seeder.insertUser(ADMIN, "admin", true);
        seeder.insertUser(OWNER, "owner", false);
        seeder.insertGroup(SALES, "sales");
        seeder.addUserToGroup(BOB, SALES);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    class UnauthenticatedGuard {

        @Test
        void everyReadPathRefusesToRunWithoutAPrincipal() {
            seeder.insertAccount(10, "Acme", OWNER, null, ACCESS_PUBLIC);

            assertThatExceptionOfType(UnauthenticatedException.class).isThrownBy(() -> service.list(1, null, null, null));
            assertThatExceptionOfType(UnauthenticatedException.class).isThrownBy(() -> service.get(10));
            assertThatExceptionOfType(UnauthenticatedException.class).isThrownBy(() -> service.autocomplete("a"));
        }

        @Test
        void aPrincipalOfTheWrongTypeCountsAsUnauthenticated() {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));

            assertThatExceptionOfType(UnauthenticatedException.class).isThrownBy(() -> service.list(1, null, null, null));
        }
    }

    @Nested
    class ListPaging {

        @BeforeEach
        void rows() {
            for (int i = 1; i <= 5; i++) {
                seeder.insertAccount(10 + i, "Row " + i, OWNER, null, ACCESS_PUBLIC);
            }
            actAs(ALICE, false);
        }

        @Test
        void pageBelowOneIsTreatedAsTheFirstPageLikeRailsWillPaginate() {
            PageResponse<AccountDto> first = service.list(1, 2, null, "name");

            assertThat(service.list(0, 2, null, "name")).isEqualTo(first);
            assertThat(service.list(-7, 2, null, "name")).isEqualTo(first);
            assertThat(first.page()).isEqualTo(1);
            assertThat(names(first.items())).containsExactly("Row 1", "Row 2");
        }

        @Test
        void perPageIsClampedIntoRailsOneToMaxRange() {
            assertThat(service.list(1, 0, null, "name").perPage()).isEqualTo(1);
            assertThat(service.list(1, -5, null, "name").perPage()).isEqualTo(1);
            assertThat(service.list(1, 0, null, "name").items()).hasSize(1);
            assertThat(service.list(1, Integer.MAX_VALUE, null, "name").perPage()).isEqualTo(pagination.maxPageSize());
            assertThat(pagination.maxPageSize()).isEqualTo(200);
        }

        @Test
        void nullPerPageUsesTheRailsDefaultOfTwenty() {
            assertThat(service.list(1, null, null, null).perPage()).isEqualTo(20);
            assertThat(pagination.defaultPageSize()).isEqualTo(20);
        }

        @Test
        void pageBeyondTheLastIsEmptyButKeepsTheTotal() {
            PageResponse<AccountDto> page = service.list(1_000, 200, null, null);

            assertThat(page.items()).isEmpty();
            assertThat(page.page()).isEqualTo(1_000);
            assertThat(page.perPage()).isEqualTo(200);
            assertThat(page.totalCount()).isEqualTo(5);
        }

        @Test
        void unknownSortByIsRejectedBeforeQuerying() {
            assertThatIllegalArgumentException().isThrownBy(() -> service.list(1, null, null, "email"));
            assertThatIllegalArgumentException().isThrownBy(() -> service.list(1, null, null, "name DESC"));
        }

        @Test
        void blankSortByFallsBackToCreatedAtDescWithIdTiebreaker() {
            LocalDateTime sameInstant = LocalDateTime.of(2024, 1, 1, 0, 0);
            seeder.jdbc().update("UPDATE accounts SET created_at = ?", sameInstant);

            assertThat(ids(service.list(1, null, null, "   ").items())).containsExactly(15L, 14L, 13L, 12L, 11L);
            assertThat(ids(service.list(1, null, null, null).items())).containsExactly(15L, 14L, 13L, 12L, 11L);
        }
    }

    @Nested
    class TextSearchParity {

        @BeforeEach
        void rows() {
            seeder.insertAccount(20, "50% off", OWNER, null, ACCESS_PUBLIC);
            seeder.insertAccount(21, "50 off", OWNER, null, ACCESS_PUBLIC);
            seeder.insertAccount(22, "a_b", OWNER, null, ACCESS_PUBLIC);
            seeder.insertAccount(23, "axb", OWNER, null, ACCESS_PUBLIC);
            seeder.insertAccount(24, "back\\slash", OWNER, null, ACCESS_PUBLIC);
            seeder.insertAccount(25, "backslash", OWNER, null, ACCESS_PUBLIC);
            seeder.jdbc().update("UPDATE accounts SET email = 'Sales@ACME.example' WHERE id = 25");
            actAs(ALICE, false);
        }

        @Test
        void percentIsMatchedLiterallyNotAsAWildcard() {
            assertThat(ids(service.list(1, null, "50%", null).items())).containsExactly(20L);
            assertThat(ids(service.list(1, null, "%", null).items())).containsExactly(20L);
            assertThat(ids(service.list(1, null, "50% o", null).items())).containsExactly(20L);
        }

        @Test
        void underscoreIsMatchedLiterallyNotAsSingleCharWildcard() {
            assertThat(ids(service.list(1, null, "a_b", null).items())).containsExactly(22L);
            assertThat(ids(service.list(1, null, "_", null).items())).containsExactly(22L);
        }

        @Test
        void backslashIsMatchedLiterally() {
            assertThat(ids(service.list(1, null, "\\", null).items())).containsExactly(24L);
            assertThat(ids(service.list(1, null, "k\\s", null).items())).containsExactly(24L);
            assertThat(ids(service.list(1, null, "kslash", null).items())).containsExactly(25L);
        }

        @Test
        void emailMatchIsCaseInsensitiveOnBothSides() {
            assertThat(ids(service.list(1, null, "sales@acme", null).items())).containsExactly(25L);
            assertThat(ids(service.list(1, null, "SALES@ACME", null).items())).containsExactly(25L);
            assertThat(autocompleteIds("ales@AcMe")).containsExactly(25L);
        }

        @Test
        void whitespaceOnlyQueryMatchesEverythingLikeRansackBlankPredicates() {
            assertThat(service.list(1, null, "   ", null).totalCount()).isEqualTo(6);
            assertThat(service.list(1, null, "\t\n", null).totalCount()).isEqualTo(6);
            assertThat(service.autocomplete("   ").results()).hasSize(6);
            assertThat(service.autocomplete(null).results()).hasSize(6);
        }

        @Test
        void surroundingWhitespaceInTheQueryIsSignificant() {
            assertThat(ids(service.list(1, null, "50 ", null).items())).containsExactly(21L);
            assertThat(ids(service.list(1, null, " off", null).items())).containsExactlyInAnyOrder(20L, 21L);
        }
    }

    @Nested
    class SharedAccessAndSoftDelete {

        @BeforeEach
        void rows() {
            seeder.insertAccount(30, "Shared with Bob", OWNER, null, ACCESS_SHARED);
            seeder.insertPermission("Account", 30, BOB, null);
            seeder.insertAccount(31, "Shared with Sales", OWNER, null, ACCESS_SHARED);
            seeder.insertPermission("Account", 31, null, SALES);
            seeder.insertAccount(32, "Shared with nobody", OWNER, null, ACCESS_SHARED);
            seeder.insertAccount(33, "Shared via Contact permission", OWNER, null, ACCESS_SHARED);
            seeder.insertPermission("Contact", 33, ALICE, null);
            seeder.insertAccount(34, "Public but deleted", OWNER, null, ACCESS_PUBLIC, LocalDateTime.now().minusHours(1));
            seeder.insertAccount(35, "Private of Alice", ALICE, null, ACCESS_PRIVATE);
        }

        @Test
        void sharedRowsAreVisibleOnlyThroughAMatchingAccountPermission() {
            actAs(BOB, false, SALES);
            assertThat(ids(service.list(1, null, null, "name").items())).containsExactly(30L, 31L);
            assertThat(service.get(30).name()).isEqualTo("Shared with Bob");
            assertThat(service.get(31).name()).isEqualTo("Shared with Sales");
            assertThat(autocompleteIds("shared")).containsExactly(30L, 31L);

            actAs(ALICE, false);
            assertThat(ids(service.list(1, null, null, "name").items())).containsExactly(35L);
            assertThat(autocompleteIds("shared")).isEmpty();
            for (long id : List.of(30L, 31L, 32L, 33L)) {
                assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.get(id));
            }
        }

        @Test
        void groupMembershipComesFromThePrincipalNotFromTheAssetPermissionAlone() {
            actAs(BOB, false);

            assertThat(ids(service.list(1, null, null, "name").items())).containsExactly(30L);
            assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.get(31));
        }

        @Test
        void adminsSeeEverySharedAndPrivateRowButNeverDeletedOnes() {
            actAs(ADMIN, true);

            assertThat(ids(service.list(1, null, null, "name").items())).containsExactlyInAnyOrder(30L, 31L, 32L, 33L, 35L);
            assertThat(service.list(1, null, null, null).totalCount()).isEqualTo(5);
            assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.get(34));
            assertThat(autocompleteIds("deleted")).isEmpty();
        }

        @Test
        void softDeletedRowsAreInvisibleToTheirOwnerToo() {
            seeder.insertAccount(36, "Owner's deleted row", ALICE, null, ACCESS_PRIVATE, LocalDateTime.now().minusMinutes(1));
            actAs(ALICE, false);

            assertThat(ids(service.list(1, null, null, null).items())).containsExactly(35L);
            assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.get(36));
            assertThat(autocompleteIds("owner")).isEmpty();
        }

        @Test
        void notFoundCarriesTheAssetTypeAndId() {
            actAs(ALICE, false);

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> service.get(32))
                    .withMessageContaining("Account")
                    .withMessageContaining("32");
        }
    }

    @Nested
    class AutocompleteOrdering {

        @Test
        void tiesOnNameAreBrokenByIdAscendingAndTextIsTheName() {
            seeder.insertAccount(42, "Same", OWNER, null, ACCESS_PUBLIC);
            seeder.insertAccount(40, "Same", OWNER, null, ACCESS_PUBLIC);
            seeder.insertAccount(41, "Same", OWNER, null, ACCESS_PUBLIC);
            seeder.insertAccount(43, "Aardvark", OWNER, null, ACCESS_PUBLIC);
            actAs(ALICE, false);

            AutoCompleteResponse response = service.autocomplete(null);

            assertThat(response.results()).extracting(AutoCompleteResponse.Item::id).containsExactly(43L, 40L, 41L, 42L);
            assertThat(response.results()).extracting(AutoCompleteResponse.Item::text)
                    .containsExactly("Aardvark", "Same", "Same", "Same");
        }

        @Test
        void limitAppliesAfterVisibilityFiltering() {
            for (int i = 1; i <= 12; i++) {
                seeder.insertAccount(50 + i, String.format("Hidden %02d", i), OWNER, null, ACCESS_PRIVATE);
            }
            for (int i = 1; i <= 12; i++) {
                seeder.insertAccount(70 + i, String.format("Visible %02d", i), OWNER, null, ACCESS_PUBLIC);
            }
            actAs(ALICE, false);

            List<AutoCompleteResponse.Item> results = service.autocomplete("i").results();

            assertThat(results).hasSize(AccountService.AUTOCOMPLETE_LIMIT);
            assertThat(results).extracting(AutoCompleteResponse.Item::text).allMatch(t -> t.startsWith("Visible"));
            assertThat(results).extracting(AutoCompleteResponse.Item::id).containsExactly(71L, 72L, 73L, 74L, 75L, 76L, 77L, 78L, 79L, 80L);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static void actAs(long userId, boolean admin, Long... groupIds) {
        CurrentUser user = new CurrentUser(userId, admin, Set.of(groupIds));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }

    private List<Long> autocompleteIds(String term) {
        return service.autocomplete(term).results().stream().map(AutoCompleteResponse.Item::id).toList();
    }

    private static List<Long> ids(List<AccountDto> items) {
        return items.stream().map(AccountDto::id).toList();
    }

    private static List<String> names(List<AccountDto> items) {
        return items.stream().map(AccountDto::name).toList();
    }
}
