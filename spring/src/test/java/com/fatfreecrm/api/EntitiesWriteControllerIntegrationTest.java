package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;

/**
 * Contacts, opportunities and campaigns writes plus the shared {@code EntitiesController}
 * member actions (attach/discard/subscribe/unsubscribe), pinned to the Rails models:
 * {@code save_account}/{@code create_or_select_for}, {@code save_with_account_and_permissions},
 * {@code OpportunityObserver} revenue/probability side effects, campaign counter callbacks and the
 * no-callback join deletes of {@code discard!}.
 */
class EntitiesWriteControllerIntegrationTest extends AbstractEntityWriteIntegrationTest {

    // ------------------------------------------------------------------ contacts

    @Test
    void contactCreateWithNestedAccountNameCreatesTheAccountAndLinksIt() throws Exception {
        long id = create("/api/v1/contacts", aliceBearer, "{\"contact\":{\"first_name\":\"Con\","
            + "\"last_name\":\"Tact\",\"title\":\"CTO\"},\"account\":{\"name\":\"New Co\"}}");
        Map<String, Object> account = jdbcTemplate.queryForMap(
            "SELECT * FROM accounts WHERE name = 'New Co'");
        long accountId = ((Number) account.get("id")).longValue();
        assertThat(account.get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(count("account_contacts", "account_id = ? AND contact_id = ?", accountId, id))
            .isEqualTo(1);
        assertThat(counter("accounts", "contacts_count", accountId)).isEqualTo(1);
        assertThat(row("contacts", id).get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(events("Account", accountId)).containsExactly("create");
        assertThat(events("Contact", id)).containsExactly("create");
        assertThat(count("versions", "item_type = 'AccountContact' AND event = 'create'"))
            .isEqualTo(1);
    }

    @Test
    void contactCreateWithExistingAccountNameSelectsItRegardlessOfScope() throws Exception {
        long accountId = account("Bob Private Co", bob, "Private");
        long id = create("/api/v1/contacts", aliceBearer, "{\"contact\":{\"first_name\":\"Con\","
            + "\"last_name\":\"Tact\"},\"account\":{\"name\":\"Bob Private Co\"}}");
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(count("account_contacts", "account_id = ? AND contact_id = ?", accountId, id))
            .isEqualTo(1);
        assertThat(counter("accounts", "contacts_count", accountId)).isEqualTo(1);
    }

    @Test
    void contactCreateWithOpportunityIdLinksWithoutAVersion() throws Exception {
        long opportunityId = opportunity("Deal", alice, "Public", null);
        long id = create("/api/v1/contacts", aliceBearer, "{\"contact\":{\"first_name\":\"Con\","
            + "\"last_name\":\"Tact\"},\"account\":{\"name\":\"\"},\"opportunity\":" + opportunityId + "}");
        assertThat(count("contact_opportunities", "contact_id = ? AND opportunity_id = ?", id,
            opportunityId)).isEqualTo(1);
        assertThat(count("accounts")).isZero();
        assertThat(count("versions", "item_type = 'ContactOpportunity'")).isZero();
    }

    @Test
    void contactCreateWithoutNamesReportsBothMessages() throws Exception {
        mockMvc.perform(authed(post("/api/v1/contacts"), aliceBearer)
                .content("{\"contact\":{\"first_name\":\"\",\"last_name\":\"\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"first_name\":[\"^Please specify first name.\"],"
                + "\"last_name\":[\"^Please specify last name.\"]}}", JsonCompareMode.STRICT));
        assertThat(count("contacts")).isZero();
    }

    @Test
    void contactUpdateWithoutAnAccountKeyUnlinksTheAccount() throws Exception {
        long accountId = account("Old Co", alice, "Public");
        long id = contact("Con", "Tact", alice, "Public");
        linkAccountContact(accountId, id);

        mockMvc.perform(authed(put("/api/v1/contacts/" + id), aliceBearer)
                .content("{\"contact\":{\"title\":\"CTO\"}}"))
            .andExpect(status().isNoContent());
        assertThat(count("account_contacts")).isZero();
        assertThat(counter("accounts", "contacts_count", accountId)).isZero();
        assertThat(count("versions", "item_type = 'AccountContact' AND event = 'destroy'"))
            .isEqualTo(1);
        assertThat(row("contacts", id).get("title")).isEqualTo("CTO");

        long newAccount = account("New Co", alice, "Public");
        mockMvc.perform(authed(put("/api/v1/contacts/" + id), aliceBearer)
                .content("{\"contact\":{},\"account\":{\"id\":" + newAccount + "}}"))
            .andExpect(status().isNoContent());
        assertThat(count("account_contacts", "account_id = ? AND contact_id = ?", newAccount, id))
            .isEqualTo(1);
        assertThat(counter("accounts", "contacts_count", newAccount)).isEqualTo(1);
    }

    @Test
    void contactDestroyRemovesJoinRowsAndDecrementsTheAccountCounter() throws Exception {
        long accountId = account("Co", alice, "Public");
        long opportunityId = opportunity("Deal", alice, "Public", null);
        long id = contact("Con", "Tact", alice, "Public");
        linkAccountContact(accountId, id);
        jdbcTemplate.update("INSERT INTO contact_opportunities (contact_id, opportunity_id,"
            + " created_at, updated_at) VALUES (?, ?, now(), now())", id, opportunityId);

        mockMvc.perform(authed(delete("/api/v1/contacts/" + id), aliceBearer))
            .andExpect(status().isNoContent());
        assertThat(exists("contacts", id)).isFalse();
        assertThat(count("account_contacts")).isZero();
        assertThat(count("contact_opportunities")).isZero();
        assertThat(exists("opportunities", opportunityId)).isTrue();
        assertThat(counter("accounts", "contacts_count", accountId)).isZero();
        assertThat(events("Contact", id)).containsExactly("destroy");
    }

    // ------------------------------------------------------------------ opportunities

    @Test
    void opportunityCreateWithoutAnAccountParamIsAnOpaque500() throws Exception {
        mockMvc.perform(authed(post("/api/v1/opportunities"), aliceBearer)
                .content("{\"opportunity\":{\"name\":\"Deal\"}}"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
        assertThat(count("opportunities")).isZero();
    }

    @Test
    void opportunityCreateLinksAccountCampaignAndContactAndBumpsCounters() throws Exception {
        long accountId = account("Co", alice, "Public");
        long campaignId = campaign("Camp", alice, "Public");
        long contactId = contact("Con", "Tact", alice, "Public");
        long id = create("/api/v1/opportunities", aliceBearer, "{\"opportunity\":{\"name\":\"Deal\","
            + "\"stage\":\"prospecting\",\"amount\":\"1500.50\",\"probability\":\"40\"},"
            + "\"account\":{\"id\":\"" + accountId + "\"},\"campaign\":\"" + campaignId + "\","
            + "\"contact\":" + contactId + "}");
        Map<String, Object> row = row("opportunities", id);
        assertThat(row.get("campaign_id")).isEqualTo((int) campaignId);
        assertThat(((BigDecimal) row.get("amount"))).isEqualByComparingTo("1500.50");
        assertThat(row.get("probability")).isEqualTo(40);
        assertThat(count("account_opportunities", "account_id = ? AND opportunity_id = ?",
            accountId, id)).isEqualTo(1);
        assertThat(count("contact_opportunities", "contact_id = ? AND opportunity_id = ?",
            contactId, id)).isEqualTo(1);
        assertThat(counter("accounts", "opportunities_count", accountId)).isEqualTo(1);
        assertThat(counter("campaigns", "opportunities_count", campaignId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
            "SELECT item_type FROM versions WHERE event = 'create' ORDER BY id", String.class))
            .containsExactly("AccountOpportunity", "Opportunity");
    }

    @Test
    void opportunityCreateWithBlankAccountFieldsCreatesNoAccountAndNoLink() throws Exception {
        long id = create("/api/v1/opportunities", aliceBearer, "{\"opportunity\":{\"name\":\"Deal\"},"
            + "\"account\":{\"id\":\"\",\"name\":\"\"}}");
        assertThat(count("accounts")).isZero();
        assertThat(count("account_opportunities")).isZero();
        assertThat(row("opportunities", id).get("access")).isEqualTo("Public");
    }

    @Test
    void opportunityCreateValidationAndMissingReferencesFail() throws Exception {
        mockMvc.perform(authed(post("/api/v1/opportunities"), aliceBearer)
                .content("{\"opportunity\":{\"name\":\"\",\"stage\":\"bogus\"},\"account\":{}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"name\":[\"^Please specify opportunity name.\"],"
                + "\"stage\":[\"is not included in the list\"]}}", JsonCompareMode.STRICT));
        mockMvc.perform(authed(post("/api/v1/opportunities"), aliceBearer)
                .content("{\"opportunity\":{\"name\":\"Deal\"},\"account\":{\"id\":999999}}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(authed(post("/api/v1/opportunities"), aliceBearer)
                .content("{\"opportunity\":{\"name\":\"Deal\"},\"account\":{},\"campaign\":999999}"))
            .andExpect(status().isNotFound());
        assertThat(count("opportunities")).isZero();
        assertThat(count("accounts")).isZero();
    }

    @Test
    void opportunityUpdateWithBlankAccountIdUnlinksAndDecrements() throws Exception {
        long accountId = account("Co", alice, "Public");
        long id = opportunity("Deal", alice, "Public", null);
        linkAccountOpportunity(accountId, id);
        mockMvc.perform(authed(put("/api/v1/opportunities/" + id), aliceBearer)
                .content("{\"opportunity\":{\"name\":\"Deal\"},\"account\":{\"id\":\"\"}}"))
            .andExpect(status().isNoContent());
        assertThat(count("account_opportunities")).isZero();
        assertThat(counter("accounts", "opportunities_count", accountId)).isZero();
        assertThat(count("versions", "item_type = 'AccountOpportunity' AND event = 'destroy'"))
            .isEqualTo(1);
    }

    @Test
    void opportunityWonAddsRevenueToTheCampaignAndUnwinRemovesIt() throws Exception {
        long campaignId = campaign("Camp", alice, "Public");
        long id = opportunity("Deal", alice, "Public", campaignId);
        jdbcTemplate.update("UPDATE opportunities SET amount = 1000, discount = 100 WHERE id = ?", id);

        mockMvc.perform(authed(put("/api/v1/opportunities/" + id), aliceBearer)
                .content("{\"opportunity\":{\"stage\":\"won\"}}"))
            .andExpect(status().isNoContent());
        assertThat((BigDecimal) row("campaigns", campaignId).get("revenue")).isEqualByComparingTo("900");
        assertThat(row("opportunities", id).get("probability")).isEqualTo(100);
        assertThat(events("Opportunity", id)).containsExactly("update", "update", "won");
        assertThat(events("Campaign", campaignId)).containsExactly("update");
        assertThat((String) versions("Campaign", campaignId).get(0).get("object_changes"))
            .contains("revenue:\n");

        mockMvc.perform(authed(put("/api/v1/opportunities/" + id), aliceBearer)
                .content("{\"opportunity\":{\"stage\":\"lost\"}}"))
            .andExpect(status().isNoContent());
        assertThat((BigDecimal) row("campaigns", campaignId).get("revenue")).isEqualByComparingTo("0");
        assertThat(row("opportunities", id).get("probability")).isEqualTo(100);
        assertThat(events("Campaign", campaignId)).containsExactly("update", "update");
    }

    @Test
    void opportunityLostFromOpenZeroesProbability() throws Exception {
        long id = opportunity("Deal", alice, "Public", null);
        jdbcTemplate.update("UPDATE opportunities SET probability = 60 WHERE id = ?", id);
        mockMvc.perform(authed(put("/api/v1/opportunities/" + id), aliceBearer)
                .content("{\"opportunity\":{\"stage\":\"lost\"}}"))
            .andExpect(status().isNoContent());
        assertThat(row("opportunities", id).get("probability")).isEqualTo(0);
        assertThat(events("Opportunity", id)).containsExactly("update", "update");
    }

    @Test
    void opportunityDestroyDecrementsAccountAndCampaignCounters() throws Exception {
        long accountId = account("Co", alice, "Public");
        long campaignId = campaign("Camp", alice, "Public");
        jdbcTemplate.update("UPDATE campaigns SET opportunities_count = 1 WHERE id = ?", campaignId);
        long id = opportunity("Deal", alice, "Public", campaignId);
        linkAccountOpportunity(accountId, id);
        mockMvc.perform(authed(delete("/api/v1/opportunities/" + id), aliceBearer))
            .andExpect(status().isNoContent());
        assertThat(exists("opportunities", id)).isFalse();
        assertThat(counter("accounts", "opportunities_count", accountId)).isZero();
        assertThat(counter("campaigns", "opportunities_count", campaignId)).isZero();
        assertThat(count("account_opportunities")).isZero();
        assertThat(events("Opportunity", id)).containsExactly("destroy");
    }

    // ------------------------------------------------------------------ campaigns

    @Test
    void campaignNameIsUniquePerOwnerOnly() throws Exception {
        campaign("Spring", alice, "Public");
        mockMvc.perform(authed(post("/api/v1/campaigns"), aliceBearer)
                .content("{\"campaign\":{\"name\":\"Spring\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"name\":[\"has already been taken\"]}}",
                JsonCompareMode.STRICT));
        create("/api/v1/campaigns", bobBearer, "{\"campaign\":{\"name\":\"Spring\"}}");
        assertThat(count("campaigns", "name = 'Spring'")).isEqualTo(2);
        mockMvc.perform(authed(post("/api/v1/campaigns"), aliceBearer)
                .content("{\"campaign\":{\"name\":\"\",\"status\":\"bogus\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"name\":[\"^Please specify campaign name.\"],"
                + "\"status\":[\"is not included in the list\"]}}", JsonCompareMode.STRICT));
    }

    @Test
    void campaignEndDateBeforeStartDateIsRejected() throws Exception {
        long id = campaign("Spring", alice, "Public");
        mockMvc.perform(authed(put("/api/v1/campaigns/" + id), aliceBearer)
                .content("{\"campaign\":{\"starts_on\":\"2030-05-10\",\"ends_on\":\"2030-05-01\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.ends_on[0]",
                Matchers.startsWith("^Please make sure the campaign end date is after")));
        assertThat(row("campaigns", id).get("starts_on")).isNull();
        mockMvc.perform(authed(put("/api/v1/campaigns/" + id), aliceBearer)
                .content("{\"campaign\":{\"starts_on\":\"2030-05-01\",\"ends_on\":\"2030-05-10\","
                    + "\"budget\":\"2500.75\"}}"))
            .andExpect(status().isNoContent());
        assertThat(row("campaigns", id).get("starts_on").toString()).isEqualTo("2030-05-01");
        assertThat((BigDecimal) row("campaigns", id).get("budget")).isEqualByComparingTo("2500.75");
    }

    @Test
    void campaignAttachLeadMovesItAndBumpsTheCounterOnce() throws Exception {
        long campaignId = campaign("Camp", alice, "Public");
        long leadId = lead("Lee", "Ad", alice, "Public", null);
        mockMvc.perform(authed(put("/api/v1/campaigns/" + campaignId
            + "/attach?assets=leads&asset_id=" + leadId), aliceBearer))
            .andExpect(status().isNoContent());
        assertThat(row("leads", leadId).get("campaign_id")).isEqualTo((int) campaignId);
        assertThat(counter("campaigns", "leads_count", campaignId)).isEqualTo(1);
        assertThat(events("Lead", leadId)).containsExactly("update");

        mockMvc.perform(authed(put("/api/v1/campaigns/" + campaignId
            + "/attach?assets=leads&asset_id=" + leadId), aliceBearer))
            .andExpect(status().isNoContent());
        assertThat(counter("campaigns", "leads_count", campaignId)).isEqualTo(1);
        assertThat(events("Lead", leadId)).containsExactly("update");
    }

    @Test
    void campaignDiscardLeadNullifiesAndDecrementsThenReturnsTheCampaign() throws Exception {
        long campaignId = campaign("Camp", alice, "Public");
        jdbcTemplate.update("UPDATE campaigns SET leads_count = 1 WHERE id = ?", campaignId);
        long leadId = lead("Lee", "Ad", alice, "Public", campaignId);
        mockMvc.perform(authed(post("/api/v1/campaigns/" + campaignId
            + "/discard?attachment=leads&attachment_id=" + leadId), aliceBearer))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(campaignId))
            .andExpect(jsonPath("$.name").value("Camp"));
        assertThat(row("leads", leadId).get("campaign_id")).isNull();
        assertThat(counter("campaigns", "leads_count", campaignId)).isZero();
        assertThat(events("Lead", leadId)).containsExactly("update");
    }

    // ------------------------------------------------------------------ attach / discard

    @Test
    void accountAttachContactCreatesTheJoinOnceWithCounterAndVersion() throws Exception {
        long accountId = account("Co", alice, "Public");
        long contactId = contact("Con", "Tact", alice, "Public");
        String path = "/api/v1/accounts/" + accountId + "/attach?assets=contacts&asset_id=" + contactId;
        mockMvc.perform(authed(put(path), aliceBearer)).andExpect(status().isNoContent());
        mockMvc.perform(authed(put(path), aliceBearer)).andExpect(status().isNoContent());
        assertThat(count("account_contacts")).isEqualTo(1);
        assertThat(counter("accounts", "contacts_count", accountId)).isEqualTo(1);
        assertThat(count("versions", "item_type = 'AccountContact' AND event = 'create'"))
            .isEqualTo(1);
    }

    @Test
    void attachWithUnknownOrMissingAssetIsNotFound() throws Exception {
        long accountId = account("Co", alice, "Public");
        mockMvc.perform(authed(put("/api/v1/accounts/" + accountId
            + "/attach?assets=contacts&asset_id=999999"), aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(authed(put("/api/v1/accounts/" + accountId
            + "/attach?assets=widgets&asset_id=1"), aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(authed(put("/api/v1/accounts/" + accountId + "/attach"), aliceBearer))
            .andExpect(status().isBadRequest());
        assertThat(count("account_contacts")).isZero();
    }

    @Test
    void leadAttachOfANonTaskAssetMirrorsRailsNoMethodErrorAs500() throws Exception {
        long leadId = lead("Lee", "Ad", alice, "Public", null);
        long contactId = contact("Con", "Tact", alice, "Public");
        mockMvc.perform(authed(put("/api/v1/leads/" + leadId
            + "/attach?assets=contacts&asset_id=" + contactId), aliceBearer))
            .andExpect(status().isInternalServerError());
    }

    @Test
    void accountDiscardContactDeletesTheJoinWithoutTouchingTheCounter() throws Exception {
        long accountId = account("Co", alice, "Public");
        long contactId = contact("Con", "Tact", alice, "Public");
        linkAccountContact(accountId, contactId);
        mockMvc.perform(authed(post("/api/v1/accounts/" + accountId
            + "/discard?attachment=contacts&attachment_id=" + contactId), aliceBearer))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(accountId));
        assertThat(count("account_contacts")).isZero();
        assertThat(exists("contacts", contactId)).isTrue();
        // has_many :through delete → no callbacks: counter stays stale, no destroy version.
        assertThat(counter("accounts", "contacts_count", accountId)).isEqualTo(1);
        assertThat(count("versions", "item_type = 'AccountContact'")).isZero();
    }

    @Test
    void taskAttachAndDiscardUpdateTheAssetWithVersions() throws Exception {
        long opportunityId = opportunity("Deal", alice, "Public", null);
        long taskId = task("Call", alice, null, null);
        mockMvc.perform(authed(put("/api/v1/opportunities/" + opportunityId
            + "/attach?assets=tasks&asset_id=" + taskId), aliceBearer))
            .andExpect(status().isNoContent());
        assertThat(row("tasks", taskId).get("asset_type")).isEqualTo("Opportunity");
        assertThat(row("tasks", taskId).get("asset_id")).isEqualTo((int) opportunityId);
        assertThat((String) versions("Task", taskId).get(0).get("object_changes"))
            .contains("asset_type:\n-\n- Opportunity\n");

        mockMvc.perform(authed(post("/api/v1/opportunities/" + opportunityId
            + "/discard?attachment=tasks&attachment_id=" + taskId), aliceBearer))
            .andExpect(status().isCreated());
        assertThat(row("tasks", taskId).get("asset_type")).isNull();
        assertThat(row("tasks", taskId).get("asset_id")).isNull();
        assertThat(events("Task", taskId)).containsExactly("update", "update");
    }

    // ------------------------------------------------------------------ subscribe / unsubscribe

    @Test
    void subscribePersistsTheSubscriptionAndThenMirrorsTheRails500() throws Exception {
        long accountId = account("Co", alice, "Public");
        mockMvc.perform(authed(post("/api/v1/accounts/" + accountId + "/subscribe"), bobBearer))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
        assertThat(subscribedUsers("accounts", accountId)).containsExactly(bob.getId());
        assertThat(versions("Account", accountId)).isEmpty();
    }

    @Test
    void unsubscribeRemovesOnlyTheCurrentUserAndReturnsTheEntity() throws Exception {
        long leadId = lead("Lee", "Ad", alice, "Public", null);
        jdbcTemplate.update("UPDATE leads SET subscribed_users = ? WHERE id = ?",
            "---\n- " + alice.getId() + "\n- " + bob.getId() + "\n", leadId);
        mockMvc.perform(authed(post("/api/v1/leads/" + leadId + "/unsubscribe"), bobBearer))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(leadId))
            .andExpect(jsonPath("$.first_name").value("Lee"));
        assertThat(subscribedUsers("leads", leadId)).containsExactly(alice.getId());
        mockMvc.perform(authed(post("/api/v1/leads/" + leadId + "/unsubscribe"), bobBearer))
            .andExpect(status().isCreated());
        assertThat(subscribedUsers("leads", leadId)).containsExactly(alice.getId());
        assertThat(versions("Lead", leadId)).isEmpty();
    }

    private long create(String path, String bearer, String body) throws Exception {
        String response = mockMvc.perform(authed(post(path), bearer).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json(response).path("id").asLong();
    }
}
