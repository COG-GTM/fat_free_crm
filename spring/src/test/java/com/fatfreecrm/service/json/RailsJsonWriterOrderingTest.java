package com.fatfreecrm.service.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.RailsRow;
import com.fatfreecrm.repository.RailsRowRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The list endpoint hands the writer the ids in query order; the writer must return the rows in exactly that
 * order, skip ids that vanished between the two queries, and never fabricate rows.
 */
class RailsJsonWriterOrderingTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RailsJsonWriter writer;

    @Autowired
    private RailsRowRepository rows;

    @Autowired
    private RailsResources railsResources;

    private Account first;
    private Account second;
    private Account third;

    @BeforeEach
    void seed() {
        clearData();
        User owner = new User();
        owner.setUsername("writer-owner");
        owner.setEncryptedPassword("encrypted");
        owner.setPasswordSalt("salt");
        owner.setAdmin(false);
        owner.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        owner = userRepository.saveAndFlush(owner);
        first = account(owner, "First");
        second = account(owner, "Second");
        third = account(owner, "Third");
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void writesRowsInTheRequestedOrderNotDatabaseOrder() {
        List<ObjectNode> written = writer.write(
            railsResources.account, List.of(third.getId(), first.getId(), second.getId()));

        assertThat(written).extracting(node -> node.path("name").asText())
            .containsExactly("Third", "First", "Second");
        assertThat(written).extracting(node -> node.path("id").asLong())
            .containsExactly(third.getId(), first.getId(), second.getId());
    }

    @Test
    void skipsIdsThatNoLongerExistAndHandlesEmptyInput() {
        List<ObjectNode> written = writer.write(
            railsResources.account, List.of(first.getId(), 999_999L, second.getId()));

        assertThat(written).extracting(node -> node.path("name").asText()).containsExactly("First", "Second");
        assertThat(writer.write(railsResources.account, List.of())).isEmpty();
        assertThat(writer.write(railsResources.account, List.of(999_999L))).isEmpty();
    }

    @Test
    void duplicateIdsAreWrittenOncePerOccurrence() {
        List<ObjectNode> written = writer.write(
            railsResources.account, List.of(first.getId(), first.getId()));

        assertThat(written).extracting(node -> node.path("id").asLong())
            .containsExactly(first.getId(), first.getId());
    }

    @Test
    void writeOneFailsWithEntityNotFoundForMissingRows() {
        assertThatThrownBy(() -> writer.writeOne(railsResources.account, 999_999L))
            .isInstanceOf(EntityNotFoundException.class);
        assertThat(writer.writeOne(railsResources.account, second.getId()).path("name").asText())
            .isEqualTo("Second");
    }

    @Test
    void rowRepositoryReturnsEmptyForEmptyOrUnknownIdsAndExposesPostgresTypes() {
        assertThat(rows.findByIds(railsResources.account, List.of())).isEmpty();
        assertThat(rows.findByIds(railsResources.account, List.of(999_999L))).isEmpty();

        List<RailsRow> found = rows.findByIds(railsResources.account, List.of(first.getId()));
        assertThat(found).hasSize(1);
        RailsRow row = found.get(0);
        assertThat(row.id()).isEqualTo(first.getId());
        assertThat(row.columns().keySet()).startsWith("id", "user_id", "assigned_to", "name");
        assertThat(row.typeNames()).containsEntry("name", "varchar").containsEntry("created_at", "timestamp");
        assertThat(row.columns()).containsKeys("subscribed_users", "deleted_at").doesNotContainKey("tag_list");
    }

    private Account account(User owner, String name) {
        Account account = new Account();
        account.setName(name);
        account.setAccess("Public");
        account.setUser(owner);
        Instant at = Instant.now();
        account.setCreatedAt(at);
        account.setUpdatedAt(at);
        return accountRepository.saveAndFlush(account);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
