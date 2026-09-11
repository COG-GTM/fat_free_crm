package com.fatfreecrm.service;

import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PRIVATE;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.api.dto.AutoCompleteResponse;
import com.fatfreecrm.api.dto.ContactDto;
import com.fatfreecrm.api.dto.PageResponse;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.security.CurrentUser;
import com.fatfreecrm.security.UnauthenticatedException;
import com.fatfreecrm.support.TestDataSeeder.ContactRow;
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
 * {@link ContactService} exercised directly (below the HTTP layer), so the defensive
 * behaviour the controller's Bean Validation normally hides is pinned: page/perPage clamping,
 * {@code sortBy} rejection, and the unauthenticated guard.
 *
 * <pre>
 * users:    A (1), admin C (3), Z (4)
 * contacts: 10 Public  owned by A   Alice Anderson   created 2024-05-01 10:00
 *           11 Private owned by A   Bob Brown        created 2024-05-01 09:00
 *           16 Private owned by Z   Grace Green      created 2024-05-01 08:00
 * </pre>
 */
class ContactServiceTest extends AbstractIntegrationTest {

    private static final long A = 1;
    private static final long C = 3;
    private static final long Z = 4;

    private static final CurrentUser USER_A = new CurrentUser(A, false, Set.of());
    private static final CurrentUser ADMIN_C = new CurrentUser(C, true, Set.of());

    @Autowired
    private ContactService service;

    @BeforeEach
    void seed() {
        seeder.insertUser(A, "alice", false);
        seeder.insertUser(C, "carol", true);
        seeder.insertUser(Z, "zed", false);
        seeder.insertContact(contact(10, "Alice", "Anderson", A, ACCESS_PUBLIC, 10));
        seeder.insertContact(contact(11, "Bob", "Brown", A, ACCESS_PRIVATE, 9));
        seeder.insertContact(contact(16, "Grace", "Green", Z, ACCESS_PRIVATE, 8));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- list: defensive clamping the controller validation normally hides -------------------

    @Test
    void pageBelowOneIsServedAsPageOne() {
        authenticate(ADMIN_C);

        PageResponse<ContactDto> zero = service.list(0, 20, null, null);
        PageResponse<ContactDto> negative = service.list(-7, 20, null, null);
        PageResponse<ContactDto> one = service.list(1, 20, null, null);

        assertThat(zero.page()).isEqualTo(1);
        assertThat(negative.page()).isEqualTo(1);
        assertThat(ids(zero.items())).isEqualTo(ids(one.items()));
        assertThat(ids(negative.items())).isEqualTo(ids(one.items()));
    }

    @Test
    void perPageIsClampedIntoOneToMaxPageSize() {
        authenticate(ADMIN_C);

        assertThat(service.list(1, 0, null, null).perPage()).isEqualTo(1);
        assertThat(service.list(1, -3, null, null).perPage()).isEqualTo(1);
        assertThat(service.list(1, 1_000, null, null).perPage()).isEqualTo(200);
        assertThat(service.list(1, 200, null, null).perPage()).isEqualTo(200);
        assertThat(service.list(1, 20, null, null).perPage()).isEqualTo(20);

        PageResponse<ContactDto> single = service.list(1, 0, null, "first_name");
        assertThat(single.items()).hasSize(1);
        assertThat(single.totalCount()).isEqualTo(3);
    }

    @Test
    void blankSortByAndQueryFallBackToDefaults() {
        authenticate(ADMIN_C);

        PageResponse<ContactDto> defaults = service.list(1, 20, null, null);
        PageResponse<ContactDto> blanks = service.list(1, 20, "   ", "  ");

        assertThat(ids(defaults.items())).as("created_at DESC").containsExactly(10L, 11L, 16L);
        assertThat(ids(blanks.items())).isEqualTo(ids(defaults.items()));
        assertThat(blanks.totalCount()).isEqualTo(3);
    }

    @Test
    void unknownSortByIsRejectedBeforeQuerying() {
        authenticate(ADMIN_C);

        assertThatThrownBy(() -> service.list(1, 20, null, "email DESC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email DESC")
                .hasMessageContaining("first_name ASC");
    }

    // ---- visibility is enforced in the service, not only in the controller -------------------

    @Test
    void listAndGetAreScopedToTheCurrentUser() {
        authenticate(USER_A);

        assertThat(ids(service.list(1, 20, null, "first_name").items())).containsExactly(10L, 11L);
        assertThat(service.get(11).id()).isEqualTo(11);
        assertThatThrownBy(() -> service.get(16))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Contact with id 16 not found");
        assertThatThrownBy(() -> service.get(999))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Contact with id 999 not found");
    }

    @Test
    void autocompleteIsScopedToTheCurrentUser() {
        authenticate(USER_A);

        AutoCompleteResponse visible = service.autocomplete("a");
        assertThat(visible.results()).extracting(AutoCompleteResponse.Item::id).containsExactly(10L);
        assertThat(visible.results().get(0).text()).isEqualTo("Alice Anderson");

        assertThat(service.autocomplete("Grace").results()).isEmpty();
        assertThat(service.autocomplete(null).results()).extracting(AutoCompleteResponse.Item::id)
                .containsExactly(10L, 11L);
    }

    // ---- unauthenticated guard ---------------------------------------------------------------

    @Test
    void everyOperationRequiresAnAuthenticatedUser() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.list(1, 20, null, null)).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> service.get(10)).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> service.autocomplete("a")).isInstanceOf(UnauthenticatedException.class);
    }

    // ---- Rails Contact#full_name ("before" format) -------------------------------------------

    @Test
    void fullNameIsFirstNameSpaceLastName() {
        Contact contact = new Contact();
        contact.setFirstName("Ada");
        contact.setLastName("Lovelace");
        assertThat(ContactService.fullName(contact)).isEqualTo("Ada Lovelace");

        contact.setFirstName("");
        assertThat(ContactService.fullName(contact)).isEqualTo(" Lovelace");

        contact.setLastName("");
        assertThat(ContactService.fullName(contact)).isEqualTo(" ");
    }

    @Test
    void autocompleteLimitMatchesRailsHardCodedTen() {
        assertThat(ContactService.AUTOCOMPLETE_LIMIT).isEqualTo(10);
        assertThat(ContactService.ASSET_TYPE).isEqualTo("Contact");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static void authenticate(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }

    private static List<Long> ids(List<ContactDto> items) {
        return items.stream().map(ContactDto::id).toList();
    }

    private static ContactRow contact(long id, String first, String last, long owner, String access, int hour) {
        LocalDateTime created = LocalDateTime.of(2024, 5, 1, hour, 0);
        return new ContactRow(id, owner, null, null, null, first, last, access, null, null, null, null, null, null,
                null, null, null, null, null, null, null, false, null, null, null, null, null, null, null, null, null,
                created, created);
    }
}
