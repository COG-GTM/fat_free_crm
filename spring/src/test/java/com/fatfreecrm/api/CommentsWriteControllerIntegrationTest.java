package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * AB-272 {@code CommentsWriteController}: Rails {@code CommentsController} parity — forced
 * {@code user_id}, {@code my(current_user)} commentable scoping (Public/Private/Shared and
 * {@code Task.my}), mention + author subscriptions, PaperTrail rows, and CanCan owner-or-admin
 * on update/destroy.
 */
class CommentsWriteControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    private long alicePublicAccount;
    private long bobPrivateAccount;
    private long bobSharedAccount;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice", false);
        bob = user("bob", false);
        admin = user("admin", true);
        aliceBearer = bearer(alice);
        bobBearer = bearer(bob);
        adminBearer = bearer(admin);
        alicePublicAccount = account("Alice public", alice, "Public");
        bobPrivateAccount = account("Bob private", bob, "Private");
        bobSharedAccount = account("Bob shared", bob, "Shared");
        jdbcTemplate.update("INSERT INTO permissions (asset_type, asset_id, user_id, created_at, updated_at)"
            + " VALUES ('Account', ?, ?, now(), now())", bobSharedAccount, alice.getId());
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void everyWriteRouteRequiresAuthentication() throws Exception {
        long id = comment(alice, "Account", alicePublicAccount, "hello");
        mockMvc.perform(post("/api/v1/comments").contentType(MediaType.APPLICATION_JSON)
            .content(body("Account", alicePublicAccount, "x"))).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/comments/" + id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"comment\":{\"comment\":\"x\"}}")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/comments/" + id)).andExpect(status().isUnauthorized());
        assertThat(count("comments")).isEqualTo(1);
        assertThat(count("versions")).isZero();
    }

    @Test
    void createForcesAuthorSubscribesAuthorAndRecordsCreateVersionWithCommentableMeta() throws Exception {
        Timestamp before = (Timestamp) accountRow(alicePublicAccount).get("updated_at");
        String response = mockMvc.perform(authed(post("/api/v1/comments"), aliceBearer)
                .content("{\"comment\":{\"user_id\":" + bob.getId() + ",\"commentable_type\":\"Account\","
                    + "\"commentable_id\":" + alicePublicAccount + ",\"comment\":\"Note\",\"private\":\"1\"}}"))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/api/v1/comments/")))
            .andExpect(jsonPath("$.comment").value("Note"))
            .andExpect(jsonPath("$.commentable_type").value("Account"))
            .andExpect(jsonPath("$.user_id").value(alice.getId().intValue()))
            .andReturn().getResponse().getContentAsString();
        long id = JSON.readTree(response).path("id").asLong();

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM comments WHERE id = ?", id);
        assertThat(row.get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(row.get("private")).isEqualTo(true);
        assertThat(row.get("state")).isEqualTo("Expanded");

        assertThat(subscribedUsers(alicePublicAccount)).containsExactly(alice.getId());
        Timestamp after = (Timestamp) accountRow(alicePublicAccount).get("updated_at");
        assertThat(after.toInstant()).isAfter(before.toInstant());

        List<Map<String, Object>> versions = allVersions();
        assertThat(versions).hasSize(1);
        Map<String, Object> version = versions.get(0);
        assertThat(version.get("item_type")).isEqualTo("Comment");
        assertThat(version.get("item_id")).isEqualTo((int) id);
        assertThat(version.get("event")).isEqualTo("create");
        assertThat(version.get("related_type")).isEqualTo("Account");
        assertThat(version.get("related_id")).isEqualTo((int) alicePublicAccount);
        assertThat(version.get("whodunnit")).isEqualTo(alice.getId().toString());
        assertThat(version.get("object")).isNull();
        String changes = (String) version.get("object_changes");
        assertThat(changes).contains("comment:\n-\n- Note\n");
        assertThat(changes).contains("user_id:\n-\n- " + alice.getId() + "\n");
        assertThat(changes).doesNotContain("state:");
    }

    @Test
    void createWithMentionSubscribesMentionedUserBeforeAuthorAndIgnoresUnknownNames() throws Exception {
        mockMvc.perform(authed(post("/api/v1/comments"), aliceBearer)
                .content(body("Account", alicePublicAccount, "ping @bob and @nobody-here")))
            .andExpect(status().isCreated());
        assertThat(subscribedUsers(alicePublicAccount)).containsExactly(bob.getId(), alice.getId());
    }

    @Test
    void createOnPrivateAccountOfAnotherUserIsNotFoundAndWritesNothing() throws Exception {
        mockMvc.perform(authed(post("/api/v1/comments"), aliceBearer)
                .content(body("Account", bobPrivateAccount, "sneaky")))
            .andExpect(status().isNotFound());
        assertThat(count("comments")).isZero();
        assertThat(count("versions")).isZero();
        assertThat(accountRow(bobPrivateAccount).get("subscribed_users")).isNull();
    }

    @Test
    void createOnSharedAccountWithPermissionSucceedsAndAdminSeesPrivateAccounts() throws Exception {
        mockMvc.perform(authed(post("/api/v1/comments"), aliceBearer)
                .content(body("Account", bobSharedAccount, "shared ok")))
            .andExpect(status().isCreated());
        mockMvc.perform(authed(post("/api/v1/comments"), adminBearer)
                .content(body("Account", bobPrivateAccount, "admin ok")))
            .andExpect(status().isCreated());
        assertThat(subscribedUsers(bobSharedAccount)).containsExactly(alice.getId());
        assertThat(subscribedUsers(bobPrivateAccount)).containsExactly(admin.getId());
    }

    @Test
    void createOnTaskFollowsTaskMyScope() throws Exception {
        long ownUnassigned = task("Mine", alice, null, "due_asap", null, null);
        long ownAssignedAway = task("Delegated", alice, bob, "due_asap", null, null);
        long assignedToMe = task("For Alice", bob, alice, "due_asap", null, null);

        mockMvc.perform(authed(post("/api/v1/comments"), aliceBearer).content(body("Task", ownUnassigned, "a")))
            .andExpect(status().isCreated());
        mockMvc.perform(authed(post("/api/v1/comments"), aliceBearer).content(body("Task", ownAssignedAway, "b")))
            .andExpect(status().isNotFound());
        mockMvc.perform(authed(post("/api/v1/comments"), aliceBearer).content(body("Task", assignedToMe, "c")))
            .andExpect(status().isCreated());
        assertThat(count("comments")).isEqualTo(2);
    }

    @Test
    void createOnUncompletedTaskRerunsSetDueDateThroughCommentableSave() throws Exception {
        Instant stale = Instant.parse("2020-01-01T00:00:00Z");
        long id = task("Due today", alice, null, "due_today", stale, null);
        mockMvc.perform(authed(post("/api/v1/comments"), aliceBearer).content(body("Task", id, "bump")))
            .andExpect(status().isCreated());
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM tasks WHERE id = ?", id);
        assertThat(((Timestamp) row.get("due_at")).toInstant())
            .isEqualTo(LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThat(parseSubscribed((String) row.get("subscribed_users"))).containsExactly(alice.getId());
        // due_at changed, so the Task gets its own PaperTrail update row besides the Comment create.
        assertThat(allVersions()).extracting(version -> version.get("item_type") + ":" + version.get("event"))
            .containsExactly("Task:update", "Comment:create");
    }

    @Test
    void createOnCompletedTaskDoesNotTouchDueAt() throws Exception {
        Instant stale = Instant.parse("2020-01-01T00:00:00Z");
        long id = task("Done", alice, null, "due_today", stale, Instant.parse("2024-01-01T00:00:00Z"));
        mockMvc.perform(authed(post("/api/v1/comments"), aliceBearer).content(body("Task", id, "late note")))
            .andExpect(status().isCreated());
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM tasks WHERE id = ?", id);
        assertThat(((Timestamp) row.get("due_at")).toInstant()).isEqualTo(stale);
        assertThat(allVersions()).extracting(version -> version.get("item_type")).containsExactly("Comment");
    }

    @Test
    void createWithBlankCommentReturnsRailsErrors() throws Exception {
        mockMvc.perform(authed(post("/api/v1/comments"), aliceBearer)
                .content(body("Account", alicePublicAccount, "   ")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"comment\":[\"can't be blank\"]}}", JsonCompareMode.STRICT));
        assertThat(count("comments")).isZero();
        assertThat(accountRow(alicePublicAccount).get("subscribed_users")).isNull();
    }

    @Test
    void createWithUnknownCommentableTypeIs500LikeRailsConstantizeAndMissingCommentableIsNotFound() throws Exception {
        mockMvc.perform(authed(post("/api/v1/comments"), aliceBearer)
                .content(body("Widget", 1, "x")))
            .andExpect(status().isInternalServerError());
        mockMvc.perform(authed(post("/api/v1/comments"), aliceBearer)
                .content(body("Account", 999_999, "x")))
            .andExpect(status().isNotFound());
        mockMvc.perform(authed(post("/api/v1/comments"), aliceBearer)
                .content("{\"comment\":{\"commentable_type\":\"Account\",\"comment\":\"x\"}}"))
            .andExpect(status().isNotFound());
        assertThat(count("comments")).isZero();
    }

    @Test
    void updateByOwnerChangesTextAndRecordsUpdateVersion() throws Exception {
        long id = comment(alice, "Account", alicePublicAccount, "old text");
        mockMvc.perform(authed(put("/api/v1/comments/" + id), aliceBearer)
                .content("{\"comment\":{\"comment\":\"new text\"}}"))
            .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT comment FROM comments WHERE id = ?", String.class, id))
            .isEqualTo("new text");
        List<Map<String, Object>> versions = allVersions();
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).get("event")).isEqualTo("update");
        assertThat(versions.get(0).get("related_type")).isEqualTo("Account");
        assertThat((String) versions.get(0).get("object")).startsWith("---\ncomment: old text\nid: " + id + "\n");
        assertThat((String) versions.get(0).get("object_changes")).contains("comment:\n- old text\n- new text\n");
    }

    @Test
    void updateChangingOnlyIgnoredStateWritesNoVersion() throws Exception {
        long id = comment(alice, "Account", alicePublicAccount, "text");
        mockMvc.perform(authed(put("/api/v1/comments/" + id), aliceBearer)
                .content("{\"comment\":{\"state\":\"Collapsed\"}}"))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM comments WHERE id = ?", String.class, id))
            .isEqualTo("Collapsed");
        assertThat(count("versions")).isZero();
    }

    @Test
    void updateToBlankCommentIsRejectedAndRolledBack() throws Exception {
        long id = comment(alice, "Account", alicePublicAccount, "keep me");
        mockMvc.perform(authed(put("/api/v1/comments/" + id), aliceBearer)
                .content("{\"comment\":{\"comment\":\"\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"comment\":[\"can't be blank\"]}}", JsonCompareMode.STRICT));
        assertThat(jdbcTemplate.queryForObject("SELECT comment FROM comments WHERE id = ?", String.class, id))
            .isEqualTo("keep me");
        assertThat(count("versions")).isZero();
    }

    @Test
    void updateAndDestroyByNonOwnerAreForbiddenWhileAdminIsAllowed() throws Exception {
        long id = comment(alice, "Account", alicePublicAccount, "alice wrote this");
        mockMvc.perform(authed(put("/api/v1/comments/" + id), bobBearer)
                .content("{\"comment\":{\"comment\":\"bob edit\"}}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(authed(delete("/api/v1/comments/" + id), bobBearer)).andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT comment FROM comments WHERE id = ?", String.class, id))
            .isEqualTo("alice wrote this");
        assertThat(count("versions")).isZero();

        mockMvc.perform(authed(put("/api/v1/comments/" + id), adminBearer)
                .content("{\"comment\":{\"comment\":\"admin edit\"}}"))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT comment FROM comments WHERE id = ?", String.class, id))
            .isEqualTo("admin edit");
        assertThat(allVersions().get(0).get("whodunnit")).isEqualTo(admin.getId().toString());
    }

    @Test
    void destroyByOwnerDeletesRowAndRecordsDestroyVersion() throws Exception {
        long id = comment(alice, "Account", alicePublicAccount, "bye");
        mockMvc.perform(authed(delete("/api/v1/comments/" + id), aliceBearer)).andExpect(status().isNoContent());

        assertThat(count("comments")).isZero();
        List<Map<String, Object>> versions = allVersions();
        assertThat(versions).hasSize(1);
        Map<String, Object> destroy = versions.get(0);
        assertThat(destroy.get("event")).isEqualTo("destroy");
        assertThat(destroy.get("related_type")).isEqualTo("Account");
        assertThat(destroy.get("related_id")).isEqualTo((int) alicePublicAccount);
        assertThat((String) destroy.get("object")).contains("comment: bye\n");
        assertThat((String) destroy.get("object")).contains("state: Expanded\n");
        assertThat((String) destroy.get("object_changes")).contains("comment:\n- bye\n-\n");
        assertThat((String) destroy.get("object_changes")).doesNotContain("state:");
    }

    @Test
    void writesToMissingCommentReturnNotFound() throws Exception {
        mockMvc.perform(authed(put("/api/v1/comments/424242"), aliceBearer)
                .content("{\"comment\":{\"comment\":\"x\"}}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(authed(delete("/api/v1/comments/424242"), aliceBearer)).andExpect(status().isNotFound());
    }

    private static String body(String type, long id, String text) {
        return "{\"comment\":{\"commentable_type\":\"" + type + "\",\"commentable_id\":" + id
            + ",\"comment\":\"" + text + "\"}}";
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String bearer) {
        return builder.header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON);
    }

    private Map<String, Object> accountRow(long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM accounts WHERE id = ?", id);
    }

    private List<Long> subscribedUsers(long accountId) {
        return parseSubscribed((String) accountRow(accountId).get("subscribed_users"));
    }

    private static List<Long> parseSubscribed(String yaml) {
        if (yaml == null) {
            return List.of();
        }
        return yaml.lines().filter(line -> line.matches("- \\d+"))
            .map(line -> Long.valueOf(line.substring(2))).toList();
    }

    private List<Map<String, Object>> allVersions() {
        return jdbcTemplate.queryForList("SELECT * FROM versions ORDER BY id");
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

    private long account(String name, User owner, String access) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, user_id, access, created_at, updated_at)"
                + " VALUES (?, ?, ?, now() - interval '1 hour', now() - interval '1 hour') RETURNING id",
            Long.class, name, owner.getId(), access);
    }

    private long comment(User author, String type, long commentableId, String text) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO comments (user_id, commentable_type, commentable_id, comment, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, now(), now()) RETURNING id",
            Long.class, author.getId(), type, commentableId, text);
    }

    private long task(String name, User owner, User assignee, String bucket, Instant dueAt, Instant completedAt) {
        return jdbcTemplate.queryForObject("INSERT INTO tasks (name, user_id, assigned_to, bucket, due_at, "
                + "completed_at, category, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'call', now(), now()) RETURNING id", Long.class,
            name, owner.getId(), assignee == null ? null : assignee.getId(), bucket,
            dueAt == null ? null : Timestamp.from(dueAt), completedAt == null ? null : Timestamp.from(completedAt));
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM permissions");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
