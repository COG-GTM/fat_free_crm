package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Rails {@code TasksController} write parity through the HTTP layer: validation messages and
 * order, {@code set_due_date} buckets, {@code tracked_by} scoping after {@code hasPermission},
 * PaperTrail/TaskObserver version rows, and that failed writes leave no rows behind.
 */
class TasksWriteControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Value("${ffcrm.time-zone:UTC}")
    private String defaultZone;

    private User alice;
    private User bob;
    private String aliceBearer;
    private String bobBearer;
    private long aliceTask;
    private long bobTaskAssignedToAlice;
    private long bobTaskClosedByAlice;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice");
        bob = user("bob");
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
        bobBearer = "Bearer " + jwtTokenService.issue(bob).accessToken();
        aliceTask = task("Alice call", alice, null, "due_asap", null, null, null);
        bobTaskAssignedToAlice = task("Bob delegates", bob, alice, "due_asap", null, null, null);
        bobTaskClosedByAlice = task("Bob closed by Alice", bob, null, "due_asap", null, Instant.now(), alice);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void writesRequireAuthentication() throws Exception {
        mockMvc.perform(json(post("/api/v1/tasks"), taskBody("name", "x"))).andExpect(status().isUnauthorized());
        mockMvc.perform(json(put("/api/v1/tasks/" + aliceTask), taskBody("name", "x")))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/tasks/" + aliceTask)).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/tasks/" + aliceTask + "/complete")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/tasks/" + aliceTask + "/uncomplete")).andExpect(status().isUnauthorized());
        assertThat(count("tasks")).isEqualTo(3);
        assertThat(count("versions")).isZero();
    }

    @Test
    void createPersistsTaskComputesDueAtFromBucketAndRecordsCreateVersion() throws Exception {
        MvcResult result = mockMvc.perform(json(post("/api/v1/tasks"), taskBody(
                "name", "Call Alice", "bucket", "due_today", "category", "call", "user_id", alice.getId(),
                "background_info", "ring twice")).header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Call Alice"))
            .andExpect(jsonPath("$.bucket").value("due_today"))
            .andReturn();
        long id = JSON.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/v1/tasks/" + id);

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM tasks WHERE id = ?", id);
        ZoneId zone = ZoneId.of(defaultZone);
        assertThat(((Timestamp) row.get("due_at")).toInstant())
            .isEqualTo(LocalDate.now(zone).atStartOfDay(zone).toInstant());
        assertThat(row).containsEntry("user_id", alice.getId().intValue())
            .containsEntry("category", "call")
            .containsEntry("background_info", "ring twice")
            .containsEntry("completed_at", null);
        assertThat(row.get("created_at")).isNotNull();

        List<Map<String, Object>> versions = versionsOf("Task", id);
        assertThat(versions).hasSize(1);
        Map<String, Object> version = versions.get(0);
        assertThat(version).containsEntry("event", "create")
            .containsEntry("whodunnit", alice.getId().toString())
            .containsEntry("object", null)
            .containsEntry("related_type", null)
            .containsEntry("transaction_id", null);
        String changes = (String) version.get("object_changes");
        assertThat(changes).contains("name:\n- ''\n- Call Alice\n")
            .contains("bucket:\n-\n- due_today\n")
            .contains("user_id:\n-\n- " + alice.getId() + "\n")
            .doesNotContain("subscribed_users").doesNotContain("assigned_to");
    }

    @Test
    void createHonoursRequestTimeZoneForBucketBoundaries() throws Exception {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        long id = create(taskBody("name", "Tokyo", "bucket", "due_tomorrow", "user_id", alice.getId()),
            "timeZone", "Asia/Tokyo");

        assertThat(dueAt(id)).isEqualTo(LocalDate.now(tokyo).plusDays(1).atStartOfDay(tokyo).toInstant());
        mockMvc.perform(json(post("/api/v1/tasks").param("timeZone", "Mars/Olympus"),
                taskBody("name", "x", "user_id", alice.getId())).header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createDoesNotForceOwnerToCurrentUserMirroringRailsTaskParams() throws Exception {
        long id = create(taskBody("name", "For Bob", "bucket", "due_asap", "user_id", bob.getId()));

        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM tasks WHERE id = ?", Long.class, id))
            .isEqualTo(bob.getId());
        assertThat(dueAt(id)).isNull();
    }

    @Test
    void blankNameIsRejectedWithRailsCaretMessageAndNothingIsWritten() throws Exception {
        mockMvc.perform(json(post("/api/v1/tasks"), taskBody("name", "", "bucket", "due_today",
                "user_id", alice.getId())).header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json(
                "{\"errors\":{\"name\":[\"^Please specify task name.\"]}}",
                JsonCompareMode.STRICT));

        assertThat(count("tasks")).isEqualTo(3);
        assertThat(count("versions")).isZero();
    }

    @Test
    void missingUserReportsBelongsToThenPresenceMessagesInRailsOrder() throws Exception {
        mockMvc.perform(json(post("/api/v1/tasks"), taskBody("name", "Orphan", "bucket", "due_today"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json(
                "{\"errors\":{\"user\":[\"must exist\",\"can't be blank\"]}}",
                JsonCompareMode.STRICT));

        mockMvc.perform(json(post("/api/v1/tasks"), taskBody("name", "", "user_id", "abc"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"user\":[\"must exist\",\"can't be blank\"],"
                + "\"name\":[\"^Please specify task name.\"]}}", JsonCompareMode.STRICT));
        assertThat(count("tasks")).isEqualTo(3);
    }

    @Test
    void specificTimeValidatesCalendarLikeRails() throws Exception {
        mockMvc.perform(json(post("/api/v1/tasks"), taskBody("name", "Meet", "bucket", "specific_time",
                "user_id", alice.getId())).header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mockMvc.perform(json(post("/api/v1/tasks"), taskBody("name", "Meet", "bucket", "specific_time",
                "user_id", alice.getId(), "calendar", "")).header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json(
                "{\"errors\":{\"calendar\":[\"can't be blank\",\"^Please specify valid date.\"]}}",
                JsonCompareMode.STRICT));

        mockMvc.perform(json(post("/api/v1/tasks"), taskBody("name", "Meet", "bucket", "specific_time",
                "user_id", alice.getId(), "calendar", "not a date")).header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json(
                "{\"errors\":{\"calendar\":[\"^Please specify valid date.\"]}}",
                JsonCompareMode.STRICT));
        assertThat(count("tasks")).isEqualTo(3);
        assertThat(count("versions")).isZero();

        long id = create(taskBody("name", "Meet", "bucket", "specific_time", "user_id", alice.getId(),
            "calendar", "2030-05-04 10:30"));
        assertThat(dueAt(id))
            .isEqualTo(LocalDateTime.of(2030, 5, 4, 10, 30).atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    void malformedBodyIsBadRequestWhileUnknownTopLevelFieldsAreIgnoredLikeUnpermittedParams() throws Exception {
        mockMvc.perform(post("/api/v1/tasks").contentType(MediaType.APPLICATION_JSON).content("{not json")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isBadRequest());
        assertThat(count("tasks")).isEqualTo(3);

        mockMvc.perform(post("/api/v1/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"task\":{\"name\":\"Ignored extras\",\"user_id\":" + alice.getId() + "},"
                    + "\"unexpected\":1,\"view\":\"pending\"}")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Ignored extras"));
        assertThat(count("tasks")).isEqualTo(4);
    }

    @Test
    void updateIsPartialAndRecordsAssignedAttributesFirstInTheObjectDump() throws Exception {
        mockMvc.perform(json(put("/api/v1/tasks/" + aliceTask), taskBody("name", "Renamed"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNoContent());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM tasks WHERE id = ?", aliceTask);
        assertThat(row).containsEntry("name", "Renamed").containsEntry("category", "call")
            .containsEntry("bucket", "due_asap");

        List<Map<String, Object>> versions = versionsOf("Task", aliceTask);
        assertThat(versions).extracting(version -> version.get("event")).containsExactly("update");
        assertThat((String) versions.get(0).get("object")).startsWith("---\nname: Alice call\nid: " + aliceTask + "\n");
        assertThat((String) versions.get(0).get("object_changes")).contains("name:\n- Alice call\n- Renamed\n");
        assertThat(versions.get(0)).containsEntry("whodunnit", alice.getId().toString());
    }

    @Test
    void updateIsDeniedOutsidePermissionAndNotFoundOutsideTrackedBy() throws Exception {
        mockMvc.perform(json(put("/api/v1/tasks/" + aliceTask), taskBody("name", "Hijack"))
                .header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(json(put("/api/v1/tasks/" + bobTaskClosedByAlice), taskBody("name", "Hijack"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(json(put("/api/v1/tasks/999999"), taskBody("name", "Hijack"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(json(put("/api/v1/tasks/" + bobTaskAssignedToAlice), taskBody("name", "Assignee edit"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForList("SELECT name FROM tasks ORDER BY id", String.class))
            .containsExactly("Alice call", "Assignee edit", "Bob closed by Alice");
        assertThat(versionsOf("Task", aliceTask)).isEmpty();
        assertThat(versionsOf("Task", bobTaskClosedByAlice)).isEmpty();
    }

    @Test
    void updateValidationFailureRollsBackAndWritesNoVersion() throws Exception {
        mockMvc.perform(json(put("/api/v1/tasks/" + aliceTask), taskBody("name", "", "bucket", "due_today"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json(
                "{\"errors\":{\"name\":[\"^Please specify task name.\"]}}",
                JsonCompareMode.STRICT));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM tasks WHERE id = ?", aliceTask);
        assertThat(row).containsEntry("name", "Alice call").containsEntry("bucket", "due_asap");
        assertThat(count("versions")).isZero();
    }

    @Test
    void bucketChangeReschedulesAndAssigneeChangeReassigns() throws Exception {
        mockMvc.perform(json(put("/api/v1/tasks/" + aliceTask), taskBody("bucket", "due_tomorrow"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNoContent());
        ZoneId zone = ZoneId.of(defaultZone);
        assertThat(dueAt(aliceTask)).isEqualTo(LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant());
        assertThat(versionsOf("Task", aliceTask)).extracting(version -> version.get("event"))
            .containsExactly("update", "reschedule");

        mockMvc.perform(json(put("/api/v1/tasks/" + aliceTask), taskBody("assigned_to", bob.getId(),
                "bucket", "due_later")).header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNoContent());
        assertThat(versionsOf("Task", aliceTask)).extracting(version -> version.get("event"))
            .containsExactly("update", "reschedule", "update", "reassign");
        List<Map<String, Object>> versions = versionsOf("Task", aliceTask);
        Map<String, Object> reassign = versions.get(3);
        assertThat(reassign).containsEntry("object", null).containsEntry("object_changes", null)
            .containsEntry("related_type", null).containsEntry("whodunnit", alice.getId().toString());
        assertThat(jdbcTemplate.queryForObject("SELECT assigned_to FROM tasks WHERE id = ?", Long.class, aliceTask))
            .isEqualTo(bob.getId());
    }

    @Test
    void completeAndUncompleteRoundTripWithObserverVersions() throws Exception {
        mockMvc.perform(put("/api/v1/tasks/" + aliceTask + "/complete").header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/tasks/" + bobTaskClosedByAlice + "/uncomplete")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/tasks/" + aliceTask + "/complete").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNoContent());
        Map<String, Object> completed = jdbcTemplate.queryForMap("SELECT * FROM tasks WHERE id = ?", aliceTask);
        assertThat(completed.get("completed_at")).isNotNull();
        assertThat(completed).containsEntry("completed_by", alice.getId().intValue());
        assertThat(versionsOf("Task", aliceTask)).extracting(version -> version.get("event"))
            .containsExactly("update", "complete");
        assertThat((String) versionsOf("Task", aliceTask).get(0).get("object"))
            .startsWith("---\ncompleted_at:\ncompleted_by:\nid: " + aliceTask + "\n");

        // before_update :set_due_date is skipped while completed.
        mockMvc.perform(json(put("/api/v1/tasks/" + aliceTask), taskBody("bucket", "due_tomorrow"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNoContent());
        assertThat(dueAt(aliceTask)).isNull();

        mockMvc.perform(put("/api/v1/tasks/" + aliceTask + "/uncomplete")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNoContent());
        Map<String, Object> reopened = jdbcTemplate.queryForMap("SELECT * FROM tasks WHERE id = ?", aliceTask);
        assertThat(reopened).containsEntry("completed_at", null).containsEntry("completed_by", null);
        ZoneId zone = ZoneId.of(defaultZone);
        assertThat(dueAt(aliceTask)).isEqualTo(LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant());
        assertThat(versionsOf("Task", aliceTask)).extracting(version -> version.get("event"))
            .containsExactly("update", "complete", "update", "reschedule", "update");
    }

    @Test
    void destroyRemovesRowAndRecordsDestroyVersionOnlyForTrackedTasks() throws Exception {
        mockMvc.perform(delete("/api/v1/tasks/" + aliceTask).header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/tasks/" + bobTaskClosedByAlice).header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/tasks/999999").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        assertThat(count("tasks")).isEqualTo(3);
        assertThat(count("versions")).isZero();

        mockMvc.perform(delete("/api/v1/tasks/" + aliceTask).header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tasks WHERE id = ?", Long.class, aliceTask))
            .isZero();
        List<Map<String, Object>> versions = versionsOf("Task", aliceTask);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0)).containsEntry("event", "destroy")
            .containsEntry("whodunnit", alice.getId().toString());
        assertThat((String) versions.get(0).get("object")).startsWith("---\nid: " + aliceTask + "\nuser_id: "
            + alice.getId() + "\nassigned_to:\ncompleted_by:\nname: Alice call\n");
        assertThat((String) versions.get(0).get("object_changes")).contains("name:\n- Alice call\n-\n")
            .doesNotContain("subscribed_users");
    }

    private long create(String body, String... params) throws Exception {
        MockHttpServletRequestBuilder request = json(post("/api/v1/tasks"), body)
            .header(HttpHeaders.AUTHORIZATION, aliceBearer);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isCreated()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String taskBody(Object... keyValues) throws Exception {
        var task = JSON.createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            task.set((String) keyValues[i], JSON.valueToTree(keyValues[i + 1]));
        }
        JsonNode body = JSON.createObjectNode().set("task", task);
        return JSON.writeValueAsString(body);
    }

    private Instant dueAt(long id) {
        Timestamp dueAt = jdbcTemplate.queryForObject("SELECT due_at FROM tasks WHERE id = ?", Timestamp.class, id);
        return dueAt == null ? null : dueAt.toInstant();
    }

    private List<Map<String, Object>> versionsOf(String itemType, long itemId) {
        return jdbcTemplate.queryForList("SELECT event, whodunnit, object, object_changes, related_type, "
            + "related_id, transaction_id FROM versions WHERE item_type = ? AND item_id = ? ORDER BY id",
            itemType, itemId);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
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

    private long task(String name, User owner, User assignee, String bucket, Instant dueAt, Instant completedAt,
                      User completedBy) {
        return jdbcTemplate.queryForObject("INSERT INTO tasks (name, user_id, assigned_to, bucket, due_at, "
                + "completed_at, completed_by, category, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'call', now(), now()) RETURNING id", Long.class,
            name, owner.getId(), assignee == null ? null : assignee.getId(), bucket, timestamp(dueAt),
            timestamp(completedAt), completedBy == null ? null : completedBy.getId());
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM users");
    }
}
