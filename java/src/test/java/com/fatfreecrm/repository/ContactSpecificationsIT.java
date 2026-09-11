package com.fatfreecrm.repository;

import static com.fatfreecrm.repository.ContactSpecifications.idEquals;
import static com.fatfreecrm.repository.ContactSpecifications.textSearch;
import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.support.TestDataSeeder.ContactRow;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link ContactSpecifications#textSearch} against PostgreSQL, pinned to the Rails
 * {@code Contact.text_search} examples in spec/models/entities/contact_spec.rb ("text_search")
 * and to the Arel {@code matches} semantics that scope relies on (case-insensitive, wildcards
 * in the query left unescaped).
 *
 * <pre>
 * contacts: 1 Bob Dillion        bob_dillion@example.com   phone +1 123 456 789
 *           2 Shamus O'Connell   shamus@oconnell.example   phone +1 123 456 789   mobile 07700 900123
 *           3 Mary Ann Smith     (three-word full name: first "Mary Ann", last "Smith")
 *           4 Zed Zulu           soft-deleted, otherwise matches nothing above
 * </pre>
 */
class ContactSpecificationsIT extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2024, 5, 1, 9, 30, 0);

    @Autowired
    private ContactRepository contacts;

    @BeforeEach
    void seed() {
        seeder.insertUser(1, "alice", false);
        seeder.insertContact(row(1, "Bob", "Dillion", "bob_dillion@example.com", null, "+1 123 456 789", null, null));
        seeder.insertContact(row(2, "Shamus", "O'Connell", "shamus@oconnell.example", null, "+1 123 456 789",
                "07700 900123", null));
        seeder.insertContact(row(3, "Mary Ann", "Smith", null, "mary@alt.example", null, null, null));
        seeder.insertContact(row(4, "Zed", "Zulu", null, null, null, null, T0.plusDays(1)));
    }

    // ---- the Rails spec examples -------------------------------------------------------------

    @Test
    void searchesFirstName() {
        assertThat(search("Bob")).containsExactly(1L);
    }

    @Test
    void searchesLastName() {
        assertThat(search("Dillion")).containsExactly(1L);
    }

    @Test
    void searchesWholeNameInBothOrders() {
        assertThat(search("Bob Dillion")).containsExactly(1L);
        assertThat(search("Dillion Bob")).containsExactly(1L);
    }

    @Test
    void searchesEmail() {
        assertThat(search("example")).containsExactly(1L, 2L, 3L);
        assertThat(search("bob_dillion@")).containsExactly(1L);
        assertThat(search("oconnell")).containsExactly(2L);
    }

    @Test
    void searchesPhoneFragment() {
        assertThat(search("123")).containsExactly(1L, 2L);
    }

    @Test
    void doesNotBreakOnASingleQuote() {
        assertThat(search("O'Connell")).containsExactly(2L);
        assertThat(search("Shamus O'Connell")).containsExactly(2L);
    }

    @Test
    void doesNotBreakOnSpecialCharacters() {
        assertThat(search("@$%#^@!")).isEmpty();
    }

    // ---- columns and semantics the Rails scope adds beyond the spec --------------------------

    @Test
    void searchesAltEmailAndMobile() {
        assertThat(search("alt.example")).containsExactly(3L);
        assertThat(search("900123")).containsExactly(2L);
    }

    @Test
    void isCaseInsensitiveLikeArelMatches() {
        assertThat(search("bob")).containsExactly(1L);
        assertThat(search("DILLION")).containsExactly(1L);
        assertThat(search("dillion BOB")).containsExactly(1L);
        assertThat(search("BOB_DILLION@EXAMPLE.COM")).containsExactly(1L);
    }

    @Test
    void multiWordQueryTriesEveryFirstLastSplit() {
        assertThat(search("Mary Ann Smith")).containsExactly(3L);
        assertThat(search("Smith Mary Ann")).containsExactly(3L);
        assertThat(search("Ann Smith")).containsExactly(3L);
        assertThat(search("Mary Smith")).as("substring match on each side of the split").containsExactly(3L);
        assertThat(search("Ann Dillion")).as("no split puts both halves on matching columns").isEmpty();
    }

    @Test
    void multiWordQueryWithNoMatchingSplitStillMatchesContactColumns() {
        // "+1 123" has a space, so only (first, last) splits are tried for the name -- none match --
        // but the whole term is still matched against phone, as in Rails
        assertThat(search("+1 123")).containsExactly(1L, 2L);
        assertThat(search("07700 900123")).containsExactly(2L);
    }

    @Test
    void sqlWildcardsInTheQueryAreNotEscapedLikeRails() {
        // Rails Contact.text_search interpolates the raw query into "%...%" (unlike Account, which
        // goes through Ransack), so % and _ keep their LIKE meaning
        assertThat(search("Sham%s")).containsExactly(2L);
        assertThat(search("O_Connell")).containsExactly(2L);
        assertThat(search("%")).containsExactly(1L, 2L, 3L);
    }

    @Test
    void blankQueryMatchesEverythingThatIsNotSoftDeleted() {
        assertThat(search(null)).containsExactly(1L, 2L, 3L);
        assertThat(search("")).containsExactly(1L, 2L, 3L);
        assertThat(search("   ")).containsExactly(1L, 2L, 3L);
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertThat(search("  Bob  ")).containsExactly(1L);
        assertThat(search(" Dillion   Bob ")).containsExactly(1L);
    }

    @Test
    void softDeletedRowsNeverMatch() {
        assertThat(search("Zulu")).isEmpty();
        assertThat(contacts.findOne(idEquals(4))).isEmpty();
    }

    @Test
    void idEqualsSelectsExactlyOneRow() {
        assertThat(contacts.findOne(idEquals(2))).map(Contact::getLastName).contains("O'Connell");
        assertThat(contacts.findOne(idEquals(999))).isEmpty();
    }

    // ---- helpers ----------------------------------------------------------------------------

    private List<Long> search(String query) {
        return contacts.findAll(textSearch(query)).stream().map(Contact::getId).sorted().toList();
    }

    private static ContactRow row(long id, String firstName, String lastName, String email, String altEmail,
            String phone, String mobile, LocalDateTime deletedAt) {
        return new ContactRow(id, 1L, null, null, null, firstName, lastName, "Public", null, null, null, email,
                altEmail, phone, mobile, null, null, null, null, null, null, false, null, null, null, null, null,
                null, null, null, deletedAt, T0, T0);
    }
}
