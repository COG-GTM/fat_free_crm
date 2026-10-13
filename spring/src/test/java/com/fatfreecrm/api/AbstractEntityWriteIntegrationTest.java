package com.fatfreecrm.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared fixture for the AB-272 Phase B {@code EntitiesWriteController} tests: three users
 * (alice, bob, admin), raw SQL seeds in the Rails column shapes, and PaperTrail/counter readers.
 */
abstract class AbstractEntityWriteIntegrationTest extends AbstractPostgresIntegrationTest {

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected JwtTokenService jwtTokenService;

    protected User alice;
    protected User bob;
    protected User admin;
    protected String aliceBearer;
    protected String bobBearer;
    protected String adminBearer;

    @BeforeEach
    void seedUsers() {
        clearData();
        alice = user("alice", false);
        bob = user("bob", false);
        admin = user("admin", true);
        aliceBearer = bearer(alice);
        bobBearer = bearer(bob);
        adminBearer = bearer(admin);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    // ------------------------------------------------------------------ requests

    protected static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder,
        String bearer) {
        return builder.header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON);
    }

    protected static JsonNode json(String body) throws Exception {
        return JSON.readTree(body);
    }

    // ------------------------------------------------------------------ seeds

    protected long account(String name, User owner, String access) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, user_id, access, created_at, updated_at)"
                + " VALUES (?, ?, ?, now(), now()) RETURNING id",
            Long.class, name, owner.getId(), access);
    }

    protected long campaign(String name, User owner, String access) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO campaigns (name, user_id, access, leads_count, opportunities_count,"
                + " revenue, created_at, updated_at) VALUES (?, ?, ?, 0, 0, 0, now(), now())"
                + " RETURNING id",
            Long.class, name, owner.getId(), access);
    }

    protected long contact(String first, String last, User owner, String access) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO contacts (first_name, last_name, user_id, access, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, now(), now()) RETURNING id",
            Long.class, first, last, owner.getId(), access);
    }

    protected long lead(String first, String last, User owner, String access, Long campaignId) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO leads (first_name, last_name, user_id, access, campaign_id, status,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'new', now(), now()) RETURNING id",
            Long.class, first, last, owner.getId(), access, campaignId);
    }

    protected long opportunity(String name, User owner, String access, Long campaignId) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO opportunities (name, user_id, access, campaign_id, stage, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, 'prospecting', now(), now()) RETURNING id",
            Long.class, name, owner.getId(), access, campaignId);
    }

    protected long task(String name, User owner, String assetType, Long assetId) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO tasks (name, user_id, bucket, asset_type, asset_id, created_at, updated_at)"
                + " VALUES (?, ?, 'due_asap', ?, ?, now(), now()) RETURNING id",
            Long.class, name, owner.getId(), assetType, assetId);
    }

    protected void permission(String assetType, long assetId, User user, Long groupId) {
        jdbcTemplate.update(
            "INSERT INTO permissions (user_id, group_id, asset_type, asset_id, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, now(), now())",
            user == null ? null : user.getId(), groupId, assetType, assetId);
    }

    protected long group(String name, User... members) {
        long id = jdbcTemplate.queryForObject(
            "INSERT INTO groups (name, created_at, updated_at) VALUES (?, now(), now())"
                + " RETURNING id", Long.class, name);
        for (User member : members) {
            jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)",
                id, member.getId());
        }
        return id;
    }

    protected void linkAccountContact(long accountId, long contactId) {
        jdbcTemplate.update("INSERT INTO account_contacts (account_id, contact_id, created_at,"
            + " updated_at) VALUES (?, ?, now(), now())", accountId, contactId);
        jdbcTemplate.update("UPDATE accounts SET contacts_count = COALESCE(contacts_count, 0) + 1"
            + " WHERE id = ?", accountId);
    }

    protected void linkAccountOpportunity(long accountId, long opportunityId) {
        jdbcTemplate.update("INSERT INTO account_opportunities (account_id, opportunity_id,"
            + " created_at, updated_at) VALUES (?, ?, now(), now())", accountId, opportunityId);
        jdbcTemplate.update("UPDATE accounts SET opportunities_count ="
            + " COALESCE(opportunities_count, 0) + 1 WHERE id = ?", accountId);
    }

    protected void setting(String name, String yamlValue) {
        jdbcTemplate.update("DELETE FROM settings WHERE name = ?", name);
        jdbcTemplate.update("INSERT INTO settings (name, value, created_at, updated_at)"
            + " VALUES (?, ?, now(), now())", name, yamlValue);
    }

    // ------------------------------------------------------------------ readers

    protected Map<String, Object> row(String table, long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM " + table + " WHERE id = ?", id);
    }

    protected boolean exists(String table, long id) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    protected long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    protected long count(String table, String where, Object... args) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where,
            Long.class, args);
    }

    protected List<Map<String, Object>> versions(String itemType, long itemId) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM versions WHERE item_type = ? AND item_id = ? ORDER BY id",
            itemType, itemId);
    }

    protected List<String> events(String itemType, long itemId) {
        return versions(itemType, itemId).stream().map(v -> (String) v.get("event")).toList();
    }

    protected List<Long> permissionUserIds(String assetType, long assetId) {
        return jdbcTemplate.queryForList(
            "SELECT user_id FROM permissions WHERE asset_type = ? AND asset_id = ?"
                + " AND user_id IS NOT NULL ORDER BY id", Long.class, assetType, assetId);
    }

    protected List<Long> subscribedUsers(String table, long id) {
        return jdbcTemplate.queryForList(
            "SELECT CAST(substring(line FROM 3) AS bigint) FROM"
                + " (SELECT unnest(string_to_array(subscribed_users, E'\\n')) AS line FROM "
                + table + " WHERE id = ?) lines WHERE line ~ '^- [0-9]+$'",
            Long.class, id);
    }

    protected List<String> tagNames(String taggableType, long taggableId) {
        return jdbcTemplate.queryForList(
            "SELECT t.name FROM taggings tg JOIN tags t ON t.id = tg.tag_id"
                + " WHERE tg.taggable_type = ? AND tg.taggable_id = ? AND tg.context = 'tags'"
                + " ORDER BY tg.id", String.class, taggableType, taggableId);
    }

    protected Integer counter(String table, String column, long id) {
        return jdbcTemplate.queryForObject(
            "SELECT " + column + " FROM " + table + " WHERE id = ?", Integer.class, id);
    }

    // ------------------------------------------------------------------ users

    protected String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    protected User user(String username, boolean admin) {
        User user = new User();
        user.setUsername(username);
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(admin);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    protected void clearData() {
        for (String table : List.of("versions", "comments", "tasks", "taggings", "tags",
            "permissions", "account_contacts", "account_opportunities", "contact_opportunities",
            "addresses", "contacts", "leads", "opportunities", "accounts", "campaigns",
            "groups_users", "groups", "settings", "users")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }
}
