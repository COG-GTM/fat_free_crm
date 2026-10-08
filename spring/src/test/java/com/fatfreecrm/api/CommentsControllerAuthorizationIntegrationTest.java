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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Rails {@code CommentsController#index} resolves the commentable through {@code Model.my(current_user).find},
 * so a comment list on a record the caller cannot see is a 404 (never a 403, never an empty list) for every
 * CRM entity type and for both Private and Shared visibility, while admins see everything.
 */
class CommentsControllerAuthorizationIntegrationTest extends AbstractPostgresIntegrationTest {

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
    private long alicePublicAccount;
    private long bobPrivateAccount;
    private long bobSharedAccount;
    private long bobPrivateLead;
    private long bobPublicOpportunity;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice", false);
        bob = user("bob", false);
        admin = user("admin", true);
        aliceBearer = bearer(alice);
        bobBearer = bearer(bob);
        adminBearer = bearer(admin);

        alicePublicAccount = account(alice, "Alice Public", "Public");
        bobPrivateAccount = account(bob, "Bob Private", "Private");
        bobSharedAccount = account(bob, "Bob Shared", "Shared");
        bobPrivateLead = jdbcTemplate.queryForObject("INSERT INTO leads (user_id, first_name, last_name, access, "
            + "status, created_at, updated_at) VALUES (?, 'Bob', 'Lead', 'Private', 'new', now(), now()) "
            + "RETURNING id", Long.class, bob.getId());
        bobPublicOpportunity = jdbcTemplate.queryForObject("INSERT INTO opportunities (user_id, name, access, "
            + "stage, created_at, updated_at) VALUES (?, 'Bob Deal', 'Public', 'prospecting', now(), now()) "
            + "RETURNING id", Long.class, bob.getId());

        comment(bob, "Account", alicePublicAccount, "older on alice", "now() - interval '2 hours'");
        comment(alice, "Account", alicePublicAccount, "newer on alice", "now()");
        comment(bob, "Account", bobPrivateAccount, "bob private note", "now()");
        comment(bob, "Account", bobSharedAccount, "bob shared note", "now()");
        comment(bob, "Lead", bobPrivateLead, "lead note", "now()");
        comment(bob, "Opportunity", bobPublicOpportunity, "deal note", "now()");
        comment(bob, "User", bob.getId(), "about bob", "now()");
    }

    @AfterEach
    void cleanup() {
        clearData();
    }

    @Test
    void publicAccountCommentsAreVisibleToEveryoneNewestFirst() throws Exception {
        for (String bearer : new String[] {aliceBearer, bobBearer, adminBearer}) {
            JsonNode body = getJson("/api/v1/comments", bearer, "account_id", String.valueOf(alicePublicAccount));
            assertThat(body.isArray()).isTrue();
            assertThat(body.findValuesAsText("comment")).containsExactly("newer on alice", "older on alice");
            assertThat(body.get(0).get("commentable_type").asText()).isEqualTo("Account");
            assertThat(body.get(0).get("commentable_id").asLong()).isEqualTo(alicePublicAccount);
            assertThat(body.get(0).get("user_id").asLong()).isEqualTo(alice.getId());
        }
    }

    @Test
    void privateAccountCommentsAre404ForOtherUsersButVisibleToOwnerAndAdmin() throws Exception {
        mockMvc.perform(comments("account_id", bobPrivateAccount, aliceBearer))
            .andExpect(status().isNotFound());

        assertThat(getJson("/api/v1/comments", bobBearer, "account_id", String.valueOf(bobPrivateAccount))
            .findValuesAsText("comment")).containsExactly("bob private note");
        assertThat(getJson("/api/v1/comments", adminBearer, "account_id", String.valueOf(bobPrivateAccount))
            .findValuesAsText("comment")).containsExactly("bob private note");
    }

    @Test
    void sharedAccountCommentsRequireAPermissionRowForTheCaller() throws Exception {
        mockMvc.perform(comments("account_id", bobSharedAccount, aliceBearer))
            .andExpect(status().isNotFound());

        jdbcTemplate.update("INSERT INTO permissions (user_id, asset_type, asset_id, created_at, updated_at) "
            + "VALUES (?, 'Account', ?, now(), now())", alice.getId(), bobSharedAccount);

        assertThat(getJson("/api/v1/comments", aliceBearer, "account_id", String.valueOf(bobSharedAccount))
            .findValuesAsText("comment")).containsExactly("bob shared note");
    }

    @Test
    void visibilityRulesApplyToLeadAndOpportunityCommentables() throws Exception {
        mockMvc.perform(comments("lead_id", bobPrivateLead, aliceBearer))
            .andExpect(status().isNotFound());
        assertThat(getJson("/api/v1/comments", bobBearer, "lead_id", String.valueOf(bobPrivateLead))
            .findValuesAsText("comment")).containsExactly("lead note");
        assertThat(getJson("/api/v1/comments", adminBearer, "lead_id", String.valueOf(bobPrivateLead))
            .findValuesAsText("comment")).containsExactly("lead note");

        assertThat(getJson("/api/v1/comments", aliceBearer, "opportunity_id", String.valueOf(bobPublicOpportunity))
            .findValuesAsText("comment")).containsExactly("deal note");
    }

    @Test
    void adminsCanListCommentsOnAnotherUsersRecordButRegularUsersCannot() throws Exception {
        mockMvc.perform(comments("user_id", bob.getId(), aliceBearer))
            .andExpect(status().isNotFound());
        assertThat(getJson("/api/v1/comments", adminBearer, "user_id", String.valueOf(bob.getId()))
            .findValuesAsText("comment")).containsExactly("about bob");
    }

    @Test
    void adminWithoutACommentableSeesEveryCommentWhileUsersSeeOnlyTheirOwn() throws Exception {
        assertThat(getJson("/api/v1/comments", aliceBearer).findValuesAsText("comment"))
            .containsExactly("newer on alice");
        assertThat(getJson("/api/v1/comments", adminBearer).findValuesAsText("comment"))
            .hasSize(7)
            .contains("bob private note", "lead note", "about bob", "newer on alice");
    }

    @Test
    void nonPositiveOverflowingAndUnknownIdsAre404() throws Exception {
        for (String id : new String[] {"0", "-1", "99999999999", "1.5", String.valueOf(bobPrivateAccount + 100_000)}) {
            mockMvc.perform(get("/api/v1/comments").param("account_id", id)
                    .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isNotFound());
        }
    }

    @Test
    void anonymousCallersAreRejectedBeforeVisibilityIsEvaluated() throws Exception {
        mockMvc.perform(get("/api/v1/comments").param("account_id", String.valueOf(alicePublicAccount)))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/comments"))
            .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder comments(String param, long id, String bearer) {
        return get("/api/v1/comments").param(param, String.valueOf(id)).header(HttpHeaders.AUTHORIZATION, bearer);
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

    private User user(String username, boolean admin) {
        User user = new User();
        user.setUsername(username);
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(admin);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private long account(User owner, String name, String access) {
        return jdbcTemplate.queryForObject("INSERT INTO accounts (user_id, name, access, created_at, updated_at) "
            + "VALUES (?, ?, ?, now(), now()) RETURNING id", Long.class, owner.getId(), name, access);
    }

    private void comment(User author, String type, long commentableId, String text, String createdAt) {
        jdbcTemplate.update("INSERT INTO comments (user_id, commentable_id, commentable_type, comment, created_at, "
            + "updated_at) VALUES (?, ?, ?, ?, " + createdAt + ", now())", author.getId(), commentableId, type, text);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM permissions");
        jdbcTemplate.update("DELETE FROM leads");
        jdbcTemplate.update("DELETE FROM opportunities");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
