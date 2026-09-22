package com.fatfreecrm.repository;

import static com.fatfreecrm.repository.AccountSpecifications.idEquals;
import static com.fatfreecrm.repository.AccountSpecifications.textSearch;
import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.support.TestDataSeeder.AccountRow;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link AccountSpecifications#textSearch} must reproduce Rails
 * {@code Account.text_search(q)} = {@code ransack(name_or_email_cont: q)}: Ransack's {@code cont}
 * predicate escapes {@code %}, {@code _} and {@code \} so they match literally, matching is
 * case-insensitive, and a blank predicate is ignored.
 *
 * <pre>
 * 20 "100% Cotton"    email null
 * 21 "under_score"    email null
 * 22 "back\slash"     email null
 * 23 "Plain"          email SALES@Plain.EXAMPLE
 * 24 "Deleted"        email deleted@plain.example, soft-deleted
 * </pre>
 */
class AccountSpecificationsIT extends AbstractIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void seed() {
        seeder.insertUser(1, "alice", false);
        seeder.insertAccount(row(20, "100% Cotton", null, null));
        seeder.insertAccount(row(21, "under_score", null, null));
        seeder.insertAccount(row(22, "back\\slash", null, null));
        seeder.insertAccount(row(23, "Plain", "SALES@Plain.EXAMPLE", null));
        seeder.insertAccount(row(24, "Deleted", "deleted@plain.example", LocalDateTime.now().minusDays(1)));
    }

    @Test
    void percentMatchesOnlyRowsContainingALiteralPercent() {
        assertThat(search("%")).containsExactly(20L);
        assertThat(search("100%")).containsExactly(20L);
        assertThat(search("0% c")).containsExactly(20L);
    }

    @Test
    void underscoreMatchesOnlyRowsContainingALiteralUnderscore() {
        assertThat(search("_")).containsExactly(21L);
        assertThat(search("r_s")).containsExactly(21L);
    }

    @Test
    void backslashMatchesOnlyRowsContainingALiteralBackslash() {
        assertThat(search("\\")).containsExactly(22L);
        assertThat(search("k\\s")).containsExactly(22L);
        assertThat(search("\\%")).isEmpty();
    }

    @Test
    void matchingIsCaseInsensitiveOnBothNameAndEmail() {
        assertThat(search("pLaIn")).containsExactly(23L);
        assertThat(search("sales@plain")).containsExactly(23L);
        assertThat(search("PLAIN.EXAMPLE")).containsExactly(23L);
        assertThat(search("COTTON")).containsExactly(20L);
    }

    @Test
    void nullOrBlankQueryMatchesEveryLiveRow() {
        assertThat(search(null)).containsExactly(20L, 21L, 22L, 23L);
        assertThat(search("")).containsExactly(20L, 21L, 22L, 23L);
        assertThat(search("   ")).containsExactly(20L, 21L, 22L, 23L);
        assertThat(search("\t\n")).containsExactly(20L, 21L, 22L, 23L);
    }

    @Test
    void surroundingWhitespaceIsPartOfTheSearchTerm() {
        assertThat(search(" Plain")).isEmpty();
        assertThat(search("100% ")).containsExactly(20L);
    }

    @Test
    void softDeletedRowsAreExcludedEvenWhenTheyMatch() {
        assertThat(search("deleted")).isEmpty();
        assertThat(accountRepository.findOne(idEquals(24))).isEmpty();
    }

    @Test
    void idEqualsSelectsExactlyOneLiveRow() {
        assertThat(accountRepository.findOne(idEquals(23))).get().extracting(Account::getName).isEqualTo("Plain");
        assertThat(accountRepository.findOne(idEquals(999))).isEmpty();
        assertThat(accountRepository.findAll(idEquals(23).and(textSearch("nomatch")))).isEmpty();
    }

    private List<Long> search(String query) {
        return accountRepository.findAll(textSearch(query)).stream().map(Account::getId).sorted().toList();
    }

    private static AccountRow row(long id, String name, String email, LocalDateTime deletedAt) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        return new AccountRow(id, 1L, null, name, "Public", null, null, null, null, email, null, 0, null,
                null, 0, 0, null, null, null, null, null, null, null, null, null, null, deletedAt, now, now);
    }
}
