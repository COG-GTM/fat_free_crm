package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Rails {@code CommentsController#index} scopes the commentable with {@code .my(current_user)} (Public, owned,
 * assigned or Shared-with-permission records; {@code User.my} is admin-or-self) and raises
 * {@code RecordNotFound} otherwise. Without a commentable it lists {@code Comment.accessible_by(ability)}.
 */
class CommentsAccessControlIntegrationTest extends AbstractPostgresIntegrationTest {

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
    private Account bobPrivate;
    private Account bobPublic;
    private Account bobSharedWithAlice;
    private Account bobSharedWithGroup;
    private long privateComment;
    private long publicComment;
    private long aliceUserComment;
    private long bobUserComment;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice", false);
        bob = user("bob", false);
        admin = user("admin", true);
        aliceBearer = bearer(alice);
        bobBearer = bearer(bob);
        adminBearer = bearer(admin);
        bobPrivate = account(bob, "Bob Private", "Private");
        bobPublic = account(bob, "Bob Public", "Public");
        bobSharedWithAlice = account(bob, "Bob Shared Alice", "Shared");
        bobSharedWithGroup = account(bob, "Bob Shared Group", "Shared");
        jdbcTemplate.update("INSERT INTO permissions (user_id, asset_type, asset_id, created_at, updated_at) "
            + "VALUES (?, 'Account', ?, now(), now())", alice.getId(), bobSharedWithAlice.getId());
        long group = jdbcTemplate.queryForObject(
            "INSERT INTO groups (name, created_at, updated_at) VALUES ('sales', now(), now()) RETURNING id",
            Long.class);
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", group, alice.getId());
        jdbcTemplate.update("INSERT INTO permissions (group_id, asset_type, asset_id, created_at, updated_at) "
            + "VALUES (?, 'Account', ?, now(), now())", group, bobSharedWithGroup.getId());
        privateComment = comment(bob, "Account", bobPrivate.getId(), "private note");
        publicComment = comment(alice, "Account", bobPublic.getId(), "public note");
        aliceUserComment = comment(alice, "User", alice.getId(), "about alice");
        bobUserComment = comment(bob, "User", bob.getId(), "about bob");
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void privateCommentableIsNotFoundForOtherUsersButVisibleToOwnerAndAdmin() throws Exception {
        comments(aliceBearer, "account_id", id(bobPrivate)).andExpect(status().isNotFound());
        assertThat(ids(comments(bobBearer, "account_id", id(bobPrivate)))).containsExactly(privateComment);
        assertThat(ids(comments(adminBearer, "account_id", id(bobPrivate)))).containsExactly(privateComment);
    }

    @Test
    void publicCommentableIsVisibleToEveryAuthenticatedUser() throws Exception {
        assertThat(ids(comments(aliceBearer, "account_id", id(bobPublic)))).containsExactly(publicComment);
        assertThat(ids(comments(bobBearer, "account_id", id(bobPublic)))).containsExactly(publicComment);
    }

    @Test
    void sharedCommentableRequiresAUserOrGroupPermission() throws Exception {
        comments(aliceBearer, "account_id", id(bobSharedWithAlice)).andExpect(status().isOk());
        comments(aliceBearer, "account_id", id(bobSharedWithGroup)).andExpect(status().isOk());

        User carol = user("carol", false);
        comments(bearer(carol), "account_id", id(bobSharedWithAlice)).andExpect(status().isNotFound());
        comments(bearer(carol), "account_id", id(bobSharedWithGroup)).andExpect(status().isNotFound());
    }

    @Test
    void userCommentableIsSelfOnlyUnlessAdmin() throws Exception {
        comments(aliceBearer, "user_id", String.valueOf(bob.getId())).andExpect(status().isNotFound());
        assertThat(ids(comments(bobBearer, "user_id", String.valueOf(bob.getId())))).containsExactly(bobUserComment);
        assertThat(ids(comments(adminBearer, "user_id", String.valueOf(bob.getId()))))
            .containsExactly(bobUserComment);
    }

    @Test
    void commentableIdsOutsideRailsIntegerRangeOrNonNumericAreNotFound() throws Exception {
        comments(bobBearer, "account_id", "0").andExpect(status().isNotFound());
        comments(bobBearer, "account_id", "-1").andExpect(status().isNotFound());
        comments(bobBearer, "account_id", "2147483648").andExpect(status().isNotFound());
        comments(bobBearer, "account_id", "abc").andExpect(status().isNotFound());
        comments(bobBearer, "account_id", "").andExpect(status().isNotFound());
    }

    @Test
    void unknownCommentableParameterIsRejected() throws Exception {
        comments(bobBearer, "widget_id", "1").andExpect(status().isBadRequest());
    }

    @Test
    void withoutACommentableRegularUsersSeeOnlyTheirOwnComments() throws Exception {
        assertThat(ids(comments(aliceBearer))).containsExactly(publicComment, aliceUserComment);
        assertThat(ids(comments(bobBearer))).containsExactly(privateComment, bobUserComment);
    }

    @Test
    void withoutACommentableAdminsSeeEveryComment() throws Exception {
        assertThat(ids(comments(adminBearer)))
            .containsExactly(privateComment, publicComment, aliceUserComment, bobUserComment);
    }

    @Test
    void commentableWithoutCommentsReturnsAnEmptyList() throws Exception {
        Account empty = account(bob, "Bob Empty", "Public");

        assertThat(ids(comments(aliceBearer, "account_id", id(empty)))).isEmpty();
    }

    private ResultActions comments(String bearer, String... params) throws Exception {
        var request = get("/api/v1/comments").header(HttpHeaders.AUTHORIZATION, bearer);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    private static List<Long> ids(ResultActions actions) throws Exception {
        String body = actions.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode node = JSON.readTree(body);
        List<Long> ids = new ArrayList<>();
        node.forEach(comment -> ids.add(comment.get("id").asLong()));
        return ids;
    }

    private static String id(Account account) {
        return String.valueOf(account.getId());
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

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private long comment(User author, String commentableType, long commentableId, String text) {
        return jdbcTemplate.queryForObject("INSERT INTO comments (user_id, commentable_id, commentable_type, "
                + "comment, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now()) RETURNING id", Long.class,
            author.getId(), commentableId, commentableType, text);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM permissions");
        jdbcTemplate.update("DELETE FROM groups_users");
        jdbcTemplate.update("DELETE FROM groups");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
