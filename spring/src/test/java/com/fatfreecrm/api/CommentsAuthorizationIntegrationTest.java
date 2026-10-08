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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins {@code GET /api/v1/comments?<commentable>_id=} to Rails {@code CommentsController#index}: the commentable is
 * resolved through {@code Klass.my(current_user)} ({@code accessible_by(ability)}), so Private rows of other users,
 * Shared rows without a grant and {@code Task.my} misses answer 404 while Public, owned, assigned, permission-granted,
 * group-granted and admin reads succeed. Without a commentable, {@code Comment.accessible_by} returns the caller's own
 * comments, or everything for admins.
 */
class CommentsAuthorizationIntegrationTest extends AbstractPostgresIntegrationTest {

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

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice", false);
        bob = user("bob", false);
        admin = user("admin", true);
        aliceBearer = bearer(alice);
        bobBearer = bearer(bob);
        adminBearer = bearer(admin);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void accountCommentsFollowPublicPrivateSharedAssignedAndAdminAccessLikeRailsAccountMy() throws Exception {
        long publicAccount = account(bob, "Public Co", "Public", null);
        long privateAccount = account(bob, "Private Co", "Private", null);
        long sharedWithUser = account(bob, "Shared User Co", "Shared", null);
        long sharedWithGroup = account(bob, "Shared Group Co", "Shared", null);
        long sharedWithoutGrant = account(bob, "Shared Nobody Co", "Shared", null);
        long assignedToAlice = account(bob, "Assigned Co", "Private", alice);
        for (long id : new long[] {publicAccount, privateAccount, sharedWithUser, sharedWithGroup, sharedWithoutGrant,
            assignedToAlice}) {
            comment(bob, "Account", id, "note " + id, Instant.parse("2025-01-01T00:00:00Z"));
        }
        jdbcTemplate.update("INSERT INTO permissions (asset_type, asset_id, user_id, created_at, updated_at) "
            + "VALUES ('Account', ?, ?, now(), now())", sharedWithUser, alice.getId());
        jdbcTemplate.update("INSERT INTO groups (id, name, created_at, updated_at) VALUES (7, 'Sales', now(), now())");
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (7, ?)", alice.getId());
        jdbcTemplate.update("INSERT INTO permissions (asset_type, asset_id, group_id, created_at, updated_at) "
            + "VALUES ('Account', ?, 7, now(), now())", sharedWithGroup);

        assertThat(comments(aliceBearer, "account_id", publicAccount)).containsExactly("note " + publicAccount);
        assertThat(comments(aliceBearer, "account_id", sharedWithUser)).containsExactly("note " + sharedWithUser);
        assertThat(comments(aliceBearer, "account_id", sharedWithGroup)).containsExactly("note " + sharedWithGroup);
        assertThat(comments(aliceBearer, "account_id", assignedToAlice)).containsExactly("note " + assignedToAlice);
        expectNotFound(aliceBearer, "account_id", privateAccount);
        expectNotFound(aliceBearer, "account_id", sharedWithoutGrant);

        assertThat(comments(bobBearer, "account_id", privateAccount)).containsExactly("note " + privateAccount);
        assertThat(comments(adminBearer, "account_id", privateAccount)).containsExactly("note " + privateAccount);
        assertThat(comments(adminBearer, "account_id", sharedWithoutGrant))
            .containsExactly("note " + sharedWithoutGrant);
    }

    @Test
    void everyEntityCommentableHidesPrivateRowsOfOtherUsersAndOrdersCommentsNewestFirst() throws Exception {
        long contact = insertEntity("contacts", "(first_name, last_name, user_id, access, created_at, updated_at) "
            + "VALUES ('Bob', 'Private', ?, 'Private', now(), now())", bob);
        long lead = insertEntity("leads", "(first_name, last_name, user_id, access, status, created_at, updated_at) "
            + "VALUES ('Bob', 'Lead', ?, 'Private', 'new', now(), now())", bob);
        long opportunity = insertEntity("opportunities", "(name, user_id, access, stage, created_at, updated_at) "
            + "VALUES ('Bob Deal', ?, 'Private', 'prospecting', now(), now())", bob);
        long campaign = insertEntity("campaigns", "(name, user_id, access, status, created_at, updated_at) "
            + "VALUES ('Bob Campaign', ?, 'Private', 'planned', now(), now())", bob);
        comment(bob, "Contact", contact, "older contact note", Instant.parse("2025-01-01T00:00:00Z"));
        comment(alice, "Contact", contact, "newer contact note", Instant.parse("2025-06-01T00:00:00Z"));
        comment(bob, "Lead", lead, "lead note", Instant.parse("2025-01-01T00:00:00Z"));
        comment(bob, "Opportunity", opportunity, "opportunity note", Instant.parse("2025-01-01T00:00:00Z"));
        comment(bob, "Campaign", campaign, "campaign note", Instant.parse("2025-01-01T00:00:00Z"));

        expectNotFound(aliceBearer, "contact_id", contact);
        expectNotFound(aliceBearer, "lead_id", lead);
        expectNotFound(aliceBearer, "opportunity_id", opportunity);
        expectNotFound(aliceBearer, "campaign_id", campaign);

        assertThat(comments(bobBearer, "contact_id", contact))
            .containsExactly("newer contact note", "older contact note");
        assertThat(comments(bobBearer, "lead_id", lead)).containsExactly("lead note");
        assertThat(comments(bobBearer, "opportunity_id", opportunity)).containsExactly("opportunity note");
        assertThat(comments(bobBearer, "campaign_id", campaign)).containsExactly("campaign note");
        assertThat(comments(adminBearer, "campaign_id", campaign)).containsExactly("campaign note");
    }

    /**
     * Rails resolves task commentables with the plain {@code Task.my} scope rather than {@code accessible_by}, so the
     * assigner of a delegated task and admins who neither own nor are assigned the task both get 404, unlike entities.
     */
    @Test
    void taskCommentsUseRailsTaskMyWhichExcludesTheAssignerAndUninvolvedAdmins() throws Exception {
        long delegated = task("Delegated", bob, alice);
        long adminOwn = task("Admin own", admin, null);
        long adminAssigned = task("Admin assigned", bob, admin);
        comment(bob, "Task", delegated, "delegated note", Instant.parse("2025-01-01T00:00:00Z"));
        comment(admin, "Task", adminOwn, "admin own note", Instant.parse("2025-01-01T00:00:00Z"));
        comment(bob, "Task", adminAssigned, "admin assigned note", Instant.parse("2025-01-01T00:00:00Z"));

        assertThat(comments(aliceBearer, "task_id", delegated)).containsExactly("delegated note");
        expectNotFound(bobBearer, "task_id", delegated);
        expectNotFound(adminBearer, "task_id", delegated);
        assertThat(comments(adminBearer, "task_id", adminOwn)).containsExactly("admin own note");
        assertThat(comments(adminBearer, "task_id", adminAssigned)).containsExactly("admin assigned note");
        expectNotFound(bobBearer, "task_id", adminAssigned);
    }

    @Test
    void outOfRangeCommentableIdsAreNotFoundAndTheFirstIdParameterNamesTheCommentable() throws Exception {
        long publicAccount = account(bob, "Public Co", "Public", null);
        long aliceTask = task("Alice task", alice, null);
        comment(bob, "Account", publicAccount, "account note", Instant.parse("2025-01-01T00:00:00Z"));
        comment(alice, "Task", aliceTask, "task note", Instant.parse("2025-01-01T00:00:00Z"));

        for (String id : new String[] {"0", "-1", "2147483648", "99999999999999999999", ""}) {
            mockMvc.perform(get("/api/v1/comments").param("account_id", id)
                    .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isNotFound());
        }

        JsonNode firstWins = JSON.readTree(mockMvc.perform(get("/api/v1/comments")
                .param("account_id", String.valueOf(publicAccount))
                .param("task_id", String.valueOf(aliceTask))
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(firstWins.findValuesAsText("comment")).containsExactly("account note");
        assertThat(firstWins.findValuesAsText("commentable_type")).containsOnly("Account");
    }

    @Test
    void withoutACommentableNonAdminsSeeOnlyTheirOwnCommentsAndAdminsSeeEverything() throws Exception {
        long publicAccount = account(bob, "Public Co", "Public", null);
        comment(alice, "Account", publicAccount, "alice second", Instant.parse("2025-03-01T00:00:00Z"));
        comment(bob, "Account", publicAccount, "bob note", Instant.parse("2025-02-01T00:00:00Z"));
        comment(alice, "Account", publicAccount, "alice first", Instant.parse("2025-01-01T00:00:00Z"));

        assertThat(comments(aliceBearer)).containsExactly("alice second", "alice first");
        assertThat(comments(bobBearer)).containsExactly("bob note");
        assertThat(comments(adminBearer)).containsExactly("alice second", "bob note", "alice first");
        mockMvc.perform(get("/api/v1/comments")).andExpect(status().isUnauthorized());
    }

    private java.util.List<String> comments(String bearer, String... params) throws Exception {
        var request = get("/api/v1/comments").header(HttpHeaders.AUTHORIZATION, bearer);
        for (int index = 0; index < params.length; index += 2) {
            request = request.param(params[index], params[index + 1]);
        }
        String content = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();
        return JSON.readTree(content).findValuesAsText("comment");
    }

    private java.util.List<String> comments(String bearer, String param, long id) throws Exception {
        return comments(bearer, param, String.valueOf(id));
    }

    private void expectNotFound(String bearer, String param, long id) throws Exception {
        mockMvc.perform(get("/api/v1/comments").param(param, String.valueOf(id))
                .header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isNotFound());
    }

    private User user(String username, boolean isAdmin) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(isAdmin);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private long account(User owner, String name, String access, User assignee) {
        return jdbcTemplate.queryForObject("INSERT INTO accounts (name, user_id, assigned_to, access, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, now(), now()) RETURNING id", Long.class,
            name, owner.getId(), assignee == null ? null : assignee.getId(), access);
    }

    private long insertEntity(String table, String columnsAndValues, User owner) {
        return jdbcTemplate.queryForObject("INSERT INTO " + table + " " + columnsAndValues + " RETURNING id",
            Long.class, owner.getId());
    }

    private long task(String name, User owner, User assignee) {
        return jdbcTemplate.queryForObject("INSERT INTO tasks (name, user_id, assigned_to, bucket, category, "
                + "created_at, updated_at) VALUES (?, ?, ?, 'due_asap', 'call', now(), now()) RETURNING id",
            Long.class, name, owner.getId(), assignee == null ? null : assignee.getId());
    }

    private void comment(User author, String type, long commentableId, String text, Instant createdAt) {
        jdbcTemplate.update("INSERT INTO comments (user_id, commentable_id, commentable_type, comment, created_at, "
            + "updated_at) VALUES (?, ?, ?, ?, ?, ?)", author.getId(), commentableId, type, text,
            java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt));
    }

    private void clearData() {
        for (String table : new String[] {"versions", "comments", "permissions", "groups_users", "groups", "tasks",
            "leads", "contacts", "opportunities", "campaigns", "accounts", "users"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }
}
