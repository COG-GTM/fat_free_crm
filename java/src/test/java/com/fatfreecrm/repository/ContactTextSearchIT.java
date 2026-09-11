package com.fatfreecrm.repository;

import static com.fatfreecrm.repository.ContactSpecifications.textSearch;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PRIVATE;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PUBLIC;
import static com.fatfreecrm.security.AccessControlSpecifications.visibleTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.security.CurrentUser;
import com.fatfreecrm.support.TestDataSeeder.ContactRow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * {@link ContactSpecifications#textSearch} against PostgreSQL must return what the Rails scope
 * {@code Contact.text_search(query)} (app/models/entities/contact.rb) returns. The fixture and
 * the first vectors are lifted from spec/models/entities/contact_spec.rb "text_search".
 *
 * <pre>
 * 1  Bob     Dillion     bob_dillion@example.com     phone +1 123 456 789
 * 2  Shamus  O'Connell   shamus@example.net          phone +1 987 654 321
 * 3  Mary    Ann Smith   alt_email mary%ann@example.org   mobile 555-0300
 * 4  Ann     Smith-Jones
 * 5  Zed     Zimmer      (soft-deleted)              zed@example.com
 * </pre>
 */
class ContactTextSearchIT extends AbstractIntegrationTest {

    private static final long OWNER = 1;
    private static final long STRANGER = 2;
    private static final LocalDateTime T0 = LocalDateTime.of(2024, 5, 1, 9, 30, 0);

    @Autowired
    private ContactRepository repository;

    @BeforeEach
    void seed() {
        seeder.insertUser(OWNER, "owner", false);
        seeder.insertUser(STRANGER, "stranger", false);
        seeder.insertContact(row(1, "Bob", "Dillion", "bob_dillion@example.com", null, "+1 123 456 789", null, null));
        seeder.insertContact(row(2, "Shamus", "O'Connell", "shamus@example.net", null, "+1 987 654 321", null, null));
        seeder.insertContact(row(3, "Mary", "Ann Smith", null, "mary%ann@example.org", null, "555-0300", null));
        seeder.insertContact(row(4, "Ann", "Smith-Jones", null, null, null, null, null));
        seeder.insertContact(row(5, "Zed", "Zimmer", "zed@example.com", null, null, null, T0.plusDays(1)));
    }

    // ---- vectors from spec/models/entities/contact_spec.rb ----------------------------------

    @Test
    void searchesFirstNameAndLastName() {
        assertThat(search("Bob")).containsExactly(1L);
        assertThat(search("Dillion")).containsExactly(1L);
    }

    @Test
    void searchesWholeNameInBothOrders() {
        assertThat(search("Bob Dillion")).containsExactly(1L);
        assertThat(search("Dillion Bob")).containsExactly(1L);
    }

    @Test
    void searchesEmailAndPhone() {
        assertThat(search("example")).containsExactly(1L, 2L, 3L);
        assertThat(search("123")).containsExactly(1L);
    }

    @Test
    void doesNotBreakOnASingleQuote() {
        assertThat(search("O'Connell")).containsExactly(2L);
    }

    @Test
    void doesNotBreakOnSpecialCharacters() {
        assertThat(search("@$%#^@!")).isEmpty();
    }

    // ---- behaviour the Rails scope has that the vectors above do not exercise ---------------

    @Test
    void isCaseInsensitiveOnEveryColumn() {
        assertThat(search("BOB")).containsExactly(1L);
        assertThat(search("dILLION")).containsExactly(1L);
        assertThat(search("BOB_DILLION@EXAMPLE")).containsExactly(1L);
        assertThat(search("MARY%ANN")).containsExactly(3L);
    }

    @Test
    void searchesAltEmailAndMobile() {
        assertThat(search("example.org")).containsExactly(3L);
        assertThat(search("555-0300")).containsExactly(3L);
    }

    @Test
    void blankQueryMatchesEverythingThatIsNotSoftDeleted() {
        assertThat(search(null)).containsExactly(1L, 2L, 3L, 4L);
        assertThat(search("")).containsExactly(1L, 2L, 3L, 4L);
        assertThat(search("   ")).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void softDeletedRowsNeverMatch() {
        assertThat(search("Zed")).isEmpty();
        assertThat(search("zed@example.com")).isEmpty();
    }

    @Test
    void multiWordQueryTriesEverySplitOfFirstAndLastName() {
        assertThat(search("Mary Ann Smith")).containsExactly(3L);
        assertThat(search("Ann Smith Mary")).containsExactly(3L);
        // "Ann Smith" is split (Ann|Smith) / (Smith|Ann); Mary's last name alone never satisfies a pair
        assertThat(search("Ann Smith")).containsExactly(4L);
        assertThat(search("Smith Ann")).containsExactly(4L);
        assertThat(search("Mary Smith")).containsExactly(3L);
        assertThat(search("Bob Smith")).isEmpty();
    }

    @Test
    void multiWordQueryStillMatchesEmailAndPhoneOnTheWholeString() {
        assertThat(search("123 456")).containsExactly(1L);
        assertThat(search("1 123 456 789")).containsExactly(1L);
    }

    /** Arel {@code matches} does not escape {@code %} / {@code _}; the Java port must not either. */
    @Test
    void sqlWildcardsInTheQueryAreNotEscaped() {
        assertThat(search("%")).containsExactly(1L, 2L, 3L, 4L);
        assertThat(search("B_b")).containsExactly(1L);
        assertThat(search("bob%example")).containsExactly(1L);
        assertThat(search("O%Connell")).containsExactly(2L);
    }

    @Test
    void composesWithVisibilityScope() {
        seeder.insertContact(new ContactRow(6, OWNER, null, null, null, "Bob", "Private", ACCESS_PRIVATE, null, null,
                null, null, null, null, null, null, null, null, null, null, null, false, null, null, null, null,
                null, null, null, null, null, T0, T0));
        CurrentUser owner = new CurrentUser(OWNER, false, Set.of());
        CurrentUser stranger = new CurrentUser(STRANGER, false, Set.of());

        Specification<Contact> ownerSees = visibleTo(owner, "Contact");
        Specification<Contact> strangerSees = visibleTo(stranger, "Contact");

        assertThat(ids(repository.findAll(ownerSees.and(textSearch("Bob")), Sort.by("id")))).containsExactly(1L, 6L);
        assertThat(ids(repository.findAll(strangerSees.and(textSearch("Bob")), Sort.by("id")))).containsExactly(1L);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private List<Long> search(String query) {
        return ids(repository.findAll(textSearch(query), Sort.by("id")));
    }

    private static List<Long> ids(List<Contact> contacts) {
        return contacts.stream().map(Contact::getId).toList();
    }

    private static ContactRow row(long id, String firstName, String lastName, String email, String altEmail,
            String phone, String mobile, LocalDateTime deletedAt) {
        return new ContactRow(id, OWNER, null, null, null, firstName, lastName, ACCESS_PUBLIC, null, null, null,
                email, altEmail, phone, mobile, null, null, null, null, null, null, false, null, null, null, null,
                null, null, null, null, deletedAt, T0.minusHours(id), T0.plusHours(id));
    }
}
