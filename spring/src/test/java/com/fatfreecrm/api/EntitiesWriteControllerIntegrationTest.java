package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * AB-272 Phase B {@code EntitiesWriteController} — accounts and campaigns plus the shared
 * attach/discard/subscribe/unsubscribe member actions: Rails {@code EntitiesController} /
 * {@code AccountsController} / {@code CampaignsController} parity (CanCan defaults, validation
 * messages, permission rows, dependent destroys, PaperTrail versions) and the authentication /
 * authorization boundary of every write route.
 */
class EntitiesWriteControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String MISSING_ACCOUNT_NAME = "^Please specify account name.";
    private static final String SHARE_ACCOUNT = "^Please specify users with whom to share the account.";
    private static final String MISSING_CAMPAIGN_NAME = "^Please specify campaign name.";

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

    // ------------------------------------------------------------------ authentication

    @Test
    void everyEntityWriteRouteRequiresAuthentication() throws Exception {
        long accountId = account("Anon", alice, "Public");
        long leadId = lead("Anon", "Lead", alice, null, "Public");
        for (String family : List.of("accounts", "campaigns", "contacts", "leads", "opportunities")) {
            mockMvc.perform(post("/api/v1/" + family).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isUnauthorized());
            mockMvc.perform(put("/api/v1/" + family + "/" + accountId)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
            mockMvc.perform(delete("/api/v1/" + family + "/" + accountId)).andExpect(status().isUnauthorized());
            mockMvc.perform(put("/api/v1/" + family + "/" + accountId + "/attach")
                .param("assets", "tasks").param("asset_id", "1")).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/v1/" + family + "/" + accountId + "/discard")
                .param("attachment", "tasks").param("attachment_id", "1")).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/v1/" + family + "/" + accountId + "/subscribe"))
                .andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/v1/" + family + "/" + accountId + "/unsubscribe"))
                .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(put("/api/v1/leads/" + leadId + "/promote")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/leads/" + leadId + "/reject")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/leads/" + leadId + "/convert")).andExpect(status().isUnauthorized());
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(count("leads")).isEqualTo(1);
        assertThat(count("versions")).isZero();
    }

    // ------------------------------------------------------------------ accounts: create

    @Test
    void createAccountAppliesCanCanDefaultsAndWritesCreateVersion() throws Exception {
        String response = mockMvc.perform(authed(post("/api/v1/accounts"), aliceBearer)
                .content("{\"account\":{\"name\":\"Acme\",\"website\":\"acme.example\"}}"))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, Matchers.startsWith("/api/v1/accounts/")))
            .andExpect(jsonPath("$.name").value("Acme"))
            .andReturn().getResponse().getContentAsString();
        long id = idOf(response);

        Map<String, Object> row = row("accounts", id);
        assertThat(row.get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(row.get("assigned_to")).isEqualTo(alice.getId().intValue());
        assertThat(row.get("access")).isEqualTo("Public");
        assertThat(row.get("rating")).isEqualTo(0);
        assertThat(row.get("website")).isEqualTo("acme.example");

        List<Map<String, Object>> versions = versions("Account", id);
        assertThat(versions).hasSize(1);
        Map<String, Object> version = versions.get(0);
        assertThat(version.get("event")).isEqualTo("create");
        assertThat(version.get("whodunnit")).isEqualTo(alice.getId().toString());
        assertThat(version.get("object")).isNull();
        String changes = (String) version.get("object_changes");
        assertThat(changes).startsWith("---\n");
        assertThat(changes).contains("name:\n- ''\n- Acme\n");
        assertThat(changes).contains("website:\n-\n- acme.example\n");
        assertThat(changes).contains("user_id:\n-\n- " + alice.getId() + "\n");
        // Attributes equal to their column default (rating 0, access Public) are not changes.
        assertThat(changes).doesNotContain("rating:");
        assertThat(changes).doesNotContain("access:");
        assertThat(changes).doesNotContain("subscribed_users");
        assertThat(changes).doesNotContain("custom_fields");
    }

    @Test
    void createAccountHonoursExplicitOwnerAssigneeAndAccessOverDefaults() throws Exception {
        long id = create("/api/v1/accounts", aliceBearer, "{\"account\":{\"name\":\"Bob's\",\"user_id\":"
            + bob.getId() + ",\"assigned_to\":" + bob.getId() + ",\"access\":\"Private\"}}");
        Map<String, Object> row = row("accounts", id);
        assertThat(row.get("user_id")).isEqualTo(bob.getId().intValue());
        assertThat(row.get("assigned_to")).isEqualTo(bob.getId().intValue());
        assertThat(row.get("access")).isEqualTo("Private");
        assertThat((String) versions("Account", id).get(0).get("object_changes"))
            .contains("access:\n- Public\n- Private\n");
    }

    @Test
    void createAccountWithBlankNameReturnsRailsMessageAndPersistsNothing() throws Exception {
        mockMvc.perform(authed(post("/api/v1/accounts"), aliceBearer).content("{\"account\":{\"name\":\"\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json("{\"errors\":{\"name\":[\"" + MISSING_ACCOUNT_NAME + "\"]}}",
                JsonCompareMode.STRICT));
        mockMvc.perform(authed(post("/api/v1/accounts"), aliceBearer).content("{\"account\":{}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"name\":[\"" + MISSING_ACCOUNT_NAME + "\"]}}",
                JsonCompareMode.STRICT));
        assertThat(count("accounts")).isZero();
        assertThat(count("versions")).isZero();
    }

    @Test
    void createAccountDuplicateNameIsRejectedAcrossUsersLikeRequireUniqueAccountNames() throws Exception {
        account("Globex", bob, "Private");
        mockMvc.perform(authed(post("/api/v1/accounts"), aliceBearer).content("{\"account\":{\"name\":\"Globex\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"name\":[\"has already been taken\"]}}",
                JsonCompareMode.STRICT));
        assertThat(count("accounts")).isEqualTo(1);
    }

    @Test
    void createAccountValidationMessagesForCategoryRatingAndLength() throws Exception {
        mockMvc.perform(authed(post("/api/v1/accounts"), aliceBearer)
                .content("{\"account\":{\"name\":\"Cat\",\"category\":\"bogus\",\"rating\":9}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"rating\":[\"is not included in the list\"],"
                + "\"category\":[\"is not included in the list\"]}}", JsonCompareMode.STRICT));
        mockMvc.perform(authed(post("/api/v1/accounts"), aliceBearer)
                .content("{\"account\":{\"name\":\"" + "x".repeat(65) + "\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name").isArray())
            .andExpect(jsonPath("$.errors.*", Matchers.hasSize(1)));
        assertThat(count("accounts")).isZero();
    }

    @Test
    void createAccountBlankCategoryIsNullifiedBeforeSave() throws Exception {
        long id = create("/api/v1/accounts", aliceBearer, "{\"account\":{\"name\":\"NoCat\",\"category\":\"\"}}");
        assertThat(row("accounts", id).get("category")).isNull();
    }

    @Test
    void createSharedAccountWithoutUsersIs422AndWritesNoPermissionRows() throws Exception {
        mockMvc.perform(authed(post("/api/v1/accounts"), aliceBearer)
                .content("{\"account\":{\"name\":\"Orphan\",\"access\":\"Shared\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"access\":[\"" + SHARE_ACCOUNT + "\"]}}",
                JsonCompareMode.STRICT));
        assertThat(count("accounts")).isZero();
        assertThat(count("permissions")).isZero();
    }

    @Test
    void createSharedAccountWithUserIdsInsertsPermissionRowsAfterTheSave() throws Exception {
        long id = create("/api/v1/accounts", aliceBearer, "{\"account\":{\"name\":\"Shared\",\"access\":\"Shared\","
            + "\"user_ids\":[\"" + bob.getId() + "\",\"\"," + bob.getId() + "],\"group_ids\":[]}}");
        List<Map<String, Object>> permissions = jdbcTemplate.queryForList(
            "SELECT * FROM permissions WHERE asset_type = 'Account' AND asset_id = ?", id);
        assertThat(permissions).hasSize(1);
        assertThat(permissions.get(0).get("user_id")).isEqualTo(bob.getId().intValue());
        assertThat(permissions.get(0).get("group_id")).isNull();
        assertThat(row("accounts", id).get("access")).isEqualTo("Shared");
        // Bob can now update the shared account; a third user still cannot.
        mockMvc.perform(authed(put("/api/v1/accounts/" + id), bobBearer)
            .content("{\"account\":{\"name\":\"Shared by Bob\"}}")).andExpect(status().isNoContent());
        User carol = user("carol", false);
        mockMvc.perform(authed(put("/api/v1/accounts/" + id), bearer(carol))
            .content("{\"account\":{\"name\":\"Nope\"}}")).andExpect(status().isForbidden());
    }

    @Test
    void createAccountTagListWritesTaggingsWithNullTaggerAndCounterCache() throws Exception {
        long id = create("/api/v1/accounts", aliceBearer,
            "{\"account\":{\"name\":\"Tagged\",\"tag_list\":\"vip, contract, vip\"}}");
        List<Map<String, Object>> taggings = jdbcTemplate.queryForList(
            "SELECT tg.*, t.name AS tag_name, t.taggings_count FROM taggings tg JOIN tags t ON t.id = tg.tag_id"
                + " WHERE tg.taggable_type = 'Account' AND tg.taggable_id = ? ORDER BY tg.id", id);
        assertThat(taggings).extracting(t -> t.get("tag_name")).containsExactly("vip", "contract");
        assertThat(taggings).allSatisfy(t -> {
            assertThat(t.get("context")).isEqualTo("tags");
            assertThat(t.get("tagger_type")).isNull();
            assertThat(t.get("tagger_id")).isNull();
            assertThat(t.get("taggings_count")).isEqualTo(1);
        });
        assertThat((String) versions("Account", id).get(0).get("object_changes"))
            .contains("tag_list:\n- []\n- - vip\n  - contract\n");
    }

    @Test
    void createAccountCommentBodyPersistsCommentAndSubscribesAuthor() throws Exception {
        long id = create("/api/v1/accounts", aliceBearer,
            "{\"account\":{\"name\":\"Commented\"},\"comment_body\":\"hello note\"}");
        List<Map<String, Object>> comments = jdbcTemplate.queryForList(
            "SELECT * FROM comments WHERE commentable_type = 'Account' AND commentable_id = ?", id);
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).get("comment")).isEqualTo("hello note");
        assertThat(comments.get(0).get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat((String) row("accounts", id).get("subscribed_users")).contains("- " + alice.getId());
        assertThat(versions("Comment", ((Number) comments.get(0).get("id")).longValue())).hasSize(1);

        long quiet = create("/api/v1/accounts", aliceBearer,
            "{\"account\":{\"name\":\"Quiet\"},\"comment_body\":\"  \"}");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM comments WHERE commentable_id = ?", Long.class, quiet)).isZero();
    }

    // ------------------------------------------------------------------ accounts: update / destroy

    @Test
    void updateAccountWritesUpdateVersionWithBeforeAndAfterValues() throws Exception {
        long id = account("Old name", alice, "Public");
        mockMvc.perform(authed(put("/api/v1/accounts/" + id), aliceBearer)
                .content("{\"account\":{\"name\":\"Renamed\",\"rating\":3}}"))
            .andExpect(status().isNoContent());
        Map<String, Object> row = row("accounts", id);
        assertThat(row.get("name")).isEqualTo("Renamed");
        assertThat(row.get("rating")).isEqualTo(3);
        List<Map<String, Object>> versions = versions("Account", id);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).get("event")).isEqualTo("update");
        assertThat(versions.get(0).get("whodunnit")).isEqualTo(alice.getId().toString());
        assertThat((String) versions.get(0).get("object")).contains("name: Old name\n");
        String changes = (String) versions.get(0).get("object_changes");
        assertThat(changes).contains("name:\n- Old name\n- Renamed\n");
        assertThat(changes).contains("rating:\n- 0\n- 3\n");
        assertThat(changes).contains("updated_at:\n");
    }

    @Test
    void updateAccountAccessChangeDeletesPermissionsEvenWhenTheSaveFails() throws Exception {
        long id = account("Shared", alice, "Shared");
        permission("Account", id, bob);
        mockMvc.perform(authed(put("/api/v1/accounts/" + id), aliceBearer)
                .content("{\"account\":{\"access\":\"Public\",\"name\":\"\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"name\":[\"" + MISSING_ACCOUNT_NAME + "\"]}}",
                JsonCompareMode.STRICT));
        // Rails access= destroys the permission rows immediately; the failed save leaves the row.
        assertThat(count("permissions")).isZero();
        Map<String, Object> row = row("accounts", id);
        assertThat(row.get("access")).isEqualTo("Shared");
        assertThat(row.get("name")).isEqualTo("Shared");
        assertThat(versions("Account", id)).isEmpty();
    }

    @Test
    void updateSharedAccountUserIdsReplacesPermissionRows() throws Exception {
        long id = account("Shared", alice, "Shared");
        permission("Account", id, bob);
        User carol = user("carol", false);
        mockMvc.perform(authed(put("/api/v1/accounts/" + id), aliceBearer)
                .content("{\"account\":{\"access\":\"Shared\",\"user_ids\":[" + carol.getId() + "]}}"))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM permissions WHERE asset_id = ?", Integer.class, id))
            .containsExactly(carol.getId().intValue());
    }

    @Test
    void updateAndDestroyMissingAccountReturn404() throws Exception {
        mockMvc.perform(authed(put("/api/v1/accounts/999999"), aliceBearer).content("{\"account\":{\"name\":\"x\"}}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(authed(delete("/api/v1/accounts/999999"), aliceBearer)).andExpect(status().isNotFound());
        mockMvc.perform(authed(put("/api/v1/accounts/999999/attach"), aliceBearer)
            .param("assets", "tasks").param("asset_id", "1")).andExpect(status().isNotFound());
        mockMvc.perform(authed(post("/api/v1/accounts/999999/unsubscribe"), aliceBearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void otherUsersPrivateAccountIsForbiddenForEveryMemberWriteButAllowedForAdmin() throws Exception {
        long id = account("Bob private", bob, "Private");
        long task = task("Bob task", bob, null, null);
        mockMvc.perform(authed(put("/api/v1/accounts/" + id), aliceBearer).content("{\"account\":{\"name\":\"x\"}}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(authed(delete("/api/v1/accounts/" + id), aliceBearer)).andExpect(status().isForbidden());
        mockMvc.perform(authed(put("/api/v1/accounts/" + id + "/attach"), aliceBearer)
            .param("assets", "tasks").param("asset_id", String.valueOf(task))).andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/v1/accounts/" + id + "/discard"), aliceBearer)
            .param("attachment", "tasks").param("attachment_id", String.valueOf(task)))
            .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/v1/accounts/" + id + "/subscribe"), aliceBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/v1/accounts/" + id + "/unsubscribe"), aliceBearer))
            .andExpect(status().isForbidden());
        assertThat(row("accounts", id).get("name")).isEqualTo("Bob private");
        assertThat(row("tasks", task).get("asset_id")).isNull();
        assertThat(count("versions")).isZero();

        mockMvc.perform(authed(put("/api/v1/accounts/" + id), adminBearer)
                .content("{\"account\":{\"name\":\"Admin\"}}"))
            .andExpect(status().isNoContent());
        assertThat(row("accounts", id).get("name")).isEqualTo("Admin");
        // The assignee of a private record is inside Rails' accessible scope.
        jdbcTemplate.update("UPDATE accounts SET assigned_to = ? WHERE id = ?", alice.getId(), id);
        mockMvc.perform(authed(put("/api/v1/accounts/" + id), aliceBearer).content("{\"account\":{\"name\":\"Mine\"}}"))
            .andExpect(status().isNoContent());
    }

    @Test
    void destroyAccountRemovesDependentsAndWritesDestroyVersionsForEach() throws Exception {
        long id = account("Doomed", alice, "Public");
        long contactId = contact("Jane", "Doe", alice);
        long opportunityId = opportunity("Deal", alice, null);
        long accountContact = link("account_contacts", "account_id", id, "contact_id", contactId);
        long accountOpportunity = link("account_opportunities", "account_id", id, "opportunity_id", opportunityId);
        jdbcTemplate.update("UPDATE accounts SET contacts_count = 1, opportunities_count = 1 WHERE id = ?", id);
        long taskId = task("Call", alice, "Account", id);
        long commentId = comment(alice, "Account", id);
        long addressId = address("Account", id);
        tagging("Account", id, "vip");
        permission("Account", id, bob);

        mockMvc.perform(authed(delete("/api/v1/accounts/" + id), aliceBearer)).andExpect(status().isNoContent());

        assertThat(count("accounts")).isZero();
        assertThat(count("account_contacts")).isZero();
        assertThat(count("account_opportunities")).isZero();
        assertThat(count("tasks")).isZero();
        assertThat(count("comments")).isZero();
        assertThat(count("addresses")).isZero();
        assertThat(count("taggings")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT taggings_count FROM tags WHERE name = 'vip'", Integer.class))
            .isZero();
        // Contacts/opportunities survive (only the join rows are dependent: :destroy)...
        assertThat(count("contacts")).isEqualTo(1);
        assertThat(count("opportunities")).isEqualTo(1);
        // ...and so do permission rows (Rails deliberately omits dependent: :destroy on them).
        assertThat(count("permissions")).isEqualTo(1);

        Map<String, Object> destroy = versions("Account", id).get(0);
        assertThat(destroy.get("event")).isEqualTo("destroy");
        assertThat(destroy.get("whodunnit")).isEqualTo(alice.getId().toString());
        assertThat((String) destroy.get("object")).contains("name: Doomed\n");
        assertThat((String) destroy.get("object")).contains("tag_list:\n");
        assertThat((String) destroy.get("object_changes")).contains("name:\n- Doomed\n-\n");
        assertThat(versions("Task", taskId)).extracting(v -> v.get("event")).containsExactly("destroy");
        assertThat(versions("Comment", commentId)).extracting(v -> v.get("event")).containsExactly("destroy");
        assertThat(versions("Address", addressId)).extracting(v -> v.get("event")).containsExactly("destroy");
        assertThat(versions("AccountContact", accountContact)).extracting(v -> v.get("event"))
            .containsExactly("destroy");
        assertThat(versions("AccountOpportunity", accountOpportunity)).extracting(v -> v.get("event"))
            .containsExactly("destroy");
        assertThat(versions("Contact", contactId)).isEmpty();
        assertThat(versions("Opportunity", opportunityId)).isEmpty();
    }

    // ------------------------------------------------------------------ campaigns

    @Test
    void createCampaignDefaultsAndValidationMessagesMirrorRails() throws Exception {
        long id = create("/api/v1/campaigns", aliceBearer,
            "{\"campaign\":{\"name\":\"Spring\",\"status\":\"planned\",\"budget\":\"1500.50\"}}");
        Map<String, Object> row = row("campaigns", id);
        assertThat(row.get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(row.get("assigned_to")).isEqualTo(alice.getId().intValue());
        assertThat(row.get("access")).isEqualTo("Public");
        assertThat(row.get("status")).isEqualTo("planned");
        assertThat(((java.math.BigDecimal) row.get("budget"))).isEqualByComparingTo("1500.50");
        assertThat(versions("Campaign", id)).extracting(v -> v.get("event")).containsExactly("create");

        mockMvc.perform(authed(post("/api/v1/campaigns"), aliceBearer).content("{\"campaign\":{\"name\":\"\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"name\":[\"" + MISSING_CAMPAIGN_NAME + "\"]}}",
                JsonCompareMode.STRICT));
        mockMvc.perform(authed(post("/api/v1/campaigns"), aliceBearer)
                .content("{\"campaign\":{\"name\":\"Bad\",\"status\":\"bogus\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"status\":[\"is not included in the list\"]}}",
                JsonCompareMode.STRICT));
        mockMvc.perform(authed(post("/api/v1/campaigns"), aliceBearer)
                .content("{\"campaign\":{\"name\":\"Dates\",\"starts_on\":\"2030-02-01\",\"ends_on\":\"2030-01-01\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.ends_on[0]").value(
                Matchers.startsWith("^Please make sure the campaign end date is after")))
            .andExpect(jsonPath("$.errors.*", Matchers.hasSize(1)));
        mockMvc.perform(authed(post("/api/v1/campaigns"), aliceBearer)
                .content("{\"campaign\":{\"name\":\"Shared\",\"access\":\"Shared\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json(
                "{\"errors\":{\"access\":[\"^Please specify users with whom to share the campaign.\"]}}",
                JsonCompareMode.STRICT));
        assertThat(count("campaigns")).isEqualTo(1);
    }

    @Test
    void campaignNameUniquenessIsScopedToTheOwner() throws Exception {
        campaign("Dup", alice);
        mockMvc.perform(authed(post("/api/v1/campaigns"), aliceBearer).content("{\"campaign\":{\"name\":\"Dup\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"name\":[\"has already been taken\"]}}",
                JsonCompareMode.STRICT));
        mockMvc.perform(authed(post("/api/v1/campaigns"), bobBearer).content("{\"campaign\":{\"name\":\"Dup\"}}"))
            .andExpect(status().isCreated());
        assertThat(count("campaigns")).isEqualTo(2);
    }

    @Test
    void updateAndDestroyCampaignKeepLeadsAndOpportunitiesButDropDependentTasks() throws Exception {
        long id = campaign("Old", alice);
        long leadId = lead("In", "Campaign", alice, id, "Public");
        long opportunityId = opportunity("Opp", alice, id);
        long taskId = task("Plan", alice, "Campaign", id);
        mockMvc.perform(authed(put("/api/v1/campaigns/" + id), aliceBearer)
                .content("{\"campaign\":{\"name\":\"New\",\"status\":\"started\"}}"))
            .andExpect(status().isNoContent());
        assertThat((String) versions("Campaign", id).get(0).get("object_changes"))
            .contains("name:\n- Old\n- New\n").contains("status:\n-\n- started\n");
        mockMvc.perform(authed(put("/api/v1/campaigns/" + id), bobBearer).content("{\"campaign\":{\"name\":\"x\"}}"))
            .andExpect(status().isNoContent()); // Public → can :manage for every user
        jdbcTemplate.update("UPDATE campaigns SET access = 'Private' WHERE id = ?", id);
        mockMvc.perform(authed(put("/api/v1/campaigns/" + id), bobBearer).content("{\"campaign\":{\"name\":\"y\"}}"))
            .andExpect(status().isForbidden());
        assertThat(row("campaigns", id).get("name")).isEqualTo("x");

        mockMvc.perform(authed(delete("/api/v1/campaigns/" + id), aliceBearer)).andExpect(status().isNoContent());
        assertThat(count("campaigns")).isZero();
        assertThat(count("tasks")).isZero();
        assertThat(row("leads", leadId).get("campaign_id")).isEqualTo((int) id);
        assertThat(row("opportunities", opportunityId).get("campaign_id")).isEqualTo((int) id);
        assertThat(versions("Campaign", id)).extracting(v -> v.get("event"))
            .containsExactly("update", "update", "destroy");
        assertThat(versions("Task", taskId)).extracting(v -> v.get("event")).containsExactly("destroy");
    }

    // ------------------------------------------------------------------ attach / discard

    @Test
    void attachContactToAccountCreatesJoinRowCounterAndVersionIdempotently() throws Exception {
        long id = account("Acme", alice, "Public");
        long contactId = contact("Jane", "Doe", alice);
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(authed(put("/api/v1/accounts/" + id + "/attach"), aliceBearer)
                    .param("assets", "contacts").param("asset_id", String.valueOf(contactId)))
                .andExpect(status().isNoContent());
        }
        List<Map<String, Object>> links = jdbcTemplate.queryForList(
            "SELECT * FROM account_contacts WHERE account_id = ?", id);
        assertThat(links).hasSize(1);
        assertThat(links.get(0).get("contact_id")).isEqualTo((int) contactId);
        assertThat(row("accounts", id).get("contacts_count")).isEqualTo(1);
        List<Map<String, Object>> versions = versions("AccountContact", ((Number) links.get(0).get("id")).longValue());
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).get("event")).isEqualTo("create");
        assertThat(versions.get(0).get("related_type")).isEqualTo("Contact");
        assertThat(versions.get(0).get("related_id")).isEqualTo((int) contactId);
        assertThat((String) versions.get(0).get("object_changes")).contains("account_id:\n-\n- " + id + "\n");
        // AccountContact's trail ignores id/created_at/updated_at/contact_id.
        assertThat((String) versions.get(0).get("object_changes")).doesNotContain("contact_id:");
        assertThat((String) versions.get(0).get("object_changes")).doesNotContain("created_at:");
    }

    @Test
    void attachTaskMovesTheTaskAndWritesATaskUpdateVersion() throws Exception {
        long id = account("Acme", alice, "Public");
        long taskId = task("Loose", alice, null, null);
        mockMvc.perform(authed(put("/api/v1/accounts/" + id + "/attach"), aliceBearer)
                .param("assets", "tasks").param("asset_id", String.valueOf(taskId)))
            .andExpect(status().isNoContent());
        Map<String, Object> task = row("tasks", taskId);
        assertThat(task.get("asset_type")).isEqualTo("Account");
        assertThat(task.get("asset_id")).isEqualTo((int) id);
        List<Map<String, Object>> versions = versions("Task", taskId);
        assertThat(versions).extracting(v -> v.get("event")).containsExactly("update");
        assertThat((String) versions.get(0).get("object_changes")).contains("asset_id:\n-\n- " + id + "\n");
        assertThat((String) versions.get(0).get("object_changes")).contains("asset_type:\n-\n- Account\n");
    }

    @Test
    void attachUnknownOrMissingAssetReturns404AndAttachingAContactToALeadIs500() throws Exception {
        long id = account("Acme", alice, "Public");
        long leadId = lead("Lee", "Dd", alice, null, "Public");
        long contactId = contact("Jane", "Doe", alice);
        mockMvc.perform(authed(put("/api/v1/accounts/" + id + "/attach"), aliceBearer)
            .param("assets", "contacts").param("asset_id", "999999")).andExpect(status().isNotFound());
        mockMvc.perform(authed(put("/api/v1/accounts/" + id + "/attach"), aliceBearer)
            .param("assets", "widgets").param("asset_id", String.valueOf(contactId))).andExpect(status().isNotFound());
        mockMvc.perform(authed(put("/api/v1/leads/" + leadId + "/attach"), aliceBearer)
            .param("assets", "contacts").param("asset_id", String.valueOf(contactId)))
            .andExpect(status().isInternalServerError());
        assertThat(count("account_contacts")).isZero();
        assertThat(count("versions")).isZero();
    }

    @Test
    void discardContactFromAccountDeletesJoinRowWithoutCallbacksAndReturnsEntityJson() throws Exception {
        long id = account("Acme", alice, "Public");
        long contactId = contact("Jane", "Doe", alice);
        long linkId = link("account_contacts", "account_id", id, "contact_id", contactId);
        jdbcTemplate.update("UPDATE accounts SET contacts_count = 1 WHERE id = ?", id);
        mockMvc.perform(authed(post("/api/v1/accounts/" + id + "/discard"), aliceBearer)
                .param("attachment", "contacts").param("attachment_id", String.valueOf(contactId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.name").value("Acme"));
        assertThat(count("account_contacts")).isZero();
        assertThat(count("contacts")).isEqualTo(1);
        // collection.delete on a has_many :through join → no destroy callbacks, no version, no counter.
        assertThat(row("accounts", id).get("contacts_count")).isEqualTo(1);
        assertThat(versions("AccountContact", linkId)).isEmpty();
        assertThat(versions("Account", id)).isEmpty();
    }

    @Test
    void discardTaskNilsTheAssetViaUpdateAttribute() throws Exception {
        long id = account("Acme", alice, "Public");
        long taskId = task("Call", alice, "Account", id);
        mockMvc.perform(authed(post("/api/v1/accounts/" + id + "/discard"), aliceBearer)
                .param("attachment", "tasks").param("attachment_id", String.valueOf(taskId)))
            .andExpect(status().isCreated());
        Map<String, Object> task = row("tasks", taskId);
        assertThat(task.get("asset_type")).isNull();
        assertThat(task.get("asset_id")).isNull();
        assertThat(versions("Task", taskId)).extracting(v -> v.get("event")).containsExactly("update");
        assertThat((String) versions("Task", taskId).get(0).get("object_changes"))
            .contains("asset_id:\n- " + id + "\n-\n");
    }

    @Test
    void campaignAttachAndDiscardLeadMoveCounterAndWriteLeadUpdateVersions() throws Exception {
        long id = campaign("Camp", alice);
        long leadId = lead("Lee", "Dd", alice, null, "Public");
        mockMvc.perform(authed(put("/api/v1/campaigns/" + id + "/attach"), aliceBearer)
                .param("assets", "leads").param("asset_id", String.valueOf(leadId)))
            .andExpect(status().isNoContent());
        assertThat(row("leads", leadId).get("campaign_id")).isEqualTo((int) id);
        assertThat(row("campaigns", id).get("leads_count")).isEqualTo(1);
        assertThat((String) versions("Lead", leadId).get(0).get("object_changes"))
            .contains("campaign_id:\n-\n- " + id + "\n");

        mockMvc.perform(authed(post("/api/v1/campaigns/" + id + "/discard"), aliceBearer)
                .param("attachment", "leads").param("attachment_id", String.valueOf(leadId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(id));
        assertThat(row("leads", leadId).get("campaign_id")).isNull();
        assertThat(row("campaigns", id).get("leads_count")).isEqualTo(0);
        assertThat(versions("Lead", leadId)).extracting(v -> v.get("event")).containsExactly("update", "update");
        assertThat((String) versions("Lead", leadId).get(1).get("object_changes"))
            .contains("campaign_id:\n- " + id + "\n-\n");
        assertThat(versions("Campaign", id)).isEmpty();
    }

    // ------------------------------------------------------------------ subscribe / unsubscribe

    @Test
    void subscribePersistsTheSubscriberButStillAnswers500LikeRails() throws Exception {
        long id = account("Acme", alice, "Public");
        mockMvc.perform(authed(post("/api/v1/accounts/" + id + "/subscribe"), aliceBearer))
            .andExpect(status().isInternalServerError());
        assertThat((String) row("accounts", id).get("subscribed_users")).contains("- " + alice.getId());
        // subscribed_users is ignored by the trail: no version row.
        assertThat(versions("Account", id)).isEmpty();
    }

    @Test
    void unsubscribeRemovesOnlyTheCurrentUserAndReturnsEntityJson() throws Exception {
        long id = account("Acme", alice, "Public");
        jdbcTemplate.update("UPDATE accounts SET subscribed_users = ? WHERE id = ?",
            "---\n- " + alice.getId() + "\n- " + bob.getId() + "\n", id);
        mockMvc.perform(authed(post("/api/v1/accounts/" + id + "/unsubscribe"), aliceBearer))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.name").value("Acme"));
        String subscribed = (String) row("accounts", id).get("subscribed_users");
        assertThat(subscribed).contains("- " + bob.getId()).doesNotContain("- " + alice.getId());
        assertThat(versions("Account", id)).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private long create(String path, String bearer, String body) throws Exception {
        String response = mockMvc.perform(authed(post(path), bearer).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return idOf(response);
    }

    private static long idOf(String json) throws Exception {
        return JsonMapper.builder().build().readTree(json).path("id").asLong();
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String bearer) {
        return builder.header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON);
    }

    private Map<String, Object> row(String table, long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM " + table + " WHERE id = ?", id);
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

    private long account(String name, User owner, String access) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, user_id, assigned_to, access, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, now(), now()) RETURNING id",
            Long.class, name, owner.getId(), owner.getId(), access);
    }

    private long campaign(String name, User owner) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO campaigns (name, user_id, assigned_to, access, leads_count, opportunities_count,"
                + " created_at, updated_at) VALUES (?, ?, ?, 'Public', 0, 0, now(), now()) RETURNING id",
            Long.class, name, owner.getId(), owner.getId());
    }

    private long contact(String firstName, String lastName, User owner) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO contacts (first_name, last_name, user_id, assigned_to, access, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'Public', now(), now()) RETURNING id",
            Long.class, firstName, lastName, owner.getId(), owner.getId());
    }

    private long lead(String firstName, String lastName, User owner, Long campaignId, String access) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO leads (first_name, last_name, user_id, assigned_to, campaign_id, access, status,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'new', now(), now()) RETURNING id",
            Long.class, firstName, lastName, owner.getId(), owner.getId(), campaignId, access);
    }

    private long opportunity(String name, User owner, Long campaignId) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO opportunities (name, user_id, assigned_to, campaign_id, access, stage, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, 'Public', 'prospecting', now(), now()) RETURNING id",
            Long.class, name, owner.getId(), owner.getId(), campaignId);
    }

    private long link(String table, String leftColumn, long leftId, String rightColumn, long rightId) {
        return jdbcTemplate.queryForObject("INSERT INTO " + table + " (" + leftColumn + ", " + rightColumn
            + ", created_at, updated_at) VALUES (?, ?, now(), now()) RETURNING id", Long.class, leftId, rightId);
    }

    private long task(String name, User owner, String assetType, Long assetId) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO tasks (name, user_id, asset_type, asset_id, bucket, category, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'due_asap', 'call', now(), now()) RETURNING id",
            Long.class, name, owner.getId(), assetType, assetId);
    }

    private long comment(User author, String commentableType, long commentableId) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO comments (user_id, commentable_type, commentable_id, comment, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'note', now(), now()) RETURNING id",
            Long.class, author.getId(), commentableType, commentableId);
    }

    private long address(String addressableType, long addressableId) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO addresses (addressable_type, addressable_id, address_type, street1, created_at, updated_at)"
                + " VALUES (?, ?, 'Billing', '1 Main St', now(), now()) RETURNING id",
            Long.class, addressableType, addressableId);
    }

    private void tagging(String taggableType, long taggableId, String tagName) {
        long tagId = jdbcTemplate.queryForObject(
            "INSERT INTO tags (name, taggings_count) VALUES (?, 1) RETURNING id", Long.class, tagName);
        jdbcTemplate.update("INSERT INTO taggings (tag_id, taggable_type, taggable_id, context, created_at)"
            + " VALUES (?, ?, ?, 'tags', now())", tagId, taggableType, taggableId);
    }

    private void permission(String assetType, long assetId, User user) {
        jdbcTemplate.update("INSERT INTO permissions (user_id, asset_type, asset_id, created_at, updated_at)"
            + " VALUES (?, ?, ?, now(), now())", user.getId(), assetType, assetId);
    }

    private void clearData() {
        for (String table : List.of("versions", "comments", "tasks", "taggings", "tags", "permissions",
            "addresses", "account_contacts", "account_opportunities", "contact_opportunities", "contacts",
            "leads", "opportunities", "campaigns", "accounts", "users")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }
}
