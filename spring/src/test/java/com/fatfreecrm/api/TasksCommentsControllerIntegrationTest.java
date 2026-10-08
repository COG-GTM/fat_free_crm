package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.service.query.CrmQueryService;
import com.fatfreecrm.service.query.ListQuery;
import com.fatfreecrm.service.query.ListResult;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;

class TasksCommentsControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private CrmQueryService crmQueryService;

    private User alice;
    private User bob;
    private String aliceBearer;
    private String bobBearer;
    private long aliceTask;
    private long bobTaskClosedByAlice;
    private long boundaryTask;
    private Instant boundaryDueAt;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice");
        bob = user("bob");
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
        bobBearer = "Bearer " + jwtTokenService.issue(bob).accessToken();
        aliceTask = task("Alice call", alice, null, "due_asap", null, null, null);
        bobTaskClosedByAlice = task("Bob closed by Alice", bob, null, "due_asap", null, Instant.now(), alice);
        boundaryDueAt = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(60);
        boundaryTask = task("Boundary", alice, null, "overdue", boundaryDueAt, null, null);
        jdbcTemplate.update("INSERT INTO comments (user_id, commentable_id, commentable_type, comment, created_at, "
            + "updated_at) VALUES (?, ?, 'Task', 'older', now() - interval '1 hour', now()), "
            + "(?, ?, 'Task', 'newer', now(), now())", bob.getId(), aliceTask, alice.getId(), aliceTask);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/tasks")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/tasks/" + aliceTask)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/tasks/autocomplete")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/comments").param("task_id", String.valueOf(aliceTask)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void groupsPendingTasksIntoRailsBucketsInOrder() throws Exception {
        JsonNode body = getJson("/api/v1/tasks", aliceBearer, "view", "pending");
        assertThat(fieldNames(body.get("buckets"))).containsExactly(
            "overdue", "due_asap", "due_today", "due_tomorrow", "due_this_week", "due_next_week", "due_later");
        assertThat(body.at("/buckets/due_asap/0/id").asLong()).isEqualTo(aliceTask);
        assertThat(body.at("/buckets/overdue/0/id").asLong()).isEqualTo(boundaryTask);

        JsonNode completed = getJson("/api/v1/tasks", aliceBearer, "view", "completed");
        assertThat(fieldNames(completed.get("buckets"))).containsExactly("completed_today", "completed_yesterday",
            "completed_last_week", "completed_this_month", "completed_last_month");
    }

    @Test
    void bucketBoundariesFollowTheRequestedTimeZone() throws Exception {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        LocalDate due = boundaryDueAt.atZone(tokyo).toLocalDate();
        String expected = due.equals(LocalDate.now(tokyo)) ? "due_today" : "overdue";
        JsonNode body = getJson("/api/v1/tasks", aliceBearer, "timeZone", "Asia/Tokyo");
        assertThat(body.at("/buckets/" + expected + "/0/id").asLong()).isEqualTo(boundaryTask);

        mockMvc.perform(get("/api/v1/tasks").param("timeZone", "Mars/Olympus")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isBadRequest());
    }

    @Test
    void showFollowsTrackedByWithPolicyDenialsAsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/" + aliceTask).header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(aliceTask))
            .andExpect(jsonPath("$.subscribed_users").isArray());
        mockMvc.perform(get("/api/v1/tasks/" + aliceTask).header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/tasks/" + bobTaskClosedByAlice).header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/tasks/999999").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM versions WHERE event = 'view'", Long.class))
            .isZero();
    }

    @Test
    void taskTextSearchBindsApostrophesInsteadOfInliningThem() throws Exception {
        long obrien = task("Call O'Brien", alice, null, "due_asap", null, null, null);
        task("Call Smith", alice, null, "due_asap", null, null, null);

        JsonNode body = getJson("/api/v1/tasks/autocomplete", aliceBearer, "term", "O'Brien");
        assertThat(body.at("/results").findValuesAsText("text")).containsExactly("Call O'Brien");
        assertThat(body.at("/results/0/id").asLong()).isEqualTo(obrien);

        JsonNode injection = getJson("/api/v1/tasks/autocomplete", aliceBearer, "term", "x' OR '1'='1");
        assertThat(injection.at("/results")).isEmpty();

        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("query", "O'Brien");
        ListResult<Task> result = crmQueryService.list(
            new AuthenticatedUser(alice.getId(), alice.getUsername(), false), Task.class,
            ListQuery.fromParameters(params));
        assertThat(result.items()).extracting(Task::getId).containsExactly(obrien);
    }

    @Test
    void listsCommentsOfAVisibleCommentableNewestFirst() throws Exception {
        JsonNode body = getJson("/api/v1/comments", aliceBearer, "task_id", String.valueOf(aliceTask));
        assertThat(body.isArray()).isTrue();
        assertThat(body.findValuesAsText("comment")).containsExactly("newer", "older");

        mockMvc.perform(get("/api/v1/comments").param("task_id", String.valueOf(aliceTask))
                .header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/comments").param("task_id", "abc")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/comments").param("foo_id", "1")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listsCommentsOnAUserRecordLikeRailsUserMy() throws Exception {
        jdbcTemplate.update("INSERT INTO comments (user_id, commentable_id, commentable_type, comment, created_at, "
            + "updated_at) VALUES (?, ?, 'User', 'about alice', now(), now())", bob.getId(), alice.getId());
        JsonNode own = getJson("/api/v1/comments", aliceBearer, "user_id", String.valueOf(alice.getId()));
        assertThat(own.findValuesAsText("comment")).containsExactly("about alice");

        mockMvc.perform(get("/api/v1/comments").param("user_id", String.valueOf(alice.getId()))
                .header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void listsOwnCommentsWithoutACommentable() throws Exception {
        JsonNode body = getJson("/api/v1/comments", bobBearer);
        assertThat(body.findValuesAsText("comment")).containsExactly("older");
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
