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
 * Rails {@code Task.find_all_grouped} drives bucket names from {@code Setting.task_bucket} /
 * {@code Setting.task_completed} (YAML symbol arrays), the {@code assigned} view from {@code Task.assigned_by},
 * and {@code TasksController#auto_complete} from {@code Task.my} plus
 * {@code ApplicationController#auto_complete_ids_to_exclude}. These cases pin the parts of that behaviour that
 * the Rails-generated matrix does not exercise (settings overrides, asset-based exclusions, cross-user scoping).
 */
class TasksControllerBucketSettingsIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> DEFAULT_PENDING = List.of("overdue", "due_asap", "due_today", "due_tomorrow",
        "due_this_week", "due_next_week", "due_later");

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
    private long accountId;
    private long aliceOwnTask;
    private long aliceAccountTask;
    private long aliceAssignedToBob;
    private long aliceAssignedToBobDone;
    private long bobOwnTask;
    private long bobAssignedToAlice;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice");
        bob = user("bob");
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
        bobBearer = "Bearer " + jwtTokenService.issue(bob).accessToken();
        accountId = jdbcTemplate.queryForObject("INSERT INTO accounts (user_id, name, access, created_at, "
            + "updated_at) VALUES (?, 'Acme', 'Public', now(), now()) RETURNING id", Long.class, alice.getId());

        aliceOwnTask = task("Call Alice own", alice, null, "due_asap", null, null, null, null);
        aliceAccountTask = task("Call Acme follow-up", alice, null, "due_later", "Account", accountId, null, null);
        aliceAssignedToBob = task("Call delegated to Bob", alice, bob, "due_asap", null, null, null, null);
        aliceAssignedToBobDone = task("Call delegated done", alice, bob, "due_asap", null, null, Instant.now(), bob);
        bobOwnTask = task("Call Bob own", bob, null, "due_asap", null, null, null, null);
        bobAssignedToAlice = task("Call delegated to Alice", bob, alice, "due_asap", null, null, null, null);
    }

    @AfterEach
    void cleanup() {
        clearData();
    }

    @Test
    void pendingViewUsesTaskMyScopeAndTheDefaultBucketOrder() throws Exception {
        JsonNode buckets = getJson("/api/v1/tasks", aliceBearer, "view", "pending").get("buckets");
        assertThat(fieldNames(buckets)).isEqualTo(DEFAULT_PENDING);
        assertThat(ids(buckets)).containsExactlyInAnyOrder(aliceOwnTask, aliceAccountTask, bobAssignedToAlice);

        JsonNode bobBuckets = getJson("/api/v1/tasks", bobBearer, "view", "pending").get("buckets");
        assertThat(ids(bobBuckets)).containsExactlyInAnyOrder(bobOwnTask, aliceAssignedToBob);
    }

    @Test
    void assignedViewListsOpenTasksTheCallerDelegatedToSomeoneElse() throws Exception {
        JsonNode buckets = getJson("/api/v1/tasks", aliceBearer, "view", "assigned").get("buckets");
        assertThat(fieldNames(buckets)).isEqualTo(DEFAULT_PENDING);
        assertThat(ids(buckets)).containsExactly(aliceAssignedToBob);
        assertThat(buckets.get("due_asap").get(0).get("assigned_to").asLong()).isEqualTo(bob.getId());

        JsonNode bobBuckets = getJson("/api/v1/tasks", bobBearer, "view", "assigned").get("buckets");
        assertThat(ids(bobBuckets)).containsExactly(bobAssignedToAlice);
    }

    @Test
    void unknownViewFallsBackToPendingLikeRailsAllowedViews() throws Exception {
        JsonNode bogus = getJson("/api/v1/tasks", aliceBearer, "view", "bogus");
        JsonNode pending = getJson("/api/v1/tasks", aliceBearer, "view", "pending");
        assertThat(bogus).isEqualTo(pending);
        assertThat(getJson("/api/v1/tasks", aliceBearer, "view", "")).isEqualTo(pending);
    }

    @Test
    void configuredTaskBucketSettingControlsBucketNamesAndOrder() throws Exception {
        setting("task_bucket", "---\n- :due_later\n- :due_asap\n");

        JsonNode buckets = getJson("/api/v1/tasks", aliceBearer, "view", "pending").get("buckets");
        assertThat(fieldNames(buckets)).containsExactly("due_later", "due_asap");
        assertThat(ids(buckets.get("due_later"))).containsExactly(aliceAccountTask);
        assertThat(ids(buckets.get("due_asap"))).containsExactlyInAnyOrder(aliceOwnTask, bobAssignedToAlice);

        JsonNode assigned = getJson("/api/v1/tasks", aliceBearer, "view", "assigned").get("buckets");
        assertThat(fieldNames(assigned)).containsExactly("due_later", "due_asap");
    }

    @Test
    void configuredTaskCompletedSettingControlsCompletedBuckets() throws Exception {
        setting("task_completed", "---\n- :completed_last_month\n- :completed_today\n");

        JsonNode buckets = getJson("/api/v1/tasks", bobBearer, "view", "completed").get("buckets");
        assertThat(fieldNames(buckets)).containsExactly("completed_last_month", "completed_today");
        assertThat(ids(buckets)).containsExactly(aliceAssignedToBobDone);

        JsonNode pending = getJson("/api/v1/tasks", bobBearer, "view", "pending").get("buckets");
        assertThat(fieldNames(pending)).as("task_completed does not leak into pending").isEqualTo(DEFAULT_PENDING);
    }

    @Test
    void unknownBucketNameInSettingIsAServerErrorNotASilentEmptyBucket() throws Exception {
        setting("task_bucket", "---\n- :due_asap\n- :not_a_bucket\n");
        mockMvc.perform(get("/api/v1/tasks").param("view", "pending").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isInternalServerError());
    }

    @Test
    void autocompleteIsScopedToTaskMyAndHonoursAssetRelatedExclusions() throws Exception {
        assertThat(autocompleteIds(aliceBearer, "term", "Call"))
            .containsExactlyInAnyOrder(aliceOwnTask, aliceAccountTask, bobAssignedToAlice);

        assertThat(autocompleteIds(aliceBearer, "term", "Call", "related", "accounts/" + accountId))
            .containsExactlyInAnyOrder(aliceOwnTask, bobAssignedToAlice);
        assertThat(autocompleteIds(aliceBearer, "term", "Call", "related", "account/" + accountId))
            .as("Rails classify accepts singular and plural")
            .containsExactlyInAnyOrder(aliceOwnTask, bobAssignedToAlice);
        assertThat(autocompleteIds(aliceBearer, "term", "Call", "related", String.valueOf(aliceOwnTask)))
            .containsExactlyInAnyOrder(aliceAccountTask, bobAssignedToAlice);
    }

    @Test
    void autocompleteIgnoresUnknownRelatedAssetsAndMissingRecords() throws Exception {
        List<Long> all = List.of(aliceOwnTask, aliceAccountTask, bobAssignedToAlice);
        assertThat(autocompleteIds(aliceBearer, "term", "Call", "related", "accounts/999999"))
            .containsExactlyInAnyOrderElementsOf(all);
        // Rails raises NameError (HTTP 500) for an unknown asset class; Spring deliberately degrades to "no exclusion".
        assertThat(autocompleteIds(aliceBearer, "term", "Call", "related", "widgets/" + accountId))
            .containsExactlyInAnyOrderElementsOf(all);
        assertThat(autocompleteIds(aliceBearer, "term", "Call", "related", "accounts/" + accountId + "/extra"))
            .as("Rails destructures split('/') into class and id, ignoring trailing segments")
            .containsExactlyInAnyOrder(aliceOwnTask, bobAssignedToAlice);
        assertThat(autocompleteIds(aliceBearer, "term", "Call", "related", ""))
            .containsExactlyInAnyOrderElementsOf(all);
        assertThat(autocompleteIds(aliceBearer, "term", "Call", "related", "accounts/abc"))
            .containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void autocompleteNeverLeaksTasksOutsideTheCallersScope() throws Exception {
        List<Long> bobVisible = autocompleteIds(bobBearer, "term", "Call");
        assertThat(bobVisible).as("Task.my is not filtered by completion, so the completed delegated task is in scope")
            .containsExactlyInAnyOrder(bobOwnTask, aliceAssignedToBob, aliceAssignedToBobDone);
        assertThat(bobVisible).doesNotContain(aliceOwnTask, aliceAccountTask, bobAssignedToAlice);
        assertThat(autocompleteIds(aliceBearer, "term", "delegated"))
            .as("tasks alice handed to bob are not in alice's Task.my scope")
            .containsExactly(bobAssignedToAlice);
    }

    private List<Long> autocompleteIds(String bearer, String... params) throws Exception {
        List<Long> ids = new ArrayList<>();
        getJson("/api/v1/tasks/autocomplete", bearer, params).get("results")
            .forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    private static List<Long> ids(JsonNode node) {
        List<Long> ids = new ArrayList<>();
        node.findValues("id").forEach(id -> ids.add(id.asLong()));
        return ids;
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

    private void setting(String name, String yaml) {
        jdbcTemplate.update("INSERT INTO settings (name, value, created_at, updated_at) VALUES (?, ?, now(), now())",
            name, yaml);
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

    private long task(String name, User owner, User assignee, String bucket, String assetType, Long assetId,
                      Instant completedAt, User completedBy) {
        return jdbcTemplate.queryForObject("INSERT INTO tasks (name, user_id, assigned_to, bucket, asset_type, "
                + "asset_id, completed_at, completed_by, category, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'call', now(), now()) RETURNING id", Long.class,
            name, owner.getId(), assignee == null ? null : assignee.getId(), bucket, assetType, assetId,
            completedAt == null ? null : Timestamp.from(completedAt),
            completedBy == null ? null : completedBy.getId());
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM settings WHERE name IN ('task_bucket', 'task_completed')");
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
