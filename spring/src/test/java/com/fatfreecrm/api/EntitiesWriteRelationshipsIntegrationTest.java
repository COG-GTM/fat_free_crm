package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * AB-272 Phase B {@code EntitiesWriteController} — contacts, leads and opportunities: the
 * relationship side-effects Rails performs in callbacks and controllers (nested account
 * create/select/unlink, campaign counters, lead promote/reject/convert, the
 * {@code OpportunityObserver} stage transitions) plus their validation and authorization edges.
 */
class EntitiesWriteRelationshipsIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String MISSING_FIRST_NAME = "^Please specify first name.";
    private static final String MISSING_LAST_NAME = "^Please specify last name.";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private User bob;
    private String aliceBearer;
    private String bobBearer;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice", false);
        bob = user("bob", false);
        aliceBearer = bearer(alice);
        bobBearer = bearer(bob);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    // ------------------------------------------------------------------ contacts

    @Test
    void createContactWithNestedNewAccountCreatesAccountJoinRowCounterAndVersions() throws Exception {
        long contactId = create("/api/v1/contacts", aliceBearer, "{\"contact\":{\"first_name\":\"Jane\","
            + "\"last_name\":\"Doe\",\"title\":\"CTO\"},\"account\":{\"name\":\"NewCo\"}}");
        Map<String, Object> contact = row("contacts", contactId);
        assertThat(contact.get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(contact.get("assigned_to")).isEqualTo(alice.getId().intValue());
        assertThat(contact.get("access")).isEqualTo("Public");
        assertThat(contact.get("title")).isEqualTo("CTO");

        List<Map<String, Object>> accounts = jdbcTemplate.queryForList("SELECT * FROM accounts");
        assertThat(accounts).hasSize(1);
        long accountId = ((Number) accounts.get(0).get("id")).longValue();
        assertThat(accounts.get(0).get("name")).isEqualTo("NewCo");
        assertThat(accounts.get(0).get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(accounts.get(0).get("contacts_count")).isEqualTo(1);
        List<Map<String, Object>> links = jdbcTemplate.queryForList("SELECT * FROM account_contacts");
        assertThat(links).hasSize(1);
        assertThat(links.get(0).get("account_id")).isEqualTo((int) accountId);
        assertThat(links.get(0).get("contact_id")).isEqualTo((int) contactId);

        assertThat(versions("Account", accountId)).extracting(v -> v.get("event")).containsExactly("create");
        assertThat(versions("Contact", contactId)).extracting(v -> v.get("event")).containsExactly("create");
        List<Map<String, Object>> linkVersions =
            versions("AccountContact", ((Number) links.get(0).get("id")).longValue());
        assertThat(linkVersions).extracting(v -> v.get("event")).containsExactly("create");
        assertThat(linkVersions.get(0).get("related_type")).isEqualTo("Contact");
        assertThat(linkVersions.get(0).get("related_id")).isEqualTo((int) contactId);
        String contactChanges = (String) versions("Contact", contactId).get(0).get("object_changes");
        assertThat(contactChanges).contains("first_name:\n- ''\n- Jane\n").contains("title:\n-\n- CTO\n");
        assertThat(contactChanges).doesNotContain("do_not_call:");
    }

    @Test
    void createContactSelectsExistingAccountByIdOrByNameInsteadOfCreatingOne() throws Exception {
        long accountId = account("Existing", bob, "Public");
        long byId = create("/api/v1/contacts", aliceBearer, "{\"contact\":{\"first_name\":\"A\",\"last_name\":\"B\"},"
            + "\"account\":{\"id\":\"" + accountId + "\",\"name\":\"ignored\"}}");
        long byName = create("/api/v1/contacts", aliceBearer, "{\"contact\":{\"first_name\":\"C\",\"last_name\":\"D\"},"
            + "\"account\":{\"name\":\"Existing\"}}");
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT contact_id FROM account_contacts WHERE account_id = ? ORDER BY id",
            Integer.class, accountId)).containsExactly((int) byId, (int) byName);
        assertThat(row("accounts", accountId).get("contacts_count")).isEqualTo(2);
        assertThat(versions("Account", accountId)).isEmpty();
    }

    @Test
    void createContactWithBlankOrAbsentAccountCreatesNoAccountAndMissingAccountIdIs404() throws Exception {
        create("/api/v1/contacts", aliceBearer, "{\"contact\":{\"first_name\":\"A\",\"last_name\":\"B\"},"
            + "\"account\":{\"name\":\"\"}}");
        create("/api/v1/contacts", aliceBearer, "{\"contact\":{\"first_name\":\"C\",\"last_name\":\"D\"}}");
        assertThat(count("accounts")).isZero();
        assertThat(count("account_contacts")).isZero();
        mockMvc.perform(authed(post("/api/v1/contacts"), aliceBearer)
                .content("{\"contact\":{\"first_name\":\"E\",\"last_name\":\"F\"},\"account\":{\"id\":999999}}"))
            .andExpect(status().isNotFound());
        assertThat(count("contacts")).isEqualTo(2);
    }

    @Test
    void createContactValidationMessagesMirrorRails() throws Exception {
        mockMvc.perform(authed(post("/api/v1/contacts"), aliceBearer).content("{\"contact\":{}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"first_name\":[\"" + MISSING_FIRST_NAME + "\"],"
                + "\"last_name\":[\"" + MISSING_LAST_NAME + "\"]}}", JsonCompareMode.STRICT));
        mockMvc.perform(authed(post("/api/v1/contacts"), aliceBearer)
                .content("{\"contact\":{\"first_name\":\"A\",\"last_name\":\"B\",\"access\":\"Shared\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json(
                "{\"errors\":{\"access\":[\"^Please specify users with whom to share the contact.\"]}}",
                JsonCompareMode.STRICT));
        // Validation failure must also roll back the nested account that was created first.
        mockMvc.perform(authed(post("/api/v1/contacts"), aliceBearer)
                .content("{\"contact\":{\"first_name\":\"\",\"last_name\":\"B\"},\"account\":{\"name\":\"Orphan\"}}"))
            .andExpect(status().isUnprocessableEntity());
        assertThat(count("contacts")).isZero();
        assertThat(count("accounts")).isZero();
        assertThat(count("versions")).isZero();
    }

    @Test
    void updateContactWithoutAccountKeyUnlinksAndWithAccountIdRelinks() throws Exception {
        long oldAccount = account("Old", alice, "Public");
        long newAccount = account("New", alice, "Public");
        long contactId = contact("Jane", "Doe", alice);
        long oldLink = link("account_contacts", "account_id", oldAccount, "contact_id", contactId);
        jdbcTemplate.update("UPDATE accounts SET contacts_count = 1 WHERE id = ?", oldAccount);

        // ContactsController#update → @contact.update_with_account_and_permissions: no :account → unlink.
        mockMvc.perform(authed(put("/api/v1/contacts/" + contactId), aliceBearer)
                .content("{\"contact\":{\"title\":\"VP\"}}"))
            .andExpect(status().isNoContent());
        assertThat(count("account_contacts")).isZero();
        assertThat(row("accounts", oldAccount).get("contacts_count")).isEqualTo(0);
        assertThat(versions("AccountContact", oldLink)).extracting(v -> v.get("event")).containsExactly("destroy");
        assertThat((String) versions("Contact", contactId).get(0).get("object_changes")).contains("title:\n-\n- VP\n");

        mockMvc.perform(authed(put("/api/v1/contacts/" + contactId), aliceBearer)
                .content("{\"contact\":{\"title\":\"VP\"},\"account\":{\"id\":" + newAccount + "}}"))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForList("SELECT account_id FROM account_contacts", Integer.class))
            .containsExactly((int) newAccount);
        assertThat(row("accounts", newAccount).get("contacts_count")).isEqualTo(1);
        // Nothing changed on the contact itself → no second Contact version.
        assertThat(versions("Contact", contactId)).hasSize(1);

        mockMvc.perform(authed(put("/api/v1/contacts/" + contactId), bobBearer)
                .content("{\"contact\":{\"title\":\"Hacker\"}}"))
            .andExpect(status().isNoContent()); // Public contact → any user may update
        assertThat(row("contacts", contactId).get("title")).isEqualTo("Hacker");
    }

    @Test
    void privateContactIsForbiddenToOtherUsersAndDestroyRemovesJoinRows() throws Exception {
        long accountId = account("Acme", alice, "Public");
        long contactId = contact("Jane", "Doe", alice);
        jdbcTemplate.update("UPDATE contacts SET access = 'Private' WHERE id = ?", contactId);
        long opportunityId = opportunity("Deal", alice, null, "prospecting", null, null);
        long accountLink = link("account_contacts", "account_id", accountId, "contact_id", contactId);
        link("contact_opportunities", "contact_id", contactId, "opportunity_id", opportunityId);
        jdbcTemplate.update("UPDATE accounts SET contacts_count = 1 WHERE id = ?", accountId);
        long taskId = task("Call Jane", alice, "Contact", contactId);

        mockMvc.perform(authed(put("/api/v1/contacts/" + contactId), bobBearer).content("{\"contact\":{}}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(authed(delete("/api/v1/contacts/" + contactId), bobBearer)).andExpect(status().isForbidden());
        mockMvc.perform(authed(put("/api/v1/contacts/" + contactId + "/attach"), bobBearer)
            .param("assets", "tasks").param("asset_id", String.valueOf(taskId))).andExpect(status().isForbidden());
        assertThat(count("contacts")).isEqualTo(1);

        mockMvc.perform(authed(delete("/api/v1/contacts/" + contactId), aliceBearer)).andExpect(status().isNoContent());
        assertThat(count("contacts")).isZero();
        assertThat(count("account_contacts")).isZero();
        assertThat(count("contact_opportunities")).isZero();
        assertThat(count("tasks")).isZero();
        assertThat(count("opportunities")).isEqualTo(1);
        assertThat(row("accounts", accountId).get("contacts_count")).isEqualTo(0);
        assertThat(versions("Contact", contactId)).extracting(v -> v.get("event")).containsExactly("destroy");
        assertThat(versions("AccountContact", accountLink)).extracting(v -> v.get("event")).containsExactly("destroy");
        assertThat(versions("Task", taskId)).extracting(v -> v.get("event")).containsExactly("destroy");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM versions WHERE item_type = 'ContactOpportunity'",
            Long.class)).isZero();
    }

    // ------------------------------------------------------------------ leads

    @Test
    void createLeadInCampaignIncrementsLeadsCountAndMissingCampaignIs404() throws Exception {
        long campaignId = campaign("Camp", alice, "Public");
        long leadId = create("/api/v1/leads", aliceBearer, "{\"lead\":{\"first_name\":\"Lee\",\"last_name\":\"Dd\","
            + "\"status\":\"new\",\"rating\":2},\"campaign\":\"" + campaignId + "\"}");
        Map<String, Object> lead = row("leads", leadId);
        assertThat(lead.get("campaign_id")).isEqualTo((int) campaignId);
        assertThat(lead.get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(lead.get("access")).isEqualTo("Public");
        assertThat(lead.get("status")).isEqualTo("new");
        assertThat(row("campaigns", campaignId).get("leads_count")).isEqualTo(1);
        String changes = (String) versions("Lead", leadId).get(0).get("object_changes");
        assertThat(changes).contains("campaign_id:\n-\n- " + campaignId + "\n").contains("rating:\n- 0\n- 2\n");
        assertThat(versions("Campaign", campaignId)).isEmpty();

        mockMvc.perform(authed(post("/api/v1/leads"), aliceBearer)
                .content("{\"lead\":{\"first_name\":\"X\",\"last_name\":\"Y\"},\"campaign\":999999}"))
            .andExpect(status().isNotFound());
        assertThat(count("leads")).isEqualTo(1);
    }

    @Test
    void createLeadWithCampaignAccessCopiesTheCampaignAccessAndPermissionRows() throws Exception {
        long campaignId = campaign("Shared camp", alice, "Shared");
        permission("Campaign", campaignId, bob);
        long leadId = create("/api/v1/leads", aliceBearer, "{\"lead\":{\"first_name\":\"Lee\",\"last_name\":\"Dd\","
            + "\"access\":\"Campaign\"},\"campaign\":" + campaignId + "}");
        assertThat(row("leads", leadId).get("access")).isEqualTo("Shared");
        assertThat(jdbcTemplate.queryForList(
            "SELECT user_id FROM permissions WHERE asset_type = 'Lead' AND asset_id = ?", Integer.class, leadId))
            .containsExactly(bob.getId().intValue());
        // Bob is now inside the lead's shared scope.
        mockMvc.perform(authed(put("/api/v1/leads/" + leadId), bobBearer).content("{\"lead\":{\"title\":\"x\"}}"))
            .andExpect(status().isNoContent());
    }

    @Test
    void createLeadValidationMessagesMirrorRails() throws Exception {
        mockMvc.perform(authed(post("/api/v1/leads"), aliceBearer).content("{\"lead\":{\"status\":\"bogus\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"first_name\":[\"" + MISSING_FIRST_NAME + "\"],"
                + "\"last_name\":[\"" + MISSING_LAST_NAME + "\"],\"status\":[\"is not included in the list\"]}}",
                JsonCompareMode.STRICT));
        mockMvc.perform(authed(post("/api/v1/leads"), aliceBearer)
                .content("{\"lead\":{\"first_name\":\"A\",\"last_name\":\"B\",\"access\":\"Shared\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json(
                "{\"errors\":{\"access\":[\"^Please specify users with whom to share the lead.\"]}}",
                JsonCompareMode.STRICT));
        assertThat(count("leads")).isZero();
    }

    @Test
    void updateLeadCampaignChangeMovesTheLeadsCounterBetweenCampaigns() throws Exception {
        long from = campaign("From", alice, "Public");
        long to = campaign("To", alice, "Public");
        jdbcTemplate.update("UPDATE campaigns SET leads_count = 1 WHERE id = ?", from);
        long leadId = lead("Lee", "Dd", alice, from, "Public");

        mockMvc.perform(authed(put("/api/v1/leads/" + leadId), aliceBearer)
                .content("{\"lead\":{\"campaign_id\":" + to + "}}"))
            .andExpect(status().isNoContent());
        assertThat(row("campaigns", from).get("leads_count")).isEqualTo(0);
        assertThat(row("campaigns", to).get("leads_count")).isEqualTo(1);
        assertThat(row("leads", leadId).get("campaign_id")).isEqualTo((int) to);
        assertThat((String) versions("Lead", leadId).get(0).get("object_changes"))
            .contains("campaign_id:\n- " + from + "\n- " + to + "\n");

        mockMvc.perform(authed(put("/api/v1/leads/" + leadId), aliceBearer)
                .content("{\"lead\":{\"campaign_id\":null}}"))
            .andExpect(status().isNoContent());
        assertThat(row("campaigns", to).get("leads_count")).isEqualTo(0);
        assertThat(row("leads", leadId).get("campaign_id")).isNull();
    }

    @Test
    void rejectLeadWritesStatusUpdateAndRejectActivityVersions() throws Exception {
        long leadId = lead("Lee", "Dd", alice, null, "Public");
        mockMvc.perform(authed(put("/api/v1/leads/" + leadId + "/reject"), aliceBearer))
            .andExpect(status().isNoContent());
        assertThat(row("leads", leadId).get("status")).isEqualTo("rejected");
        List<Map<String, Object>> versions = versions("Lead", leadId);
        assertThat(versions).extracting(v -> v.get("event")).containsExactly("update", "reject");
        assertThat((String) versions.get(0).get("object_changes")).contains("status:\n- new\n- rejected\n");
        assertThat(versions.get(1).get("whodunnit")).isEqualTo(alice.getId().toString());
        assertThat(versions.get(1).get("object_changes")).isNull();

        long privateLead = lead("Bob", "Only", bob, null, "Private");
        mockMvc.perform(authed(put("/api/v1/leads/" + privateLead + "/reject"), aliceBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(authed(put("/api/v1/leads/999999/reject"), aliceBearer)).andExpect(status().isNotFound());
        assertThat(row("leads", privateLead).get("status")).isEqualTo("new");
    }

    @Test
    void convertLeadRendersTheLeadAndRespectsAccessScope() throws Exception {
        long leadId = lead("Lee", "Dd", alice, null, "Public");
        mockMvc.perform(authed(get("/api/v1/leads/" + leadId + "/convert"), aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(leadId))
            .andExpect(jsonPath("$.first_name").value("Lee"));
        long privateLead = lead("Bob", "Only", bob, null, "Private");
        mockMvc.perform(authed(get("/api/v1/leads/" + privateLead + "/convert"), aliceBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/leads/999999/convert"), aliceBearer)).andExpect(status().isNotFound());
        assertThat(count("versions")).isZero();
    }

    @Test
    void promoteLeadCreatesAccountOpportunityAndContactThenConvertsTheLead() throws Exception {
        long campaignId = campaign("Camp", alice, "Public");
        long leadId = lead("Lee", "Dd", alice, campaignId, "Public");
        jdbcTemplate.update("UPDATE leads SET title = 'CEO', email = 'lee@example.com', phone = '555' WHERE id = ?",
            leadId);
        mockMvc.perform(authed(patch("/api/v1/leads/" + leadId + "/promote"), aliceBearer)
                .content("{\"account\":{\"name\":\"PromoCo\"},\"opportunity\":{\"name\":\"Big deal\","
                    + "\"stage\":\"prospecting\",\"amount\":\"1000\",\"campaign_id\":" + campaignId + "},"
                    + "\"access\":\"Public\"}"))
            .andExpect(status().isNoContent());

        Map<String, Object> account = jdbcTemplate.queryForMap("SELECT * FROM accounts");
        assertThat(account.get("name")).isEqualTo("PromoCo");
        assertThat(account.get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(account.get("contacts_count")).isEqualTo(1);
        assertThat(account.get("opportunities_count")).isEqualTo(1);
        Map<String, Object> opportunity = jdbcTemplate.queryForMap("SELECT * FROM opportunities");
        assertThat(opportunity.get("name")).isEqualTo("Big deal");
        assertThat(opportunity.get("campaign_id")).isEqualTo((int) campaignId);
        assertThat(((BigDecimal) opportunity.get("amount"))).isEqualByComparingTo("1000");
        Map<String, Object> contact = jdbcTemplate.queryForMap("SELECT * FROM contacts");
        assertThat(contact.get("first_name")).isEqualTo("Lee");
        assertThat(contact.get("last_name")).isEqualTo("Dd");
        assertThat(contact.get("title")).isEqualTo("CEO");
        assertThat(contact.get("email")).isEqualTo("lee@example.com");
        assertThat(contact.get("phone")).isEqualTo("555");
        assertThat(contact.get("lead_id")).isEqualTo((int) leadId);
        assertThat(contact.get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(contact.get("access")).isEqualTo("Public");
        assertThat(count("account_contacts")).isEqualTo(1);
        assertThat(count("account_opportunities")).isEqualTo(1);
        assertThat(count("contact_opportunities")).isEqualTo(1);
        assertThat(row("campaigns", campaignId).get("opportunities_count")).isEqualTo(1);

        assertThat(row("leads", leadId).get("status")).isEqualTo("converted");
        List<Map<String, Object>> leadVersions = versions("Lead", leadId);
        assertThat(leadVersions).extracting(v -> v.get("event")).containsExactly("update");
        assertThat((String) leadVersions.get(0).get("object_changes")).contains("status:\n- new\n- converted\n");
        assertThat(versions("Account", ((Number) account.get("id")).longValue()))
            .extracting(v -> v.get("event")).containsExactly("create");
        assertThat(versions("Opportunity", ((Number) opportunity.get("id")).longValue()))
            .extracting(v -> v.get("event")).containsExactly("create");
        assertThat(versions("Contact", ((Number) contact.get("id")).longValue()))
            .extracting(v -> v.get("event")).containsExactly("create");
    }

    @Test
    void promoteLeadWithExistingAccountAndNoOpportunityOnlyCreatesTheContact() throws Exception {
        long accountId = account("Known", bob, "Public");
        long leadId = lead("Lee", "Dd", alice, null, "Public");
        mockMvc.perform(authed(put("/api/v1/leads/" + leadId + "/promote"), aliceBearer)
                .content("{\"account\":{\"id\":" + accountId + "},\"opportunity\":{\"name\":\"\"},"
                    + "\"access\":\"Public\"}"))
            .andExpect(status().isNoContent());
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(count("opportunities")).isZero();
        assertThat(count("contacts")).isEqualTo(1);
        assertThat(count("account_contacts")).isEqualTo(1);
        assertThat(row("accounts", accountId).get("contacts_count")).isEqualTo(1);
        // Contact owner falls back to the selected account's owner (lead.promote passes account.user).
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM contacts").get("user_id"))
            .isEqualTo(bob.getId().intValue());
        assertThat(row("leads", leadId).get("status")).isEqualTo("converted");
    }

    @Test
    void promoteLeadWithInvalidAccountIs500AndPersistsNothing() throws Exception {
        long leadId = lead("Lee", "Dd", alice, null, "Public");
        mockMvc.perform(authed(put("/api/v1/leads/" + leadId + "/promote"), aliceBearer)
                .content("{\"account\":{\"name\":\"Bad\",\"access\":\"Shared\"},\"opportunity\":{\"name\":\"Deal\"},"
                    + "\"access\":\"Public\"}"))
            .andExpect(status().isInternalServerError());
        assertThat(count("accounts")).isZero();
        assertThat(count("opportunities")).isZero();
        assertThat(count("contacts")).isZero();
        assertThat(row("leads", leadId).get("status")).isEqualTo("new");
        assertThat(count("versions")).isZero();
    }

    @Test
    void promoteLeadWithUnsaveableContactKeepsAccountAndOpportunityLikeRailsPartialCommit() throws Exception {
        // The lead's blank last name makes the promoted contact invalid; Rails has already saved
        // the account and the opportunity by then and never wraps promote in a transaction.
        long leadId = lead("Lee", "", alice, null, "Public");
        mockMvc.perform(authed(put("/api/v1/leads/" + leadId + "/promote"), aliceBearer)
                .content("{\"account\":{\"name\":\"Kept\"},\"opportunity\":{\"name\":\"Kept deal\"},"
                    + "\"access\":\"Public\"}"))
            .andExpect(status().isInternalServerError());
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(count("opportunities")).isEqualTo(1);
        assertThat(count("account_opportunities")).isEqualTo(1);
        assertThat(count("contacts")).isZero();
        assertThat(count("contact_opportunities")).isZero();
        assertThat(row("leads", leadId).get("status")).isEqualTo("new");
        assertThat(versions("Lead", leadId)).isEmpty();
    }

    @Test
    void promoteOtherUsersPrivateLeadIsForbiddenAndMissingLeadIs404() throws Exception {
        long privateLead = lead("Bob", "Only", bob, null, "Private");
        mockMvc.perform(authed(put("/api/v1/leads/" + privateLead + "/promote"), aliceBearer)
                .content("{\"account\":{\"name\":\"Steal\"},\"opportunity\":{},\"access\":\"Public\"}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(authed(put("/api/v1/leads/999999/promote"), aliceBearer)
                .content("{\"account\":{\"name\":\"Nope\"},\"opportunity\":{},\"access\":\"Public\"}"))
            .andExpect(status().isNotFound());
        assertThat(count("accounts")).isZero();
        assertThat(count("contacts")).isZero();
    }

    @Test
    void destroyLeadNullifiesContactLeadIdAndDecrementsTheCampaign() throws Exception {
        long campaignId = campaign("Camp", alice, "Public");
        jdbcTemplate.update("UPDATE campaigns SET leads_count = 1 WHERE id = ?", campaignId);
        long leadId = lead("Lee", "Dd", alice, campaignId, "Public");
        long contactId = contact("Lee", "Dd", alice);
        jdbcTemplate.update("UPDATE contacts SET lead_id = ? WHERE id = ?", leadId, contactId);
        mockMvc.perform(authed(delete("/api/v1/leads/" + leadId), aliceBearer)).andExpect(status().isNoContent());
        assertThat(count("leads")).isZero();
        assertThat(row("contacts", contactId).get("lead_id")).isNull();
        assertThat(row("campaigns", campaignId).get("leads_count")).isEqualTo(0);
        assertThat(versions("Lead", leadId)).extracting(v -> v.get("event")).containsExactly("destroy");
        assertThat(versions("Contact", contactId)).extracting(v -> v.get("event")).containsExactly("update");
        assertThat((String) versions("Contact", contactId).get(0).get("object_changes"))
            .contains("lead_id:\n- " + leadId + "\n-\n");
    }

    // ------------------------------------------------------------------ opportunities

    @Test
    void createOpportunityWithoutAccountKeyIs500LikeRailsNoMethodError() throws Exception {
        mockMvc.perform(authed(post("/api/v1/opportunities"), aliceBearer)
                .content("{\"opportunity\":{\"name\":\"Deal\",\"stage\":\"prospecting\"}}"))
            .andExpect(status().isInternalServerError());
        assertThat(count("opportunities")).isZero();
    }

    @Test
    void createOpportunityLinksNewAccountCampaignAndContactWithCounters() throws Exception {
        long campaignId = campaign("Camp", alice, "Public");
        long contactId = contact("Jane", "Doe", alice);
        long id = create("/api/v1/opportunities", aliceBearer, "{\"opportunity\":{\"name\":\"Deal\","
            + "\"stage\":\"prospecting\",\"amount\":\"2500.00\",\"probability\":40},\"account\":{\"name\":\"OppCo\"},"
            + "\"campaign\":" + campaignId + ",\"contact\":\"" + contactId + "\"}");
        Map<String, Object> opportunity = row("opportunities", id);
        assertThat(opportunity.get("campaign_id")).isEqualTo((int) campaignId);
        assertThat(opportunity.get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(opportunity.get("probability")).isEqualTo(40);
        Map<String, Object> account = jdbcTemplate.queryForMap("SELECT * FROM accounts");
        assertThat(account.get("name")).isEqualTo("OppCo");
        assertThat(account.get("opportunities_count")).isEqualTo(1);
        assertThat(row("campaigns", campaignId).get("opportunities_count")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT contact_id FROM contact_opportunities WHERE opportunity_id = ?",
            Integer.class, id)).containsExactly((int) contactId);
        List<Map<String, Object>> links = jdbcTemplate.queryForList("SELECT * FROM account_opportunities");
        assertThat(links).hasSize(1);
        List<Map<String, Object>> linkVersions = versions("AccountOpportunity",
            ((Number) links.get(0).get("id")).longValue());
        assertThat(linkVersions).extracting(v -> v.get("event")).containsExactly("create");
        assertThat(linkVersions.get(0).get("related_type")).isNull(); // Rails has_paper_trail without meta
        assertThat(versions("Opportunity", id)).extracting(v -> v.get("event")).containsExactly("create");
        assertThat((String) versions("Opportunity", id).get(0).get("object_changes"))
            .contains("amount:\n-\n- 2500").contains("probability:\n-\n- 40\n");

        create("/api/v1/opportunities", aliceBearer,
            "{\"opportunity\":{\"name\":\"Bare\",\"stage\":\"prospecting\"},\"account\":{}}");
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(count("account_opportunities")).isEqualTo(1);
    }

    @Test
    void createOpportunityValidationMessagesMirrorRails() throws Exception {
        mockMvc.perform(authed(post("/api/v1/opportunities"), aliceBearer)
                .content("{\"opportunity\":{\"stage\":\"bogus\"},\"account\":{}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"name\":[\"^Please specify opportunity name.\"],"
                + "\"stage\":[\"is not included in the list\"]}}", JsonCompareMode.STRICT));
        mockMvc.perform(authed(post("/api/v1/opportunities"), aliceBearer)
                .content("{\"opportunity\":{\"name\":\"S\",\"access\":\"Shared\"},"
                    + "\"account\":{\"name\":\"Rolled back\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json(
                "{\"errors\":{\"access\":[\"^Please specify users with whom to share the opportunity.\"]}}",
                JsonCompareMode.STRICT));
        assertThat(count("opportunities")).isZero();
        assertThat(count("accounts")).isZero();
    }

    @Test
    void winningAnOpportunitySetsProbability100AddsCampaignRevenueAndLogsWon() throws Exception {
        long campaignId = campaign("Camp", alice, "Public");
        long id = opportunity("Deal", alice, campaignId, "prospecting", new BigDecimal("1000"), new BigDecimal("100"));
        mockMvc.perform(authed(put("/api/v1/opportunities/" + id), aliceBearer)
                .content("{\"opportunity\":{\"stage\":\"won\"}}"))
            .andExpect(status().isNoContent());
        Map<String, Object> opportunity = row("opportunities", id);
        assertThat(opportunity.get("stage")).isEqualTo("won");
        assertThat(opportunity.get("probability")).isEqualTo(100);
        assertThat((BigDecimal) row("campaigns", campaignId).get("revenue")).isEqualByComparingTo("900");

        List<Map<String, Object>> versions = versions("Opportunity", id);
        assertThat(versions).extracting(v -> v.get("event")).containsExactly("update", "update", "won");
        assertThat((String) versions.get(0).get("object_changes")).contains("stage:\n- prospecting\n- won\n");
        assertThat((String) versions.get(1).get("object_changes")).contains("probability:\n-\n- 100\n");
        assertThat(versions.get(2).get("object_changes")).isNull();
        List<Map<String, Object>> campaignVersions = versions("Campaign", campaignId);
        assertThat(campaignVersions).extracting(v -> v.get("event")).containsExactly("update");
        assertThat((String) campaignVersions.get(0).get("object_changes")).contains("revenue:\n-\n- 900");
    }

    @Test
    void unwinningSubtractsTheOriginalRevenueAndLosingSetsProbabilityZero() throws Exception {
        long campaignId = campaign("Camp", alice, "Public");
        jdbcTemplate.update("UPDATE campaigns SET revenue = 900 WHERE id = ?", campaignId);
        long won = opportunity("Won", alice, campaignId, "won", new BigDecimal("1000"), new BigDecimal("100"));
        jdbcTemplate.update("UPDATE opportunities SET probability = 100 WHERE id = ?", won);
        // won → lost: revenue -= (original amount − original discount); probability untouched (elsif chain).
        mockMvc.perform(authed(put("/api/v1/opportunities/" + won), aliceBearer)
                .content("{\"opportunity\":{\"stage\":\"lost\",\"amount\":\"5\"}}"))
            .andExpect(status().isNoContent());
        assertThat((BigDecimal) row("campaigns", campaignId).get("revenue")).isEqualByComparingTo("0");
        assertThat(row("opportunities", won).get("probability")).isEqualTo(100);
        assertThat(versions("Opportunity", won)).extracting(v -> v.get("event")).containsExactly("update");

        long open = opportunity("Open", alice, campaignId, "prospecting", null, null);
        jdbcTemplate.update("UPDATE opportunities SET probability = 50 WHERE id = ?", open);
        mockMvc.perform(authed(put("/api/v1/opportunities/" + open), aliceBearer)
                .content("{\"opportunity\":{\"stage\":\"lost\"}}"))
            .andExpect(status().isNoContent());
        assertThat(row("opportunities", open).get("probability")).isEqualTo(0);
        assertThat((BigDecimal) row("campaigns", campaignId).get("revenue")).isEqualByComparingTo("0");
        assertThat(versions("Opportunity", open)).extracting(v -> v.get("event")).containsExactly("update", "update");
        assertThat((String) versions("Opportunity", open).get(1).get("object_changes"))
            .contains("probability:\n- 50\n- 0\n");
    }

    @Test
    void updateOpportunityWithBlankAccountIdUnlinksTheAccount() throws Exception {
        long accountId = account("Acme", alice, "Public");
        long id = opportunity("Deal", alice, null, "prospecting", null, null);
        long linkId = link("account_opportunities", "account_id", accountId, "opportunity_id", id);
        jdbcTemplate.update("UPDATE accounts SET opportunities_count = 1 WHERE id = ?", accountId);
        mockMvc.perform(authed(put("/api/v1/opportunities/" + id), aliceBearer)
                .content("{\"opportunity\":{\"name\":\"Deal\"},\"account\":{\"id\":\"\",\"name\":\"\"}}"))
            .andExpect(status().isNoContent());
        assertThat(count("account_opportunities")).isZero();
        assertThat(row("accounts", accountId).get("opportunities_count")).isEqualTo(0);
        assertThat(versions("AccountOpportunity", linkId)).extracting(v -> v.get("event")).containsExactly("destroy");
        mockMvc.perform(authed(put("/api/v1/opportunities/" + id), bobBearer)
                .content("{\"opportunity\":{\"name\":\"Deal\"}}"))
            .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE opportunities SET access = 'Private' WHERE id = ?", id);
        mockMvc.perform(authed(put("/api/v1/opportunities/" + id), bobBearer)
                .content("{\"opportunity\":{\"name\":\"Deal\"}}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(authed(delete("/api/v1/opportunities/" + id), bobBearer)).andExpect(status().isForbidden());
    }

    @Test
    void destroyOpportunityDecrementsAccountAndCampaignCountersAndDropsJoinRows() throws Exception {
        long accountId = account("Acme", alice, "Public");
        long campaignId = campaign("Camp", alice, "Public");
        long contactId = contact("Jane", "Doe", alice);
        long id = opportunity("Deal", alice, campaignId, "prospecting", null, null);
        long linkId = link("account_opportunities", "account_id", accountId, "opportunity_id", id);
        link("contact_opportunities", "contact_id", contactId, "opportunity_id", id);
        jdbcTemplate.update("UPDATE accounts SET opportunities_count = 1 WHERE id = ?", accountId);
        jdbcTemplate.update("UPDATE campaigns SET opportunities_count = 1 WHERE id = ?", campaignId);
        mockMvc.perform(authed(delete("/api/v1/opportunities/" + id), aliceBearer)).andExpect(status().isNoContent());
        assertThat(count("opportunities")).isZero();
        assertThat(count("account_opportunities")).isZero();
        assertThat(count("contact_opportunities")).isZero();
        assertThat(count("contacts")).isEqualTo(1);
        assertThat(row("accounts", accountId).get("opportunities_count")).isEqualTo(0);
        assertThat(row("campaigns", campaignId).get("opportunities_count")).isEqualTo(0);
        assertThat(versions("Opportunity", id)).extracting(v -> v.get("event")).containsExactly("destroy");
        assertThat(versions("AccountOpportunity", linkId)).extracting(v -> v.get("event")).containsExactly("destroy");
    }

    // ------------------------------------------------------------------ helpers

    private long create(String path, String bearer, String body) throws Exception {
        String response = mockMvc.perform(authed(post(path), bearer).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(response).path("id").asLong();
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
            "INSERT INTO accounts (name, user_id, assigned_to, access, contacts_count, opportunities_count,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, 0, 0, now(), now()) RETURNING id",
            Long.class, name, owner.getId(), owner.getId(), access);
    }

    private long campaign(String name, User owner, String access) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO campaigns (name, user_id, assigned_to, access, leads_count, opportunities_count,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, 0, 0, now(), now()) RETURNING id",
            Long.class, name, owner.getId(), owner.getId(), access);
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

    private long opportunity(String name, User owner, Long campaignId, String stage, BigDecimal amount,
        BigDecimal discount) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO opportunities (name, user_id, assigned_to, campaign_id, access, stage, amount, discount,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, 'Public', ?, ?, ?, now(), now()) RETURNING id",
            Long.class, name, owner.getId(), owner.getId(), campaignId, stage, amount, discount);
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
