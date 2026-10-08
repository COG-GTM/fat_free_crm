package com.fatfreecrm.service.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link RailsJsonWriter#write} is handed the ids in the order the paginated query produced them; the serializer
 * must preserve that order (Rails renders {@code @accounts.to_json} in scope order) and must skip ids whose rows
 * disappeared between the id query and the row read instead of emitting nulls or failing.
 */
class RailsJsonWriterOrderingTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private RailsJsonWriter jsonWriter;

    @Autowired
    private RailsResources railsResources;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long userId;
    private long zulu;
    private long alpha;
    private long mike;

    @BeforeEach
    void seed() {
        clearData();
        userId = jdbcTemplate.queryForObject("INSERT INTO users (username, encrypted_password, password_salt, "
            + "admin, created_at, updated_at) VALUES ('writer', 'x', 'y', false, now(), now()) RETURNING id",
            Long.class);
        zulu = account("Zulu");
        alpha = account("Alpha");
        mike = account("Mike");
    }

    @AfterEach
    void cleanup() {
        clearData();
    }

    @Test
    void preservesTheRequestedIdOrderRatherThanPrimaryKeyOrder() {
        List<ObjectNode> rows = jsonWriter.write(railsResources.account, List.of(mike, zulu, alpha));
        assertThat(rows).extracting(row -> row.get("id").asLong()).containsExactly(mike, zulu, alpha);
        assertThat(rows).extracting(row -> row.get("name").asText()).containsExactly("Mike", "Zulu", "Alpha");
    }

    @Test
    void skipsIdsWithoutARowAndReturnsEmptyForNoIds() {
        List<ObjectNode> rows = jsonWriter.write(railsResources.account, List.of(alpha, 999_999_999L, zulu));
        assertThat(rows).extracting(row -> row.get("id").asLong()).containsExactly(alpha, zulu);
        assertThat(jsonWriter.write(railsResources.account, List.of())).isEmpty();
        assertThat(jsonWriter.write(railsResources.account, List.of(999_999_999L))).isEmpty();
    }

    @Test
    void duplicateIdsAreEmittedOncePerRequestedPosition() {
        List<ObjectNode> rows = jsonWriter.write(railsResources.account, List.of(alpha, alpha));
        assertThat(rows).extracting(row -> row.get("id").asLong()).containsExactly(alpha, alpha);
    }

    private long account(String name) {
        return jdbcTemplate.queryForObject("INSERT INTO accounts (user_id, name, access, created_at, updated_at) "
            + "VALUES (?, ?, 'Public', now(), now()) RETURNING id", Long.class, userId, name);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM taggings");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
