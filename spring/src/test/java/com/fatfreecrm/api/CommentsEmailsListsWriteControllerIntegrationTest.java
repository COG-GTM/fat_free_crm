package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Rails {@code CommentsController}/{@code EmailsController#destroy}/{@code ListsController} write
 * parity through the HTTP layer: {@code Task.my} visibility for the commentable, mention and author
 * subscriptions, CanCan owner-or-admin on comment/email update/destroy (Rails 401 ≙ Spring 403),
 * PaperTrail rows with {@code related}, the case-insensitive per-user list upsert, and the two
 * mirrored Rails gaps (any user may delete any list; {@code is_global} may assign another user).
 */
class CommentsEmailsListsWriteControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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
    private long aliceTask;
    private long bobTask;
    private long bobTaskAssignedToAlice;
    private long aliceComment;
    private long bobComment;
    private long aliceEmail;
    private long bobEmail;
    private long aliceList;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice", false);
        bob = user("bob", false);
        admin = user("carol", true);
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
        bobBearer = "Bearer " + jwtTokenService.issue(bob).accessToken();
        adminBearer = "Bearer " + jwtTokenService.issue(admin).accessToken();
        aliceTask = task("Alice call", alice, null);
        bobTask = task("Bob call", bob, null);
        bobTaskAssignedToAlice = task("Bob delegates", bob, alice);
        aliceComment = comment(alice, aliceTask, "alice says");
        bobComment = comment(bob, aliceTask, "bob says");
        aliceEmail = email(alice, aliceTask, "Alice mail");
        bobEmail = email(bob, bobTask, "Bob mail");
        aliceList = list("Hot leads", "/leads?x=1", alice);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void writesRequireAuthentication() throws Exception {
        mockMvc.perform(json(post("/api/v1/comments"), commentBody(aliceTask, "x")))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(json(put("/api/v1/comments/" + aliceComment), commentBody(aliceTask, "x")))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/comments/" + aliceComment)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/emails/" + aliceEmail)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(post("/api/v1/lists"), listBody("x", "/x", null))).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/lists/" + aliceList)).andExpect(status().isUnauthorized());
        assertThat(count("comments")).isEqualTo(2);
        assertThat(count("emails")).isEqualTo(2);
        assertThat(count("lists")).isEqualTo(1);
        assertThat(count("versions")).isZero();
    }

    @Test
    void createCommentSubscribesExactMentionsThenAuthorAndRecordsRelatedVersion() throws Exception {
        Timestamp taskUpdatedBefore = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM tasks WHERE id = ?", Timestamp.class, aliceTask);

        MvcResult result = mockMvc.perform(json(post("/api/v1/comments"),
                commentBody(aliceTask, "ping @bob, @Alice and @nobody")).header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.comment").value("ping @bob, @Alice and @nobody"))
            .andExpect(jsonPath("$.user_id").value(alice.getId()))
            .andReturn();
        long id = JSON.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/v1/comments/" + id);

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM comments WHERE id = ?", id);
        assertThat(row).containsEntry("user_id", alice.getId().intValue())
            .containsEntry("commentable_type", "Task")
            .containsEntry("commentable_id", (int) aliceTask)
            .containsEntry("state", "Expanded");

        // @Alice does not match username "alice" (find_by_username is exact); the author is appended last.
        assertThat(jdbcTemplate.queryForObject("SELECT subscribed_users FROM tasks WHERE id = ?", String.class,
            aliceTask)).isEqualTo("---\n- " + bob.getId() + "\n- " + alice.getId() + "\n");
        assertThat(jdbcTemplate.queryForObject("SELECT updated_at FROM tasks WHERE id = ?", Timestamp.class, aliceTask))
            .isAfter(taskUpdatedBefore);

        List<Map<String, Object>> versions = versionsOf("Comment", id);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0)).containsEntry("event", "create")
            .containsEntry("whodunnit", alice.getId().toString())
            .containsEntry("object", null)
            .containsEntry("related_type", "Task")
            .containsEntry("related_id", (int) aliceTask);
        assertThat((String) versions.get(0).get("object_changes"))
            .contains("comment:\n-\n- ping @bob, @Alice and @nobody\n")
            .contains("user_id:\n-\n- " + alice.getId() + "\n")
            .doesNotContain("state:");
        // subscribed_users is ignored on Task, so the commentable save writes no Task version.
        assertThat(versionsOf("Task", aliceTask)).isEmpty();

        // A second comment appends the author again: Rails allows duplicates in subscribed_users.
        mockMvc.perform(json(post("/api/v1/comments"), commentBody(aliceTask, "again"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject("SELECT subscribed_users FROM tasks WHERE id = ?", String.class,
            aliceTask)).isEqualTo("---\n- " + bob.getId() + "\n- " + alice.getId() + "\n- " + alice.getId() + "\n");
    }

    @Test
    void blankCommentIsRejectedBeforeAnySubscriptionOrRowIsWritten() throws Exception {
        mockMvc.perform(json(post("/api/v1/comments"), commentBody(aliceTask, ""))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json("{\"errors\":{\"comment\":[\"can't be blank\"]}}", JsonCompareMode.STRICT));
        mockMvc.perform(json(post("/api/v1/comments"), "{\"comment\":{\"commentable_type\":\"Task\","
                + "\"commentable_id\":" + aliceTask + ",\"comment\":null}}")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isUnprocessableEntity());

        assertThat(count("comments")).isEqualTo(2);
        assertThat(count("versions")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT subscribed_users FROM tasks WHERE id = ?", String.class,
            aliceTask)).isNull();
    }

    @Test
    void commentableMustBeInTaskMyScopeOrTheCreateIsNotFound() throws Exception {
        mockMvc.perform(json(post("/api/v1/comments"), commentBody(bobTask, "intrude"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(json(post("/api/v1/comments"), commentBody(999999, "ghost"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        // Task.my = (owner AND unassigned) OR assignee: the delegating owner is outside it.
        mockMvc.perform(json(post("/api/v1/comments"), commentBody(bobTaskAssignedToAlice, "owner"))
                .header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(json(post("/api/v1/comments"), commentBody(bobTaskAssignedToAlice, "assignee"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForList("SELECT comment FROM comments ORDER BY id", String.class))
            .containsExactly("alice says", "bob says", "assignee");
        assertThat(versionsOf("Task", bobTask)).isEmpty();
    }

    @Test
    void updateCommentIsOwnerOrAdminAndRecordsUpdateVersion() throws Exception {
        mockMvc.perform(json(put("/api/v1/comments/" + aliceComment), "{\"comment\":{\"comment\":\"hijack\"}}")
                .header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(json(put("/api/v1/comments/999999"), "{\"comment\":{\"comment\":\"ghost\"}}")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(json(put("/api/v1/comments/" + aliceComment), "{\"comment\":{\"comment\":\"\"}}")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"comment\":[\"can't be blank\"]}}", JsonCompareMode.STRICT));
        assertThat(jdbcTemplate.queryForObject("SELECT comment FROM comments WHERE id = ?", String.class, aliceComment))
            .isEqualTo("alice says");
        assertThat(count("versions")).isZero();

        mockMvc.perform(json(put("/api/v1/comments/" + aliceComment), "{\"comment\":{\"comment\":\"edited\"}}")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNoContent());
        mockMvc.perform(json(put("/api/v1/comments/" + bobComment), "{\"comment\":{\"comment\":\"moderated\"}}")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForList("SELECT comment FROM comments ORDER BY id", String.class))
            .containsExactly("edited", "moderated");
        List<Map<String, Object>> versions = versionsOf("Comment", aliceComment);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0)).containsEntry("event", "update")
            .containsEntry("whodunnit", alice.getId().toString())
            .containsEntry("related_type", "Task")
            .containsEntry("related_id", (int) aliceTask);
        assertThat((String) versions.get(0).get("object")).startsWith("---\ncomment: alice says\nid: " + aliceComment);
        assertThat((String) versions.get(0).get("object_changes")).contains("comment:\n- alice says\n- edited\n");
        assertThat(versionsOf("Comment", bobComment).get(0)).containsEntry("whodunnit", admin.getId().toString());
    }

    @Test
    void destroyCommentIsOwnerOrAdminAndRecordsDestroyVersion() throws Exception {
        mockMvc.perform(delete("/api/v1/comments/" + aliceComment).header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/comments/999999").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        assertThat(count("comments")).isEqualTo(2);

        mockMvc.perform(delete("/api/v1/comments/" + aliceComment).header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/comments/" + bobComment).header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNoContent());

        assertThat(count("comments")).isZero();
        List<Map<String, Object>> versions = versionsOf("Comment", aliceComment);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0)).containsEntry("event", "destroy")
            .containsEntry("related_type", "Task")
            .containsEntry("related_id", (int) aliceTask);
        assertThat((String) versions.get(0).get("object")).contains("comment: alice says\n")
            .contains("state: Expanded\n");
        assertThat((String) versions.get(0).get("object_changes")).contains("comment:\n- alice says\n-\n")
            .doesNotContain("state:");
    }

    @Test
    void destroyEmailIsOwnerOrAdminWithMediatorRelatedVersion() throws Exception {
        mockMvc.perform(delete("/api/v1/emails/" + aliceEmail).header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/emails/999999").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        assertThat(count("emails")).isEqualTo(2);
        assertThat(count("versions")).isZero();

        mockMvc.perform(delete("/api/v1/emails/" + aliceEmail).header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/emails/" + bobEmail).header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNoContent());

        assertThat(count("emails")).isZero();
        List<Map<String, Object>> versions = versionsOf("Email", aliceEmail);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0)).containsEntry("event", "destroy")
            .containsEntry("whodunnit", alice.getId().toString())
            .containsEntry("related_type", "Task")
            .containsEntry("related_id", (int) aliceTask);
        assertThat((String) versions.get(0).get("object"))
            .startsWith("---\nid: " + aliceEmail + "\nimap_message_id: ")
            .contains("subject: Alice mail\n")
            .contains("state: Expanded\n");
        assertThat((String) versions.get(0).get("object_changes")).contains("subject:\n- Alice mail\n-\n")
            .doesNotContain("state:");
        assertThat(versionsOf("Email", bobEmail).get(0)).containsEntry("whodunnit", admin.getId().toString());
    }

    @Test
    void listCreateUpsertsByLowercaseNamePerUserAndWritesNoVersions() throws Exception {
        MvcResult result = mockMvc.perform(json(post("/api/v1/lists"), listBody("hot LEADS", "/leads?x=2", null))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/lists/" + aliceList))
            .andExpect(jsonPath("$.id").value(aliceList))
            .andExpect(jsonPath("$.name").value("hot LEADS"))
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("\"url\":\"/leads?x=2\"");
        assertThat(count("lists")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT name, url, user_id FROM lists WHERE id = ?", aliceList))
            .containsEntry("name", "hot LEADS").containsEntry("url", "/leads?x=2")
            .containsEntry("user_id", alice.getId().intValue());

        MvcResult bobResult = mockMvc.perform(json(post("/api/v1/lists"), listBody("Hot Leads", "/leads?x=3", null))
                .header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isCreated())
            .andReturn();
        long bobList = JSON.readTree(bobResult.getResponse().getContentAsString()).path("id").asLong();
        assertThat(bobList).isNotEqualTo(aliceList);
        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM lists WHERE id = ?", Long.class, bobList))
            .isEqualTo(bob.getId());

        // Second create with the same name by the same user updates in place.
        mockMvc.perform(json(post("/api/v1/lists"), listBody("HOT LEADS", "/leads?x=4", null))
                .header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(bobList));
        assertThat(count("lists")).isEqualTo(2);
        assertThat(count("versions")).isZero();
    }

    @Test
    void isGlobalControlsTheOwnerLikeRailsListsController() throws Exception {
        MvcResult global = mockMvc.perform(json(post("/api/v1/lists"), listBody("Everyone", "/all", "1"))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isCreated())
            .andReturn();
        long globalId = JSON.readTree(global.getResponse().getContentAsString()).path("id").asLong();
        assertThat(jdbcTemplate.queryForMap("SELECT user_id FROM lists WHERE id = ?", globalId))
            .containsEntry("user_id", null);

        // is_global != "1" forces user_id to the current user even when another user_id is supplied.
        MvcResult forced = mockMvc.perform(json(post("/api/v1/lists"), listBody("Mine", "/mine", "0", bob.getId()))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isCreated())
            .andReturn();
        long forcedId = JSON.readTree(forced.getResponse().getContentAsString()).path("id").asLong();
        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM lists WHERE id = ?", Long.class, forcedId))
            .isEqualTo(alice.getId());

        // Mirrored Rails gap: is_global = "1" lets the caller pick any user_id.
        MvcResult reassigned = mockMvc.perform(json(post("/api/v1/lists"),
                listBody("Theirs", "/theirs", "1", bob.getId()))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isCreated())
            .andReturn();
        long reassignedId = JSON.readTree(reassigned.getResponse().getContentAsString()).path("id").asLong();
        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM lists WHERE id = ?", Long.class, reassignedId))
            .isEqualTo(bob.getId());
    }

    @Test
    void listCreateRequiresNameAndUrlInRailsOrder() throws Exception {
        mockMvc.perform(json(post("/api/v1/lists"), listBody("", "", null))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json(
                "{\"errors\":{\"name\":[\"can't be blank\"],\"url\":[\"can't be blank\"]}}", JsonCompareMode.STRICT));
        mockMvc.perform(json(post("/api/v1/lists"), "{}").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json(
                "{\"errors\":{\"name\":[\"can't be blank\"],\"url\":[\"can't be blank\"]}}", JsonCompareMode.STRICT));
        mockMvc.perform(json(post("/api/v1/lists"), listBody("Named", "", null))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"url\":[\"can't be blank\"]}}", JsonCompareMode.STRICT));
        assertThat(count("lists")).isEqualTo(1);
    }

    @Test
    void listDestroyHasNoAuthorizationMirroringRails() throws Exception {
        mockMvc.perform(delete("/api/v1/lists/999999").header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isNotFound());
        assertThat(count("lists")).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/lists/" + aliceList).header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isNoContent());

        assertThat(count("lists")).isZero();
        assertThat(count("versions")).isZero();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String commentBody(long commentableId, String text) throws Exception {
        ObjectNode comment = JSON.createObjectNode()
            .put("commentable_type", "Task")
            .put("commentable_id", commentableId)
            .put("comment", text);
        return JSON.writeValueAsString(JSON.createObjectNode().set("comment", comment));
    }

    private static String listBody(String name, String url, String isGlobal, Object... userId) throws Exception {
        ObjectNode list = JSON.createObjectNode().put("name", name).put("url", url);
        if (userId.length > 0) {
            list.set("user_id", JSON.valueToTree(userId[0]));
        }
        ObjectNode body = JSON.createObjectNode();
        body.set("list", list);
        if (isGlobal != null) {
            body.put("is_global", isGlobal);
        }
        return JSON.writeValueAsString(body);
    }

    private List<Map<String, Object>> versionsOf(String itemType, long itemId) {
        return jdbcTemplate.queryForList("SELECT event, whodunnit, object, object_changes, related_type, "
            + "related_id FROM versions WHERE item_type = ? AND item_id = ? ORDER BY id", itemType, itemId);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
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

    private long task(String name, User owner, User assignee) {
        return jdbcTemplate.queryForObject("INSERT INTO tasks (name, user_id, assigned_to, bucket, category, "
                + "created_at, updated_at) VALUES (?, ?, ?, 'due_asap', 'call', now() - interval '1 minute', "
                + "now() - interval '1 minute') RETURNING id", Long.class,
            name, owner.getId(), assignee == null ? null : assignee.getId());
    }

    private long comment(User author, long taskId, String text) {
        return jdbcTemplate.queryForObject("INSERT INTO comments (user_id, commentable_type, commentable_id, "
                + "comment, created_at, updated_at) VALUES (?, 'Task', ?, ?, now(), now()) RETURNING id",
            Long.class, author.getId(), taskId, text);
    }

    private long email(User owner, long taskId, String subject) {
        return jdbcTemplate.queryForObject("INSERT INTO emails (imap_message_id, user_id, mediator_type, "
                + "mediator_id, sent_from, sent_to, subject, created_at, updated_at) "
                + "VALUES (?, ?, 'Task', ?, 'from@example.com', 'to@example.com', ?, now(), now()) RETURNING id",
            Long.class, "<" + subject.hashCode() + "@example.com>", owner.getId(), taskId, subject);
    }

    private long list(String name, String url, User owner) {
        return jdbcTemplate.queryForObject("INSERT INTO lists (name, url, user_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, now(), now()) RETURNING id", Long.class, name, url, owner.getId());
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM lists");
        jdbcTemplate.update("DELETE FROM emails");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM users");
    }
}
