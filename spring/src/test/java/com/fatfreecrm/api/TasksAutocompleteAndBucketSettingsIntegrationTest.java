package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Rails {@code TasksController#auto_complete} ({@code Task.my(current_user)} +
 * {@code auto_complete_ids_to_exclude(params[:related])}), {@code Task.assigned_by} and the
 * {@code Setting.task_bucket} / {@code Setting.task_completed} overrides read by {@code Task.find_all_grouped}.
 */
class TasksAutocompleteAndBucketSettingsIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int ACCOUNT_ID = 77;
    private static final int OTHER_ACCOUNT_ID = 78;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private User bob;
    private String aliceBearer;
    private long aliceOwn;
    private long aliceToBob;
    private long bobToAlice;
    private long bobOwn;
    private long accountTask;
    private long otherAccountTask;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice");
        bob = user("bob");
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
        aliceOwn = task("Alice own", alice, null, null, null, null, null);
        aliceToBob = task("Alice to Bob", alice, bob, null, null, null, null);
        bobToAlice = task("Bob to Alice", bob, alice, null, null, null, null);
        bobOwn = task("Bob own", bob, null, null, null, null, null);
        accountTask = task("Account task", alice, null, null, null, "Account", ACCOUNT_ID);
        otherAccountTask = task("Other account task", alice, null, null, null, "Account", OTHER_ACCOUNT_ID);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void autocompleteFollowsRailsTaskMyScope() throws Exception {
        List<Long> ids = ids(autocomplete(aliceBearer, "term", ""));

        assertThat(ids).containsExactly(accountTask, aliceOwn, bobToAlice, otherAccountTask);
        assertThat(ids).doesNotContain(aliceToBob, bobOwn);
    }

    @Test
    void relatedAssetExcludesTasksOfThatAssetForSingularAndPluralNames() throws Exception {
        assertThat(ids(autocomplete(aliceBearer, "related", "account/" + ACCOUNT_ID)))
            .contains(otherAccountTask, aliceOwn).doesNotContain(accountTask);
        assertThat(ids(autocomplete(aliceBearer, "related", "accounts/" + ACCOUNT_ID)))
            .contains(otherAccountTask, aliceOwn).doesNotContain(accountTask);
        assertThat(ids(autocomplete(aliceBearer, "excludeRelated", "account/" + ACCOUNT_ID)))
            .doesNotContain(accountTask);
    }

    @Test
    void relatedWithoutMatchingAssetExcludesNothing() throws Exception {
        List<Long> all = List.of(accountTask, aliceOwn, bobToAlice, otherAccountTask);

        assertThat(ids(autocomplete(aliceBearer, "related", "campaigns/" + ACCOUNT_ID))).containsExactlyElementsOf(all);
        assertThat(ids(autocomplete(aliceBearer, "related", "unknown/" + ACCOUNT_ID))).containsExactlyElementsOf(all);
        assertThat(ids(autocomplete(aliceBearer, "related", "account/not-a-number")))
            .containsExactlyElementsOf(all);
        assertThat(ids(autocomplete(aliceBearer, "related", "account/99999999999"))).containsExactlyElementsOf(all);
        assertThat(ids(autocomplete(aliceBearer, "related", ""))).containsExactlyElementsOf(all);
        assertThat(ids(autocomplete(aliceBearer, "related", "abc"))).containsExactlyElementsOf(all);
    }

    @Test
    void bareRelatedIdExcludesThatTaskOnly() throws Exception {
        assertThat(ids(autocomplete(aliceBearer, "related", String.valueOf(aliceOwn))))
            .containsExactly(accountTask, bobToAlice, otherAccountTask);
    }

    @Test
    void autocompleteReturnsAtMostTenTasksOrderedByName() throws Exception {
        for (int i = 0; i < 12; i++) {
            task(String.format("Zed %02d", i), alice, null, null, null, null, null);
        }

        List<String> names = autocomplete(aliceBearer, "term", "Zed").at("/results").findValuesAsText("text");

        assertThat(names).hasSize(10);
        assertThat(names).isSorted();
        assertThat(names.get(0)).isEqualTo("Zed 00");
        assertThat(names.get(9)).isEqualTo("Zed 09");
    }

    @Test
    void assignedViewListsOnlyPendingTasksTheUserDelegatedToSomeoneElse() throws Exception {
        task("Alice to Bob done", alice, bob, Instant.now(), bob, null, null);

        JsonNode body = getJson("/api/v1/tasks", aliceBearer, "view", "assigned");
        List<Long> ids = new ArrayList<>();
        body.get("buckets").forEach(bucket -> bucket.forEach(task -> ids.add(task.get("id").asLong())));

        assertThat(ids).containsExactly(aliceToBob);
    }

    @Test
    void unknownViewFallsBackToPendingLikeRails() throws Exception {
        JsonNode body = getJson("/api/v1/tasks", aliceBearer, "view", "bogus");

        assertThat(fieldNames(body.get("buckets"))).containsExactly(
            "overdue", "due_asap", "due_today", "due_tomorrow", "due_this_week", "due_next_week", "due_later");
    }

    @Test
    void taskBucketSettingOverridesDefaultBucketsAndOrder() throws Exception {
        setting("task_bucket", "---\n- :due_today\n- :overdue\n");
        setting("task_completed", "---\n- completed_today\n");

        assertThat(fieldNames(getJson("/api/v1/tasks", aliceBearer, "view", "pending").get("buckets")))
            .containsExactly("due_today", "overdue");
        assertThat(fieldNames(getJson("/api/v1/tasks", aliceBearer, "view", "completed").get("buckets")))
            .containsExactly("completed_today");
    }

    @Test
    void unreadableTaskBucketSettingFallsBackToDefaults() throws Exception {
        setting("task_bucket", "--- just a string\n");

        assertThat(fieldNames(getJson("/api/v1/tasks", aliceBearer, "view", "pending").get("buckets")))
            .containsExactly(
                "overdue", "due_asap", "due_today", "due_tomorrow", "due_this_week", "due_next_week", "due_later");
    }

    private JsonNode autocomplete(String bearer, String... params) throws Exception {
        return getJson("/api/v1/tasks/autocomplete", bearer, params);
    }

    private JsonNode getJson(String path, String bearer, String... params) throws Exception {
        var request = get(path).header(HttpHeaders.AUTHORIZATION, bearer);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();
        return JSON.readTree(body);
    }

    private static List<Long> ids(JsonNode autocompleteBody) {
        List<Long> ids = new ArrayList<>();
        autocompleteBody.at("/results").forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
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

    private long task(String name, User owner, User assignee, Instant completedAt, User completedBy,
                      String assetType, Integer assetId) {
        return jdbcTemplate.queryForObject("INSERT INTO tasks (name, user_id, assigned_to, bucket, due_at, "
                + "completed_at, completed_by, category, asset_type, asset_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'due_asap', NULL, ?, ?, 'call', ?, ?, now(), now()) RETURNING id", Long.class,
            name, owner.getId(), assignee == null ? null : assignee.getId(),
            completedAt == null ? null : Timestamp.from(completedAt),
            completedBy == null ? null : completedBy.getId(), assetType, assetId);
    }

    private void setting(String name, String yaml) {
        jdbcTemplate.update("INSERT INTO settings (name, value, created_at, updated_at) VALUES (?, ?, now(), now())",
            name, yaml);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM settings WHERE name IN ('task_bucket', 'task_completed')");
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM users");
    }
}
