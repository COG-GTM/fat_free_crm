package com.fatfreecrm.repository;

import static com.fatfreecrm.repository.AccountSpecifications.idEquals;
import static com.fatfreecrm.repository.AccountSpecifications.textSearch;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.domain.Account;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;

/**
 * {@link AccountSpecifications} against PostgreSQL, independent of access control. Rails
 * {@code Account.text_search(q)} is {@code ransack(name_or_email_cont: q)}: a case-insensitive
 * substring match in which the user's {@code %}, {@code _} and {@code \} are literal characters.
 *
 * <pre>
 * accounts: 10 "Acme"          email sales@acme.example
 *           11 "100% Cotton"   email null
 *           12 "under_score"   email null
 *           13 "back\slash"    email null
 *           14 "Émile & Co"    email EMILE@Example.ORG
 *           15 "Deleted"       email gone@acme.example, soft-deleted
 * </pre>
 */
class AccountSpecificationsTest extends AbstractIntegrationTest {

    @Autowired
    private AccountRepository repository;

    @BeforeEach
    void seed() {
        seeder.insertUser(1, "alice", false);
        seeder.insertAccount(10, "Acme", 1L, null, ACCESS_PUBLIC);
        seeder.insertAccount(11, "100% Cotton", 1L, null, ACCESS_PUBLIC);
        seeder.insertAccount(12, "under_score", 1L, null, ACCESS_PUBLIC);
        seeder.insertAccount(13, "back\\slash", 1L, null, ACCESS_PUBLIC);
        seeder.insertAccount(14, "Émile & Co", 1L, null, ACCESS_PUBLIC);
        seeder.insertAccount(15, "Deleted", 1L, null, ACCESS_PUBLIC, LocalDateTime.now().minusDays(1));
        seeder.jdbc().update("UPDATE accounts SET email = 'sales@acme.example' WHERE id = 10");
        seeder.jdbc().update("UPDATE accounts SET email = 'EMILE@Example.ORG' WHERE id = 14");
        seeder.jdbc().update("UPDATE accounts SET email = 'gone@acme.example' WHERE id = 15");
    }

    @Test
    void nullAndBlankQueriesMatchEveryRow() {
        assertThat(ids(textSearch(null))).containsExactly(10L, 11L, 12L, 13L, 14L);
        assertThat(ids(textSearch(""))).containsExactly(10L, 11L, 12L, 13L, 14L);
        assertThat(ids(textSearch("   "))).containsExactly(10L, 11L, 12L, 13L, 14L);
    }

    @Test
    void matchesNameOrEmailSubstringIgnoringCase() {
        assertThat(ids(textSearch("ACME"))).containsExactly(10L);
        assertThat(ids(textSearch("acme.example"))).containsExactly(10L);
        assertThat(ids(textSearch("example.org"))).containsExactly(14L);
        assertThat(ids(textSearch("emile@"))).containsExactly(14L);
        assertThat(ids(textSearch("& co"))).containsExactly(14L);
        assertThat(ids(textSearch("nomatch"))).isEmpty();
    }

    @Test
    void sqlWildcardsInTheQueryAreLiteral() {
        assertThat(ids(textSearch("%"))).containsExactly(11L);
        assertThat(ids(textSearch("100% c"))).containsExactly(11L);
        assertThat(ids(textSearch("_"))).containsExactly(12L);
        assertThat(ids(textSearch("r_s"))).containsExactly(12L);
        assertThat(ids(textSearch("\\"))).containsExactly(13L);
        assertThat(ids(textSearch("k\\s"))).containsExactly(13L);
        assertThat(ids(textSearch("a_me"))).isEmpty();
        assertThat(ids(textSearch("a%e"))).isEmpty();
    }

    @Test
    void softDeletedRowsNeverMatch() {
        assertThat(ids(textSearch("gone@"))).isEmpty();
        assertThat(ids(textSearch("Deleted"))).isEmpty();
        assertThat(repository.findOne(idEquals(15))).isEmpty();
    }

    @Test
    void idEqualsSelectsExactlyOneRow() {
        assertThat(repository.findOne(idEquals(12))).hasValueSatisfying(a -> assertThat(a.getName()).isEqualTo("under_score"));
        assertThat(repository.findOne(idEquals(999))).isEmpty();
        assertThat(ids(idEquals(10).and(textSearch("cotton")))).isEmpty();
        assertThat(ids(idEquals(11).and(textSearch("cotton")))).containsExactly(11L);
    }

    private List<Long> ids(Specification<Account> spec) {
        return repository.findAll(spec).stream().map(Account::getId).sorted().toList();
    }
}
