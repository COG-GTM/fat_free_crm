package com.fatfreecrm.service.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.service.audit.ColumnDefaults;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link RailsRowAttributes} feeds the PaperTrail {@code object} snapshot for the mail processors and
 * enrichment jobs, so the map must look like {@code record.attributes} in Rails: schema column order,
 * {@code custom_fields} excluded, timestamps as instants and {@code subscribed_users} deserialized.
 */
class RailsRowAttributesIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private RailsRowAttributes rowAttributes;

    @Autowired
    private ColumnDefaults columnDefaults;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    private User alice;

    @BeforeEach
    void seed() {
        clearData();
        User user = new User();
        user.setUsername("alice");
        user.setEmail("alice@example.test");
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(false);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        alice = userRepository.saveAndFlush(user);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void readsTheRowInSchemaColumnOrderWithRailsValueTypes() {
        long id = insertAccount("Acme", "---\n- 1\n- 2\n");

        Map<String, Object> attributes = rowAttributes.read("accounts", id);

        List<String> columns = jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns"
            + " WHERE table_name = 'accounts' ORDER BY ordinal_position", String.class);
        columns.remove("custom_fields");
        assertThat(new ArrayList<>(attributes.keySet())).isEqualTo(columns);
        assertThat(attributes).doesNotContainKey("custom_fields");
        assertThat(((Number) attributes.get("id")).longValue()).isEqualTo(id);
        assertThat(attributes.get("name")).isEqualTo("Acme");
        assertThat(attributes.get("access")).isEqualTo("Public");
        assertThat(attributes.get("created_at")).isInstanceOf(Instant.class);
        assertThat(attributes.get("updated_at")).isInstanceOf(Instant.class);
        assertThat(attributes.get("subscribed_users")).isEqualTo(List.of(1L, 2L));
        assertThat(attributes).containsEntry("deleted_at", null).containsEntry("assigned_to", null);
    }

    @Test
    void emptySubscribedUsersBecomesNilLikeRailsSerializedBlank() {
        assertThat(rowAttributes.read("accounts", insertAccount("Empty", "--- []\n")).get("subscribed_users"))
            .isNull();
        assertThat(rowAttributes.read("accounts", insertAccount("Null", null)).get("subscribed_users")).isNull();
    }

    @Test
    void entityOverloadResolvesTheRailsTableFromTheEntityClass() {
        long id = insertAccount("Entity", null);
        Account account = accountRepository.findById(id).orElseThrow();

        Map<String, Object> attributes = rowAttributes.read(account);

        assertThat(attributes.get("name")).isEqualTo("Entity");
        assertThat(rowAttributes.defaults(account)).isEqualTo(columnDefaults.defaults("accounts"));
        assertThat(rowAttributes.defaults("accounts")).isEqualTo(columnDefaults.defaults("accounts"));
    }

    @Test
    void dateColumnsBecomeLocalDates() {
        long id = jdbcTemplate.queryForObject("INSERT INTO opportunities (user_id, name, access, closes_on,"
                + " created_at, updated_at) VALUES (?, 'Deal', 'Public', DATE '2024-03-05', now(), now())"
                + " RETURNING id",
            Long.class, alice.getId());

        assertThat(rowAttributes.read("opportunities", id).get("closes_on")).isEqualTo(LocalDate.of(2024, 3, 5));
    }

    @Test
    void unknownTablesAndMissingRowsAreRejected() {
        assertThatThrownBy(() -> rowAttributes.read("settings", 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported Rails table settings");
        assertThatThrownBy(() -> rowAttributes.read("accounts; DROP TABLE accounts", 1L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rowAttributes.read("accounts", -1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No persisted row in accounts for id -1");
        assertThatThrownBy(() -> rowAttributes.read(new Object()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No Rails table for java.lang.Object");
        assertThatThrownBy(() -> rowAttributes.defaults(new Object())).isInstanceOf(IllegalArgumentException.class);
    }

    private long insertAccount(String name, String subscribedUsers) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO accounts (user_id, name, access, subscribed_users, created_at, updated_at)"
                + " VALUES (?, ?, 'Public', ?, now(), now()) RETURNING id",
            Long.class, alice.getId(), name, subscribedUsers);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM opportunities");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
