package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Cross-user / admin access rules for the comments and task-autocomplete read endpoints, pinned to Rails:
 * <ul>
 *   <li>{@code CommentsController#index} resolves the commentable with
 *       {@code find_class(@commentable).my(current_user).find(...)} - a record outside the user's scope is
 *       a 404, admins see everything, and {@code Task.my} is "created by me and unassigned, or assigned to me".</li>
 *   <li>Without a commentable, {@code Comment.accessible_by(current_ability)} is "own comments" for a regular
 *       user ({@code can :manage, Comment, user_id: user.id}) and everything for an admin.</li>
 *   <li>{@code TasksController#auto_complete} excludes {@code related.classify.constantize.find(id).tasks}
 *       when {@code related=<model>/<id>} is given and nothing otherwise.</li>
 * </ul>
 */
class TasksCommentsAccessIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private User bob;
    private User admin;
    private String aliceBearer;
    private String bobBearer;
    private String adminBearer;
    private Account alicePrivateAccount;
    private Account alicePublicAccount;
    private long taskAssignedToBob;
    private long taskOnAccount;
    private long taskUnrelated;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice", false);
        bob = user("bob", false);
        admin = user("admin", true);
        aliceBearer = bearer(alice);
        bobBearer = bearer(bob);
        adminBearer = bearer(admin);
        alicePrivateAccount = account(alice, "Alice Private", "Private");
        alicePublicAccount = account(alice, "Alice Public", "Public");
        taskAssignedToBob = task("Alice delegates to Bob", alice, bob, null, null);
        taskOnAccount = task("Call Alice Public", alice, null, "Account", alicePublicAccount.getId());
        taskUnrelated = task("Call nobody", alice, null, null, null);
        comment(alice, "Account", alicePrivateAccount.getId(), "alice on private account");
        comment(bob, "Account", alicePublicAccount.getId(), "bob on public account");
        comment(bob, "Task", taskAssignedToBob, "bob on delegated task");
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void privateAccountCommentsAreNotFoundForOtherUsersButVisibleToAdmin() throws Exception {
        String accountId = String.valueOf(alicePrivateAccount.getId());

        mockMvc.perform(authed("/api/v1/comments", aliceBearer).param("account_id", accountId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].comment").value("alice on private account"));
        mockMvc.perform(authed("/api/v1/comments", bobBearer).param("account_id", accountId))
            .andExpect(status().isNotFound());
        mockMvc.perform(authed("/api/v1/comments", adminBearer).param("account_id", accountId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void publicAccountCommentsAreVisibleToEveryoneRegardlessOfAuthor() throws Exception {
        String accountId = String.valueOf(alicePublicAccount.getId());

        for (String token : List.of(aliceBearer, bobBearer, adminBearer)) {
            mockMvc.perform(authed("/api/v1/comments", token).param("account_id", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].comment").value("bob on public account"))
                .andExpect(jsonPath("$[0].user_id").value(bob.getId()));
        }
    }

    @Test
    void taskCommentableFollowsTaskMyNotTrackedBy() throws Exception {
        String taskId = String.valueOf(taskAssignedToBob);

        // Task.my => (user_id = me AND assigned_to IS NULL) OR assigned_to = me: the delegating creator is excluded.
        mockMvc.perform(authed("/api/v1/comments", aliceBearer).param("task_id", taskId))
            .andExpect(status().isNotFound());
        mockMvc.perform(authed("/api/v1/comments", bobBearer).param("task_id", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].comment").value("bob on delegated task"));
        // Task.my is not ability-based in Rails: an admin also gets 404 for a task they neither own nor are assigned.
        mockMvc.perform(authed("/api/v1/comments", adminBearer).param("task_id", taskId))
            .andExpect(status().isNotFound());
    }

    @Test
    void nonExistentAndNonPositiveCommentableIdsAreNotFound() throws Exception {
        mockMvc.perform(authed("/api/v1/comments", aliceBearer).param("task_id", "0"))
            .andExpect(status().isNotFound());
        mockMvc.perform(authed("/api/v1/comments", aliceBearer).param("task_id", "-1"))
            .andExpect(status().isNotFound());
        mockMvc.perform(authed("/api/v1/comments", aliceBearer).param("account_id", "999999"))
            .andExpect(status().isNotFound());
        mockMvc.perform(authed("/api/v1/comments", adminBearer).param("account_id", "999999"))
            .andExpect(status().isNotFound());
    }

    @Test
    void firstCommentableParameterWinsLikeRailsParamsKeysDetect() throws Exception {
        mockMvc.perform(authed("/api/v1/comments", bobBearer)
                .param("account_id", String.valueOf(alicePublicAccount.getId()))
                .param("bogus_id", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].comment").value("bob on public account"));

        // Reversed order: the unknown key is detected first, so Rails raises on constantize (400 here).
        mockMvc.perform(authed("/api/v1/comments", bobBearer)
                .param("bogus_id", "1")
                .param("account_id", String.valueOf(alicePublicAccount.getId())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void withoutCommentableRegularUsersSeeOnlyOwnCommentsAndAdminsSeeAll() throws Exception {
        JsonNode aliceBody = getJson("/api/v1/comments", aliceBearer);
        assertThat(aliceBody.findValuesAsText("comment")).containsExactly("alice on private account");

        JsonNode bobBody = getJson("/api/v1/comments", bobBearer);
        assertThat(bobBody.findValuesAsText("comment"))
            .containsExactlyInAnyOrder("bob on public account", "bob on delegated task");

        JsonNode adminBody = getJson("/api/v1/comments", adminBearer);
        assertThat(adminBody.size()).isEqualTo(3);
    }

    @Test
    void adminCanListAnotherUsersCommentsByUserIdButRegularUsersCannot() throws Exception {
        comment(bob, "User", alice.getId(), "bob about alice");
        String aliceId = String.valueOf(alice.getId());

        mockMvc.perform(authed("/api/v1/comments", adminBearer).param("user_id", aliceId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].comment").value("bob about alice"));
        // User.my(bob) = accessible_by(ability) = bob himself only, even though bob authored the comment.
        mockMvc.perform(authed("/api/v1/comments", bobBearer).param("user_id", aliceId))
            .andExpect(status().isNotFound());
    }

    @Test
    void taskAutocompleteExcludesTasksOfTheRelatedRecordOnly() throws Exception {
        String accountId = String.valueOf(alicePublicAccount.getId());

        JsonNode unrelated = getJson("/api/v1/tasks/autocomplete", aliceBearer, "term", "Call");
        assertThat(ids(unrelated)).containsExactlyInAnyOrder(taskOnAccount, taskUnrelated);

        JsonNode singular = getJson("/api/v1/tasks/autocomplete", aliceBearer, "term", "Call",
            "related", "account/" + accountId);
        assertThat(ids(singular)).containsExactly(taskUnrelated);

        JsonNode plural = getJson("/api/v1/tasks/autocomplete", aliceBearer, "term", "Call",
            "related", "accounts/" + accountId);
        assertThat(ids(plural)).containsExactly(taskUnrelated);

        JsonNode missingRecord = getJson("/api/v1/tasks/autocomplete", aliceBearer, "term", "Call",
            "related", "account/999999");
        assertThat(ids(missingRecord)).containsExactlyInAnyOrder(taskOnAccount, taskUnrelated);

        JsonNode unknownModel = getJson("/api/v1/tasks/autocomplete", aliceBearer, "term", "Call",
            "related", "widget/" + accountId);
        assertThat(ids(unknownModel)).containsExactlyInAnyOrder(taskOnAccount, taskUnrelated);
    }

    @Test
    void taskAutocompleteNeverLeaksOtherUsersTasksEvenWhenRelatedMatches() throws Exception {
        JsonNode body = getJson("/api/v1/tasks/autocomplete", bobBearer, "term", "Call",
            "related", "account/" + alicePublicAccount.getId());
        assertThat(ids(body)).isEmpty();
    }

    private MockHttpServletRequestBuilder authed(String path, String bearer) {
        return get(path).header(HttpHeaders.AUTHORIZATION, bearer);
    }

    private JsonNode getJson(String path, String bearer, String... params) throws Exception {
        MockHttpServletRequestBuilder request = authed(path, bearer);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();
        return JSON.readTree(body);
    }

    private static List<Long> ids(JsonNode autocompleteBody) {
        return autocompleteBody.at("/results").findValues("id").stream().map(JsonNode::asLong).toList();
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

    private Account account(User owner, String name, String access) {
        Account account = new Account();
        account.setName(name);
        account.setEmail(name.toLowerCase().replace(' ', '.') + "@example.test");
        account.setAccess(access);
        account.setUser(owner);
        Instant at = Instant.now();
        account.setCreatedAt(at);
        account.setUpdatedAt(at);
        return accountRepository.saveAndFlush(account);
    }

    private long task(String name, User owner, User assignee, String assetType, Long assetId) {
        return jdbcTemplate.queryForObject("INSERT INTO tasks (name, user_id, assigned_to, bucket, asset_type, "
                + "asset_id, category, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'due_asap', ?, ?, 'call', now(), now()) RETURNING id", Long.class,
            name, owner.getId(), assignee == null ? null : assignee.getId(), assetType, assetId);
    }

    private void comment(User author, String type, long commentableId, String text) {
        jdbcTemplate.update("INSERT INTO comments (user_id, commentable_id, commentable_type, comment, created_at, "
            + "updated_at) VALUES (?, ?, ?, ?, now(), now())", author.getId(), commentableId, type, text);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
