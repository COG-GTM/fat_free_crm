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
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rails {@code CommentsController#index} resolves the commentable through {@code Model.my(current_user)},
 * so a comment list is visible exactly when the parent record is: Public, owned, assigned, shared through a
 * user/group permission, or when the caller is an admin ({@code can :manage, :all}).
 */
class CommentsAccessControlIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long GROUP_ID = 4242L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private User bob;
    private User carol;
    private User admin;
    private String aliceBearer;
    private String bobBearer;
    private String carolBearer;
    private String adminBearer;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice", false);
        bob = user("bob", false);
        carol = user("carol", false);
        admin = user("admin", true);
        aliceBearer = bearer(alice);
        bobBearer = bearer(bob);
        carolBearer = bearer(carol);
        adminBearer = bearer(admin);
        jdbcTemplate.update("INSERT INTO groups (id, name, created_at, updated_at) VALUES (?, 'Sales', now(), now())",
            GROUP_ID);
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", GROUP_ID, carol.getId());
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void everyCommentableIdParameterResolvesItsOwnModelAndIsHiddenFromOtherUsers() throws Exception {
        Map<String, String> tables = Map.of(
            "account", "accounts", "campaign", "campaigns", "contact", "contacts",
            "lead", "leads", "opportunity", "opportunities");
        for (Map.Entry<String, String> entry : tables.entrySet()) {
            String param = entry.getKey() + "_id";
            long id = entity(entry.getValue(), alice, null, "Private");
            String type = Character.toUpperCase(entry.getKey().charAt(0)) + entry.getKey().substring(1);
            comment(bob, type, id, "older " + type, "now() - interval '2 hours'");
            comment(alice, type, id, "newer " + type, "now()");

            JsonNode body = getJson(aliceBearer, param, String.valueOf(id));
            assertThat(body.isArray()).as(param).isTrue();
            assertThat(body.findValuesAsText("comment")).as(param)
                .containsExactly("newer " + type, "older " + type);
            assertThat(body.findValuesAsText("commentable_type")).as(param).containsOnly(type);

            mockMvc.perform(get("/api/v1/comments").param(param, String.valueOf(id))
                    .header(HttpHeaders.AUTHORIZATION, bobBearer))
                .andExpect(status().isNotFound());
        }
    }

    @Test
    void publicAssignedAndSharedRecordsAreVisibleLikeRailsAbility() throws Exception {
        long publicAccount = entity("accounts", bob, null, "Public");
        long assignedToAlice = entity("accounts", bob, alice, "Private");
        long sharedWithAlice = entity("accounts", bob, null, "Shared");
        jdbcTemplate.update("INSERT INTO permissions (asset_type, asset_id, user_id, created_at, updated_at) "
            + "VALUES ('Account', ?, ?, now(), now())", sharedWithAlice, alice.getId());
        long sharedWithGroup = entity("accounts", bob, null, "Shared");
        jdbcTemplate.update("INSERT INTO permissions (asset_type, asset_id, group_id, created_at, updated_at) "
            + "VALUES ('Account', ?, ?, now(), now())", sharedWithGroup, GROUP_ID);
        long sharedWithNobody = entity("accounts", bob, null, "Shared");
        long[] all = {publicAccount, assignedToAlice, sharedWithAlice, sharedWithGroup, sharedWithNobody};
        for (long id : all) {
            comment(bob, "Account", id, "note " + id, "now()");
        }

        assertThat(getJson(aliceBearer, "account_id", String.valueOf(publicAccount)).findValuesAsText("comment"))
            .containsExactly("note " + publicAccount);
        assertThat(getJson(aliceBearer, "account_id", String.valueOf(assignedToAlice)).findValuesAsText("comment"))
            .containsExactly("note " + assignedToAlice);
        assertThat(getJson(aliceBearer, "account_id", String.valueOf(sharedWithAlice)).findValuesAsText("comment"))
            .containsExactly("note " + sharedWithAlice);
        assertThat(getJson(carolBearer, "account_id", String.valueOf(sharedWithGroup)).findValuesAsText("comment"))
            .containsExactly("note " + sharedWithGroup);

        expect(carolBearer, "account_id", sharedWithAlice, status().isNotFound());
        expect(aliceBearer, "account_id", sharedWithGroup, status().isNotFound());
        expect(aliceBearer, "account_id", sharedWithNobody, status().isNotFound());
        expect(carolBearer, "account_id", assignedToAlice, status().isNotFound());
    }

    @Test
    void adminsSeeCommentsOnAnyRecordAndAllCommentsWhenNoCommentableIsRequested() throws Exception {
        long privateAccount = entity("accounts", bob, null, "Private");
        comment(bob, "Account", privateAccount, "private note", "now() - interval '1 hour'");
        comment(alice, "User", bob.getId(), "about bob", "now()");

        assertThat(getJson(adminBearer, "account_id", String.valueOf(privateAccount)).findValuesAsText("comment"))
            .containsExactly("private note");
        assertThat(getJson(adminBearer, "user_id", String.valueOf(bob.getId())).findValuesAsText("comment"))
            .containsExactly("about bob");
        assertThat(getJson(adminBearer).findValuesAsText("comment"))
            .containsExactlyInAnyOrder("about bob", "private note");

        assertThat(getJson(aliceBearer).findValuesAsText("comment")).containsExactly("about bob");
        assertThat(getJson(carolBearer)).isEmpty();
        expect(aliceBearer, "user_id", bob.getId(), status().isNotFound());
    }

    @Test
    void taskCommentsFollowTaskMyRatherThanTaskVisibility() throws Exception {
        long delegated = task(alice, bob, null);
        long closedByAlice = task(bob, null, alice);
        comment(alice, "Task", delegated, "delegated note", "now()");
        comment(bob, "Task", closedByAlice, "closed note", "now()");

        // Task.my(user) is (user_id = ? AND assigned_to IS NULL) OR assigned_to = ?: the owner of a
        // delegated task and the closer of someone else's task are not in scope even though they can see
        // the task itself.
        assertThat(getJson(bobBearer, "task_id", String.valueOf(delegated)).findValuesAsText("comment"))
            .containsExactly("delegated note");
        expect(aliceBearer, "task_id", delegated, status().isNotFound());
        expect(aliceBearer, "task_id", closedByAlice, status().isNotFound());
        assertThat(getJson(bobBearer, "task_id", String.valueOf(closedByAlice)).findValuesAsText("comment"))
            .containsExactly("closed note");
        // Unlike Account.my (accessible_by, which admins pass), Task.my is a plain scope with no admin bypass.
        expect(adminBearer, "task_id", delegated, status().isNotFound());
        expect(adminBearer, "task_id", closedByAlice, status().isNotFound());
    }

    @Test
    void unknownNegativeAndOutOfRangeIdsAreNotFoundInsteadOfServerErrors() throws Exception {
        long account = entity("accounts", alice, null, "Private");
        comment(alice, "Account", account, "note", "now()");

        expect(aliceBearer, "account_id", 999_999L, status().isNotFound());
        expect(aliceBearer, "account_id", -1L, status().isNotFound());
        expect(aliceBearer, "account_id", 99_999_999_999L, status().isNotFound());
        expect(aliceBearer, "user_id", 999_999L, status().isNotFound());
        mockMvc.perform(get("/api/v1/comments").param("account_id", "")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/comments").param("account_id", String.valueOf(account)))
            .andExpect(status().isUnauthorized());
    }

    private JsonNode getJson(String bearer, String... params) throws Exception {
        var request = get("/api/v1/comments").header(HttpHeaders.AUTHORIZATION, bearer);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        String content = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();
        return JSON.readTree(content);
    }

    private void expect(String bearer, String param, long id, org.springframework.test.web.servlet.ResultMatcher
                        matcher) throws Exception {
        mockMvc.perform(get("/api/v1/comments").param(param, String.valueOf(id))
                .header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(matcher);
    }

    private User user(String username, boolean isAdmin) {
        User user = new User();
        user.setUsername(username);
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(isAdmin);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private long entity(String table, User owner, User assignee, String access) {
        return jdbcTemplate.queryForObject("INSERT INTO " + table + " (user_id, assigned_to, access, created_at, "
                + "updated_at) VALUES (?, ?, ?, now(), now()) RETURNING id", Long.class,
            owner.getId(), assignee == null ? null : assignee.getId(), access);
    }

    private long task(User owner, User assignee, User completedBy) {
        return jdbcTemplate.queryForObject("INSERT INTO tasks (name, user_id, assigned_to, bucket, completed_at, "
                + "completed_by, category, created_at, updated_at) "
                + "VALUES ('Task', ?, ?, 'due_asap', ?, ?, 'call', now(), now()) RETURNING id", Long.class,
            owner.getId(), assignee == null ? null : assignee.getId(),
            completedBy == null ? null : java.sql.Timestamp.from(Instant.now()),
            completedBy == null ? null : completedBy.getId());
    }

    private void comment(User author, String type, long commentableId, String text, String createdAt) {
        jdbcTemplate.update("INSERT INTO comments (user_id, commentable_id, commentable_type, comment, created_at, "
            + "updated_at) VALUES (?, ?, ?, ?, " + createdAt + ", now())", author.getId(), commentableId, type, text);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM permissions");
        jdbcTemplate.update("DELETE FROM groups_users");
        jdbcTemplate.update("DELETE FROM groups");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM campaigns");
        jdbcTemplate.update("DELETE FROM contacts");
        jdbcTemplate.update("DELETE FROM leads");
        jdbcTemplate.update("DELETE FROM opportunities");
        jdbcTemplate.update("DELETE FROM users");
    }
}
