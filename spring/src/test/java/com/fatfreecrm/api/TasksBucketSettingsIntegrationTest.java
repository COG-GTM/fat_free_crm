package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins {@code TasksController#index}/{@code #auto_complete} behaviour that the Rails/Spring matrix does not
 * vary: {@code Setting.task_bucket}/{@code task_completed} driven bucket lists, the {@code assigned} view
 * ({@code Task.assigned_by}), {@code Task.my} scoping of autocomplete and the
 * {@code auto_complete_ids_to_exclude(related)} forms.
 */
class TasksBucketSettingsIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> DEFAULT_PENDING = List.of(
        "overdue", "due_asap", "due_today", "due_tomorrow", "due_this_week", "due_next_week", "due_later");
    private static final List<String> DEFAULT_COMPLETED = List.of(
        "completed_today", "completed_yesterday", "completed_last_week", "completed_this_month",
        "completed_last_month");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private User bob;
    private String aliceBearer;
    private String bobBearer;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice");
        bob = user("bob");
        aliceBearer = bearer(alice);
        bobBearer = bearer(bob);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void bucketListsComeFromRailsSettingsWithSymbolPrefixesStripped() throws Exception {
        setting("task_bucket", "---\n- :due_today\n- :overdue\n");
        setting("task_completed", "---\n- :completed_this_month\n- completed_today\n");

        assertThat(fieldNames(getJson("/api/v1/tasks", aliceBearer, "view", "pending").get("buckets")))
            .containsExactly("due_today", "overdue");
        assertThat(fieldNames(getJson("/api/v1/tasks", aliceBearer, "view", "assigned").get("buckets")))
            .containsExactly("due_today", "overdue");
        assertThat(fieldNames(getJson("/api/v1/tasks", aliceBearer, "view", "completed").get("buckets")))
            .containsExactly("completed_this_month", "completed_today");
    }

    @Test
    void unusableBucketSettingsFallBackToTheRailsDefaults() throws Exception {
        setting("task_bucket", "--- []\n");
        setting("task_completed", "--- due_today\n");
        assertThat(fieldNames(getJson("/api/v1/tasks", aliceBearer, "view", "pending").get("buckets")))
            .containsExactlyElementsOf(DEFAULT_PENDING);
        assertThat(fieldNames(getJson("/api/v1/tasks", aliceBearer, "view", "completed").get("buckets")))
            .containsExactlyElementsOf(DEFAULT_COMPLETED);

        jdbcTemplate.update("UPDATE settings SET value = ? WHERE name = 'task_bucket'", "---\n- 1\n- 2\n");
        jdbcTemplate.update("UPDATE settings SET value = NULL WHERE name = 'task_completed'");
        assertThat(fieldNames(getJson("/api/v1/tasks", aliceBearer, "view", "pending").get("buckets")))
            .containsExactlyElementsOf(DEFAULT_PENDING);
        assertThat(fieldNames(getJson("/api/v1/tasks", aliceBearer, "view", "completed").get("buckets")))
            .containsExactlyElementsOf(DEFAULT_COMPLETED);
    }

    @Test
    void assignedViewListsOnlyPendingTasksDelegatedToSomebodyElse() throws Exception {
        long delegated = task("Delegated", alice, bob, null, null);
        task("Self assigned", alice, alice, null, null);
        task("Unassigned", alice, null, null, null);
        task("Delegated but done", alice, bob, Instant.now(), bob);
        long receivedFromBob = task("Received", bob, alice, null, null);

        JsonNode aliceAssigned = getJson("/api/v1/tasks", aliceBearer, "view", "assigned");
        assertThat(ids(aliceAssigned)).containsExactly(delegated);

        JsonNode bobAssigned = getJson("/api/v1/tasks", bobBearer, "view", "assigned");
        assertThat(ids(bobAssigned)).containsExactly(receivedFromBob);

        JsonNode bobPending = getJson("/api/v1/tasks", bobBearer, "view", "pending");
        assertThat(ids(bobPending)).containsExactly(delegated);
    }

    @Test
    void autocompleteIsScopedToTaskMyAndAssigneesCanShowTheTask() throws Exception {
        long own = task("Call own", alice, null, null, null);
        long receivedFromBob = task("Call received", bob, alice, null, null);
        task("Call delegated", alice, bob, null, null);
        task("Call bobs", bob, null, null, null);
        task("Call closed by alice", bob, null, Instant.now(), alice);

        JsonNode body = getJson("/api/v1/tasks/autocomplete", aliceBearer, "term", "Call");
        assertThat(resultIds(body)).containsExactlyInAnyOrder(own, receivedFromBob);
        assertThat(resultIds(getJson("/api/v1/tasks/autocomplete", aliceBearer, "term", "")))
            .containsExactlyInAnyOrder(own, receivedFromBob);

        mockMvc.perform(get("/api/v1/tasks/" + receivedFromBob).header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(receivedFromBob))
            .andExpect(jsonPath("$.assigned_to").value(alice.getId()));
    }

    @Test
    void relatedExclusionsAcceptBareIdsAndSingularOrPluralAssetNames() throws Exception {
        long accountId = jdbcTemplate.queryForObject("INSERT INTO accounts (name, user_id, access, created_at, "
            + "updated_at) VALUES ('Acme', ?, 'Public', now(), now()) RETURNING id", Long.class, alice.getId());
        long otherAccountId = jdbcTemplate.queryForObject("INSERT INTO accounts (name, user_id, access, "
            + "created_at, updated_at) VALUES ('Beta', ?, 'Public', now(), now()) RETURNING id", Long.class,
            alice.getId());
        long acmeTask = task("Call Acme", alice, null, null, null);
        long betaTask = task("Call Beta", alice, null, null, null);
        long plain = task("Call plain", alice, null, null, null);
        jdbcTemplate.update("UPDATE tasks SET asset_type = 'Account', asset_id = ? WHERE id = ?", accountId, acmeTask);
        jdbcTemplate.update("UPDATE tasks SET asset_type = 'Account', asset_id = ? WHERE id = ?", otherAccountId,
            betaTask);

        assertThat(resultIds(autocomplete("related", String.valueOf(plain))))
            .containsExactlyInAnyOrder(acmeTask, betaTask);
        assertThat(resultIds(autocomplete("related", "account/" + accountId)))
            .containsExactlyInAnyOrder(betaTask, plain);
        assertThat(resultIds(autocomplete("related", "accounts/" + accountId)))
            .containsExactlyInAnyOrder(betaTask, plain);
        assertThat(resultIds(autocomplete("related", "contact/" + accountId)))
            .containsExactlyInAnyOrder(acmeTask, betaTask, plain);
        assertThat(resultIds(autocomplete("related", "unknown/" + accountId)))
            .containsExactlyInAnyOrder(acmeTask, betaTask, plain);
        assertThat(resultIds(autocomplete("related", "account/abc")))
            .containsExactlyInAnyOrder(acmeTask, betaTask, plain);
        assertThat(resultIds(autocomplete("related", "account/99999999999")))
            .containsExactlyInAnyOrder(acmeTask, betaTask, plain);
        assertThat(resultIds(autocomplete("related", "")))
            .containsExactlyInAnyOrder(acmeTask, betaTask, plain);

        JsonNode precedence = getJson("/api/v1/tasks/autocomplete", aliceBearer, "term", "Call",
            "excludeRelated", "account/" + otherAccountId, "related", "account/" + accountId);
        assertThat(resultIds(precedence)).containsExactlyInAnyOrder(acmeTask, plain);
    }

    private JsonNode autocomplete(String name, String value) throws Exception {
        return getJson("/api/v1/tasks/autocomplete", aliceBearer, "term", "Call", name, value);
    }

    private JsonNode getJson(String path, String bearer, String... params) throws Exception {
        var request = get(path).header(HttpHeaders.AUTHORIZATION, bearer);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        String content = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();
        return JSON.readTree(content);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<Long> ids(JsonNode body) {
        List<Long> ids = new ArrayList<>();
        body.path("buckets").forEach(bucket -> bucket.forEach(item -> ids.add(item.path("id").asLong())));
        return ids;
    }

    private static List<Long> resultIds(JsonNode body) {
        List<Long> ids = new ArrayList<>();
        body.path("results").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(false);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private void setting(String name, String yaml) {
        jdbcTemplate.update("INSERT INTO settings (name, value, created_at, updated_at) VALUES (?, ?, now(), now())",
            name, yaml);
    }

    private long task(String name, User owner, User assignee, Instant completedAt, User completedBy) {
        return jdbcTemplate.queryForObject("INSERT INTO tasks (name, user_id, assigned_to, bucket, completed_at, "
                + "completed_by, category, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'due_asap', ?, ?, 'call', now(), now()) RETURNING id", Long.class,
            name, owner.getId(), assignee == null ? null : assignee.getId(),
            completedAt == null ? null : Timestamp.from(completedAt),
            completedBy == null ? null : completedBy.getId());
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM settings WHERE name IN ('task_bucket', 'task_completed')");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
