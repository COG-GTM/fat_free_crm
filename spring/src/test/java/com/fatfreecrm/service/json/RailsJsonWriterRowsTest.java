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
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Row loading and scalar mapping that {@link RailsJsonWriterTest} does not exercise: id ordering,
 * missing ids, the remaining PostgreSQL scalar types, and resources without {@code tag_list}.
 * The ALTER TABLE statements roll back with the test transaction.
 */
@TestPropertySource(properties = "spring.datasource.hikari.data-source-properties.prepareThreshold=0")
@Transactional
class RailsJsonWriterRowsTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private RailsJsonWriter jsonWriter;

    @Autowired
    private RailsRowRepository rowRepository;

    @Autowired
    private RailsResources railsResources;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    private RailsResource accounts;
    private Account first;
    private Account second;

    @BeforeEach
    void seedAccounts() {
        jdbcTemplate.update("DELETE FROM taggings");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
        accounts = railsResources.account;

        User owner = new User();
        owner.setUsername("rows_writer");
        owner.setEncryptedPassword("encrypted");
        owner.setPasswordSalt("salt");
        userRepository.saveAndFlush(owner);

        first = account(owner, "First Rows");
        second = account(owner, "Second Rows");
    }

    @Test
    void writesRowsInRequestedOrderAndSkipsUnknownIds() {
        List<ObjectNode> reversed = jsonWriter.write(accounts, List.of(second.getId(), first.getId(), 987654321L));

        assertThat(reversed).extracting(node -> node.get("id").asLong())
            .containsExactly(second.getId(), first.getId());
        assertThat(jsonWriter.write(accounts, List.of())).isEmpty();
        assertThat(jsonWriter.write(accounts, List.of(987654321L))).isEmpty();
    }

    @Test
    void writeOneRaisesEntityNotFoundForUnknownIds() {
        assertThatThrownBy(() -> jsonWriter.writeOne(accounts, 987654321L))
            .isInstanceOf(EntityNotFoundException.class)
            .hasMessageContaining("Account")
            .hasMessageContaining("987654321");
    }

    @Test
    void rowRepositoryReturnsEveryColumnWithPostgresTypeNames() {
        assertThat(rowRepository.findByIds(accounts, List.of())).isEmpty();

        List<RailsRow> rows = rowRepository.findByIds(accounts, List.of(first.getId()));
        assertThat(rows).hasSize(1);
        RailsRow row = rows.getFirst();
        assertThat(row.id()).isEqualTo(first.getId());
        assertThat(row.columns()).containsKeys("id", "name", "category", "access", "created_at", "subscribed_users");
        assertThat(row.typeNames().get("id")).isIn("int8", "bigserial");
        assertThat(row.typeNames().get("name")).isEqualTo("varchar");
        assertThat(row.typeNames().get("created_at")).isEqualTo("timestamp");
        assertThat(row.typeNames().get("subscribed_users")).isEqualTo("text");
    }

    @Test
    void mapsRemainingPostgresScalarTypesLikeRailsAsJson() {
        jdbcTemplate.execute("ALTER TABLE accounts"
            + " ADD COLUMN cf_small smallint,"
            + " ADD COLUMN cf_big bigint,"
            + " ADD COLUMN cf_real real,"
            + " ADD COLUMN cf_double double precision,"
            + " ADD COLUMN cf_whole numeric(12,2),"
            + " ADD COLUMN cf_flag boolean,"
            + " ADD COLUMN cf_on date,"
            + " ADD COLUMN cf_at timestamp with time zone,"
            + " ADD COLUMN cf_json jsonb,"
            + " ADD COLUMN cf_uuid uuid");
        jdbcTemplate.update("UPDATE accounts SET cf_small = 7, cf_big = 9000000000, cf_real = 1.5,"
                + " cf_double = 2.25, cf_whole = 1500.00, cf_flag = true, cf_on = DATE '2025-02-03',"
                + " cf_at = TIMESTAMPTZ '2025-02-03 04:05:06.789123+00', cf_json = '{\"k\":[1,\"v\"]}'::jsonb,"
                + " cf_uuid = '11111111-2222-3333-4444-555555555555' WHERE id = ?",
            first.getId());

        ObjectNode json = jsonWriter.writeOne(accounts, first.getId());

        assertThat(json.get("cf_small").isIntegralNumber()).isTrue();
        assertThat(json.get("cf_small").intValue()).isEqualTo(7);
        assertThat(json.get("cf_big").longValue()).isEqualTo(9000000000L);
        assertThat(json.get("cf_real").doubleValue()).isEqualTo(1.5);
        assertThat(json.get("cf_double").doubleValue()).isEqualTo(2.25);
        assertThat(json.get("cf_whole").asText()).isEqualTo("1500.0");
        assertThat(json.get("cf_flag").isBoolean()).isTrue();
        assertThat(json.get("cf_flag").booleanValue()).isTrue();
        assertThat(json.get("cf_on").asText()).isEqualTo("2025-02-03");
        assertThat(json.get("cf_at").asText()).isEqualTo("2025-02-03T04:05:06.789Z");
        assertThat(json.get("cf_json").isObject()).isTrue();
        assertThat(json.get("cf_json").get("k").get(1).asText()).isEqualTo("v");
        assertThat(json.get("cf_uuid").asText()).isEqualTo("11111111-2222-3333-4444-555555555555");

        ObjectNode untouched = jsonWriter.writeOne(accounts, second.getId());
        for (String column : List.of("cf_small", "cf_big", "cf_real", "cf_double", "cf_whole", "cf_flag",
            "cf_on", "cf_at", "cf_json", "cf_uuid")) {
            assertThat(untouched.get(column).isNull()).as(column).isTrue();
        }
    }

    @Test
    void nonTaggableResourcesHaveNoTagListAndKeepColumnOrder() {
        RailsResource plain = new RailsResource(
            "Account", "accounts", Account.class, Set.of("subscribed_users"), false,
            RailsResources.CRM_EXCLUDED_COLUMNS, "accounts", ignored -> "", Map.of());

        ObjectNode json = jsonWriter.writeOne(plain, first.getId());

        assertThat(json.has("tag_list")).isFalse();
        assertThat(json.has("custom_fields")).isFalse();
        assertThat(json.fieldNames().next()).isEqualTo("id");
        assertThat(json.get("subscribed_users").isArray()).isTrue();
        assertThat(jsonWriter.writeOne(accounts, first.getId()).get("tag_list").isArray()).isTrue();
    }

    private Account account(User owner, String name) {
        Account account = new Account();
        account.setName(name);
        account.setEmail(name.toLowerCase().replace(' ', '.') + "@example.test");
        account.setAccess("Public");
        account.setUser(owner);
        Instant at = Instant.parse("2025-01-03T00:00:00Z");
        account.setCreatedAt(at);
        account.setUpdatedAt(at);
        return accountRepository.saveAndFlush(account);
    }
}
