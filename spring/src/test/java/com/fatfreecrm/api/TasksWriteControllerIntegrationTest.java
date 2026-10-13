package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.json.JsonCompareMode;

/**
 * AB-272 {@code TasksWriteController}: HTTP wiring, Rails {@code TasksController} parity
 * (tracked_by scoping, validation messages, set_due_date buckets), PaperTrail/TaskObserver
 * version rows and the authorization boundary for every write route.
 */
class TasksWriteControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private User bob;
    private User admin;
    private String aliceBearer;
    private String bobBearer;
    private String adminBearer;
    private long accountId;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice", false);
        bob = user("bob", false);
        admin = user("admin", true);
        aliceBearer = bearer(alice);
        bobBearer = bearer(bob);
        adminBearer = bearer(admin);
        accountId = jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, user_id, access, created_at, updated_at)"
                + " VALUES ('Write account', ?, 'Public', now(), now()) RETURNING id",
            Long.class, alice.getId());
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void everyWriteRouteRequiresAuthentication() throws Exception {
        long id = task("Alice call", alice, null, "due_asap", null, null, null);
        mockMvc.perform(post("/api/v1/tasks").contentType(MediaType.APPLICATION_JSON)
            .content(taskBody("name", "x"))).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/tasks/" + id).contentType(MediaType.APPLICATION_JSON)
            .content(taskBody("name", "x"))).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/tasks/" + id)).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/tasks/" + id + "/complete")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/tasks/" + id + "/uncomplete")).andExpect(status().isUnauthorized());
        assertThat(count("tasks")).isEqualTo(1);
        assertThat(count("versions")).isZero();
    }

    @Test
    void createDueTodayPersistsMidnightDueAtAndCreateVersion() throws Exception {
        String body = "{\"task\":{\"user_id\":" + alice.getId()
            + ",\"name\":\"Call the client\",\"bucket\":\"due_today\",\"priority\":\"high\","
            + "\"category\":\"call\",\"background_info\":\"Follow up\"}}";
        List<Instant> utcMidnightToday = new ArrayList<>(List.of(utcMidnight(0)));
        String response = mockMvc.perform(authed(post("/api/v1/tasks"), aliceBearer).content(body))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/api/v1/tasks/")))
            .andExpect(jsonPath("$.name").value("Call the client"))
            .andExpect(jsonPath("$.bucket").value("due_today"))
            .andReturn().getResponse().getContentAsString();
        utcMidnightToday.add(utcMidnight(0));
        long id = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(response).path("id").asLong();

        Map<String, Object> row = taskRow(id);
        assertThat(row.get("name")).isEqualTo("Call the client");
        assertThat(row.get("category")).isEqualTo("call");
        assertThat(row.get("background_info")).isEqualTo("Follow up");
        assertThat(((Timestamp) row.get("due_at")).toInstant()).isIn(utcMidnightToday);
        assertThat(row.get("completed_at")).isNull();

        List<Map<String, Object>> versions = versions("Task", id);
        assertThat(versions).hasSize(1);
        Map<String, Object> version = versions.get(0);
        assertThat(version.get("event")).isEqualTo("create");
        assertThat(version.get("whodunnit")).isEqualTo(alice.getId().toString());
        assertThat(version.get("object")).isNull();
        assertThat(version.get("related_type")).isNull();
        assertThat(version.get("transaction_id")).isNull();
        String changes = (String) version.get("object_changes");
        assertThat(changes).startsWith("---\n");
        assertThat(changes).contains("name:\n- ''\n- Call the client\n");
        assertThat(changes).contains("priority:\n-\n- high\n");
        assertThat(changes).doesNotContain("subscribed_users");
        assertThat(changes).doesNotContain("completed_at");
    }

    @Test
    void createDoesNotForceOwnerToCurrentUserMirroringRails() throws Exception {
        long id = create(aliceBearer, "{\"task\":{\"user_id\":" + bob.getId()
            + ",\"name\":\"Owned by Bob\",\"bucket\":\"due_asap\"}}");
        assertThat(taskRow(id).get("user_id")).isEqualTo(bob.getId().intValue());
        assertThat(taskRow(id).get("due_at")).isNull();
    }

    @Test
    void createWithAssetRecordsRelatedMetaOnTheVersion() throws Exception {
        long id = create(aliceBearer, "{\"task\":{\"user_id\":" + alice.getId()
            + ",\"name\":\"Asset task\",\"bucket\":\"due_today\",\"asset_type\":\"Account\","
            + "\"asset_id\":" + accountId + "}}");
        Map<String, Object> version = versions("Task", id).get(0);
        assertThat(version.get("related_type")).isEqualTo("Account");
        assertThat(version.get("related_id")).isEqualTo((int) accountId);
        assertThat(taskRow(id).get("asset_id")).isEqualTo((int) accountId);
    }

    @Test
    void createBucketsComputeRailsDueDatesInRequestZone() throws Exception {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        for (String bucket : List.of("due_today", "due_tomorrow", "due_this_week", "due_next_week", "due_later",
            "overdue")) {
            LocalDate before = LocalDate.now(tokyo);
            Instant dueAt = dueAt(createIn(tokyo, bucket));
            LocalDate after = LocalDate.now(tokyo);
            assertThat(dueAt).as(bucket)
                .isIn(expectedDueAt(bucket, before, tokyo), expectedDueAt(bucket, after, tokyo));
        }
        assertThat(dueAt(createIn(tokyo, "due_asap"))).isNull();
    }

    /** Rails {@code Task#set_due_date} per bucket, evaluated for a given "today" in the request zone. */
    private static Instant expectedDueAt(String bucket, LocalDate today, ZoneId zone) {
        LocalDateTime midnight = today.atStartOfDay();
        LocalTime endOfDay = LocalTime.of(23, 59, 59, 999_999_000);
        LocalDate endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        LocalDate endOfNextWeek = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        LocalDateTime local = switch (bucket) {
            case "due_today" -> midnight;
            case "due_tomorrow" -> midnight.plusDays(1);
            case "due_this_week" -> LocalDateTime.of(endOfWeek, endOfDay);
            case "due_next_week" -> LocalDateTime.of(endOfNextWeek, endOfDay);
            case "due_later" -> midnight.plusYears(100);
            case "overdue" -> midnight.minusDays(1);
            default -> throw new IllegalArgumentException(bucket);
        };
        return local.atZone(zone).toInstant();
    }

    @Test
    void createSpecificTimeParsesCalendarInProcessLocalZone() throws Exception {
        long id = create(aliceBearer, "{\"task\":{\"user_id\":" + alice.getId()
            + ",\"name\":\"Meeting\",\"bucket\":\"specific_time\",\"calendar\":\"2030-05-04 10:00\"}}");
        assertThat(dueAt(id)).isEqualTo(
            LocalDateTime.of(2030, 5, 4, 10, 0).atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    void createWithBlankNameReturnsRailsErrorsAndWritesNothing() throws Exception {
        mockMvc.perform(authed(post("/api/v1/tasks"), aliceBearer)
                .content("{\"task\":{\"user_id\":" + alice.getId() + ",\"name\":\"\",\"bucket\":\"due_asap\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json("{\"errors\":{\"name\":[\"^Please specify task name.\"]}}",
                JsonCompareMode.STRICT));
        assertThat(count("tasks")).isZero();
        assertThat(count("versions")).isZero();
    }

    @Test
    void createWithoutUserReportsBelongsToAndPresenceMessagesInRailsOrder() throws Exception {
        mockMvc.perform(authed(post("/api/v1/tasks"), aliceBearer)
                .content("{\"task\":{\"name\":\"\",\"bucket\":\"due_asap\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"user\":[\"must exist\",\"can't be blank\"],"
                + "\"name\":[\"^Please specify task name.\"]}}", JsonCompareMode.STRICT));
    }

    @Test
    void createWithUnparseableUserIdCastsToNilLikeActiveRecord() throws Exception {
        mockMvc.perform(authed(post("/api/v1/tasks"), aliceBearer)
                .content("{\"task\":{\"user_id\":\"abc\",\"name\":\"Cast\",\"bucket\":\"due_asap\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.user[0]").value("must exist"));
        assertThat(count("tasks")).isZero();
    }

    @Test
    void createSpecificTimeWithInvalidCalendarReturnsInvalidDate() throws Exception {
        mockMvc.perform(authed(post("/api/v1/tasks"), aliceBearer)
                .content("{\"task\":{\"user_id\":" + alice.getId()
                    + ",\"name\":\"Bad date\",\"bucket\":\"specific_time\",\"calendar\":\"not a date\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"calendar\":[\"^Please specify valid date.\"]}}",
                JsonCompareMode.STRICT));
        assertThat(count("tasks")).isZero();
    }

    @Test
    void createSpecificTimeWithBlankCalendarReportsBlankAndInvalidDate() throws Exception {
        mockMvc.perform(authed(post("/api/v1/tasks"), aliceBearer)
                .content("{\"task\":{\"user_id\":" + alice.getId()
                    + ",\"name\":\"Blank date\",\"bucket\":\"specific_time\",\"calendar\":\"\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"calendar\":[\"can't be blank\","
                + "\"^Please specify valid date.\"]}}", JsonCompareMode.STRICT));
    }

    @Test
    void createSpecificTimeWithoutCalendarMirrorsRailsTypeErrorAsOpaque500() throws Exception {
        mockMvc.perform(authed(post("/api/v1/tasks"), aliceBearer)
                .content("{\"task\":{\"user_id\":" + alice.getId()
                    + ",\"name\":\"No calendar\",\"bucket\":\"specific_time\"}}"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("implicit conversion"))));
        assertThat(count("tasks")).isZero();
    }

    @Test
    void createWithNullTaskBodyFailsValidationInsteadOfCrashing() throws Exception {
        mockMvc.perform(authed(post("/api/v1/tasks"), aliceBearer).content("{\"task\":null}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.user").isArray())
            .andExpect(jsonPath("$.errors.name[0]").value("^Please specify task name."));
    }

    @Test
    void updateAssignsOnlyProvidedKeysAndRecordsUpdateThenRescheduleVersions() throws Exception {
        long id = task("Alice call", alice, null, "due_asap", null, null, null);
        Instant tomorrowBefore = utcMidnight(1);
        mockMvc.perform(authed(put("/api/v1/tasks/" + id), aliceBearer)
                .content("{\"task\":{\"bucket\":\"due_tomorrow\"}}"))
            .andExpect(status().isNoContent());

        Map<String, Object> row = taskRow(id);
        assertThat(row.get("name")).isEqualTo("Alice call");
        assertThat(row.get("category")).isEqualTo("call");
        assertThat(row.get("bucket")).isEqualTo("due_tomorrow");
        assertThat(((Timestamp) row.get("due_at")).toInstant()).isIn(tomorrowBefore, utcMidnight(1));

        List<Map<String, Object>> versions = versions("Task", id);
        assertThat(versions).extracting(version -> version.get("event"))
            .containsExactly("update", "reschedule");
        Map<String, Object> update = versions.get(0);
        assertThat((String) update.get("object")).startsWith("---\nbucket: due_asap\nid: " + id + "\n");
        assertThat((String) update.get("object_changes")).contains("bucket:\n- due_asap\n- due_tomorrow\n");
        assertThat((String) update.get("object_changes")).doesNotContain("\nname:\n");
        Map<String, Object> reschedule = versions.get(1);
        assertThat(reschedule.get("object")).isNull();
        assertThat(reschedule.get("object_changes")).isNull();
        assertThat(reschedule.get("whodunnit")).isEqualTo(alice.getId().toString());
    }

    @Test
    void updateAssignedToRecordsReassignEventInsteadOfReschedule() throws Exception {
        long id = task("Alice call", alice, null, "due_asap", null, null, null);
        mockMvc.perform(authed(put("/api/v1/tasks/" + id), aliceBearer)
                .content("{\"task\":{\"assigned_to\":" + bob.getId() + ",\"bucket\":\"due_today\"}}"))
            .andExpect(status().isNoContent());
        assertThat(taskRow(id).get("assigned_to")).isEqualTo(bob.getId().intValue());
        assertThat(versions("Task", id)).extracting(version -> version.get("event"))
            .containsExactly("update", "reassign");
    }

    @Test
    void updateWithNoEffectiveChangeWritesNoVersion() throws Exception {
        long id = task("Alice call", alice, null, "due_asap", null, null, null);
        mockMvc.perform(authed(put("/api/v1/tasks/" + id), aliceBearer)
                .content("{\"task\":{\"name\":\"Alice call\"}}"))
            .andExpect(status().isNoContent());
        assertThat(versions("Task", id)).isEmpty();
    }

    @Test
    void updateByAssigneeIsAllowedThroughTrackedByScope() throws Exception {
        long id = task("Bob delegates", bob, alice, "due_asap", null, null, null);
        mockMvc.perform(authed(put("/api/v1/tasks/" + id), aliceBearer)
                .content("{\"task\":{\"name\":\"Renamed by assignee\"}}"))
            .andExpect(status().isNoContent());
        assertThat(taskRow(id).get("name")).isEqualTo("Renamed by assignee");
    }

    @Test
    void updateDestroyAndCompletionByUnrelatedUserAreForbiddenAndLeaveRowsUntouched() throws Exception {
        long id = task("Alice call", alice, null, "due_asap", null, null, null);
        mockMvc.perform(authed(put("/api/v1/tasks/" + id), bobBearer)
                .content("{\"task\":{\"name\":\"Hijacked\"}}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(authed(delete("/api/v1/tasks/" + id), bobBearer)).andExpect(status().isForbidden());
        mockMvc.perform(authed(put("/api/v1/tasks/" + id + "/complete"), bobBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(authed(put("/api/v1/tasks/" + id + "/uncomplete"), bobBearer))
            .andExpect(status().isForbidden());
        Map<String, Object> row = taskRow(id);
        assertThat(row.get("name")).isEqualTo("Alice call");
        assertThat(row.get("completed_at")).isNull();
        assertThat(count("versions")).isZero();
    }

    @Test
    void adminOutsideTrackedByScopeGetsNotFoundLikeRails() throws Exception {
        long id = task("Alice call", alice, null, "due_asap", null, null, null);
        mockMvc.perform(authed(put("/api/v1/tasks/" + id), adminBearer)
                .content("{\"task\":{\"name\":\"Admin edit\"}}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(authed(delete("/api/v1/tasks/" + id), adminBearer)).andExpect(status().isNotFound());
        assertThat(taskRow(id).get("name")).isEqualTo("Alice call");
    }

    @Test
    void writesToMissingTaskReturnNotFound() throws Exception {
        mockMvc.perform(authed(put("/api/v1/tasks/424242"), aliceBearer)
                .content("{\"task\":{\"name\":\"x\"}}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(authed(delete("/api/v1/tasks/424242"), aliceBearer)).andExpect(status().isNotFound());
        mockMvc.perform(authed(put("/api/v1/tasks/424242/complete"), aliceBearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void updateToBlankNameIsRejectedWithRailsMessageAndRollsBack() throws Exception {
        long id = task("Alice call", alice, null, "due_asap", null, null, null);
        mockMvc.perform(authed(put("/api/v1/tasks/" + id), aliceBearer)
                .content("{\"task\":{\"name\":\"\",\"bucket\":\"due_today\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"name\":[\"^Please specify task name.\"]}}",
                JsonCompareMode.STRICT));
        Map<String, Object> row = taskRow(id);
        assertThat(row.get("name")).isEqualTo("Alice call");
        assertThat(row.get("bucket")).isEqualTo("due_asap");
        assertThat(versions("Task", id)).isEmpty();
    }

    @Test
    void completeStampsCompletionAndRecordsUpdateThenCompleteVersions() throws Exception {
        Instant dueAt = Instant.parse("2020-01-01T00:00:00Z");
        long id = task("Alice call", alice, null, "overdue", dueAt, null, null);
        mockMvc.perform(authed(put("/api/v1/tasks/" + id + "/complete"), aliceBearer))
            .andExpect(status().isNoContent());

        Map<String, Object> row = taskRow(id);
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("completed_by")).isEqualTo(alice.getId().intValue());
        assertThat(((Timestamp) row.get("due_at")).toInstant()).isEqualTo(dueAt);

        List<Map<String, Object>> versions = versions("Task", id);
        assertThat(versions).extracting(version -> version.get("event")).containsExactly("update", "complete");
        String object = (String) versions.get(0).get("object");
        assertThat(object).startsWith("---\ncompleted_at:\ncompleted_by:\nid: " + id + "\n");
        assertThat((String) versions.get(0).get("object_changes"))
            .contains("completed_by:\n-\n- " + alice.getId() + "\n");
    }

    @Test
    void completeOnAlreadyCompletedTaskRecordsNoCompleteEvent() throws Exception {
        long id = task("Done", alice, null, "due_asap", null, Instant.parse("2024-01-01T00:00:00Z"), bob);
        mockMvc.perform(authed(put("/api/v1/tasks/" + id + "/complete"), aliceBearer))
            .andExpect(status().isNoContent());
        assertThat(taskRow(id).get("completed_by")).isEqualTo(alice.getId().intValue());
        assertThat(versions("Task", id)).extracting(version -> version.get("event")).containsExactly("update");
    }

    @Test
    void uncompleteClearsCompletionAndRecomputesDueAtFromBucket() throws Exception {
        long id = task("Done", alice, null, "due_today", Instant.parse("2020-01-01T00:00:00Z"),
            Instant.parse("2024-01-01T00:00:00Z"), alice);
        Instant todayBefore = utcMidnight(0);
        mockMvc.perform(authed(put("/api/v1/tasks/" + id + "/uncomplete"), aliceBearer))
            .andExpect(status().isNoContent());

        Map<String, Object> row = taskRow(id);
        assertThat(row.get("completed_at")).isNull();
        assertThat(row.get("completed_by")).isNull();
        assertThat(((Timestamp) row.get("due_at")).toInstant()).isIn(todayBefore, utcMidnight(0));
        assertThat(versions("Task", id)).extracting(version -> version.get("event")).containsExactly("update");
    }

    @Test
    void destroyDeletesRowAndRecordsDestroyVersionWithObjectDump() throws Exception {
        long id = task("Alice call", alice, null, "due_asap", null, null, null);
        mockMvc.perform(authed(delete("/api/v1/tasks/" + id), aliceBearer)).andExpect(status().isNoContent());

        assertThat(count("tasks")).isZero();
        List<Map<String, Object>> versions = versions("Task", id);
        assertThat(versions).hasSize(1);
        Map<String, Object> destroy = versions.get(0);
        assertThat(destroy.get("event")).isEqualTo("destroy");
        assertThat((String) destroy.get("object")).startsWith("---\nid: " + id + "\nuser_id: " + alice.getId() + "\n");
        assertThat((String) destroy.get("object")).contains("name: Alice call\n");
        assertThat((String) destroy.get("object_changes")).contains("name:\n- Alice call\n-\n");
        assertThat((String) destroy.get("object_changes")).doesNotContain("subscribed_users");
    }

    private long createIn(ZoneId zone, String bucket) throws Exception {
        String body = "{\"task\":{\"user_id\":" + alice.getId() + ",\"name\":\"" + bucket
            + "\",\"bucket\":\"" + bucket + "\"}}";
        String response = mockMvc.perform(authed(post("/api/v1/tasks"), aliceBearer)
                .param("timeZone", zone.getId()).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(response).path("id").asLong();
    }

    private long create(String bearer, String body) throws Exception {
        String response = mockMvc.perform(authed(post("/api/v1/tasks"), bearer).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(response).path("id").asLong();
    }

    private static Instant utcMidnight(int plusDays) {
        return LocalDate.now(ZoneOffset.UTC).plusDays(plusDays).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant dueAt(long id) {
        Timestamp dueAt = (Timestamp) taskRow(id).get("due_at");
        return dueAt == null ? null : dueAt.toInstant();
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String bearer) {
        return builder.header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON);
    }

    private static String taskBody(String key, String value) {
        return "{\"task\":{\"" + key + "\":\"" + value + "\"}}";
    }

    private Map<String, Object> taskRow(long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM tasks WHERE id = ?", id);
    }

    private List<Map<String, Object>> versions(String itemType, long itemId) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM versions WHERE item_type = ? AND item_id = ? ORDER BY id", itemType, itemId);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private User user(String username, boolean admin) {
        User user = new User();
        user.setUsername(username);
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(admin);
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
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
