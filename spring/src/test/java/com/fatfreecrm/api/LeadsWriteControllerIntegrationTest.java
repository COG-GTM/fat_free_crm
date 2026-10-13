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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;

/**
 * {@code /api/v1/leads} writes pinned to Rails {@code LeadsController} and {@code Lead}:
 * {@code save_with_permissions} (campaign lookup + Campaign-access copy),
 * {@code update_with_lead_counters}, {@code reject}/{@code convert}, the non-transactional
 * {@code promote} (orphans on partial failure, 500 on any error) and {@code dependent: :nullify}
 * on destroy.
 */
class LeadsWriteControllerIntegrationTest extends AbstractEntityWriteIntegrationTest {

    @Test
    void createWithCampaignLinksAndIncrementsLeadsCount() throws Exception {
        long campaignId = campaign("Spring push", alice, "Public");
        long id = create(aliceBearer, "{\"lead\":{\"first_name\":\"Lee\",\"last_name\":\"Ad\","
            + "\"status\":\"new\",\"rating\":4},\"campaign\":\"" + campaignId + "\"}");
        Map<String, Object> row = row("leads", id);
        assertThat(row.get("campaign_id")).isEqualTo((int) campaignId);
        assertThat(row.get("access")).isEqualTo("Public");
        assertThat(row.get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(row.get("assigned_to")).isEqualTo(alice.getId().intValue());
        assertThat(row.get("rating")).isEqualTo(4);
        assertThat(counter("campaigns", "leads_count", campaignId)).isEqualTo(1);
        assertThat(events("Lead", id)).containsExactly("create");
        assertThat((String) versions("Lead", id).get(0).get("object_changes"))
            .contains("first_name:\n- ''\n- Lee\n").contains("campaign_id:\n-\n- " + campaignId + "\n");
    }

    @Test
    void createWithCampaignAccessCopiesTheCampaignAccessAndPermissions() throws Exception {
        long campaignId = campaign("Shared campaign", alice, "Shared");
        permission("Campaign", campaignId, bob, null);
        long id = create(aliceBearer, "{\"lead\":{\"first_name\":\"Lee\",\"last_name\":\"Ad\","
            + "\"access\":\"Campaign\"},\"campaign\":" + campaignId + "}");
        assertThat(row("leads", id).get("access")).isEqualTo("Shared");
        assertThat(permissionUserIds("Lead", id)).containsExactly(bob.getId());
    }

    @Test
    void createWithCampaignAccessButNoCampaignStoresTheLiteralAccessValue() throws Exception {
        long id = create(aliceBearer, "{\"lead\":{\"first_name\":\"Lee\",\"last_name\":\"Ad\","
            + "\"access\":\"Campaign\"}}");
        assertThat(row("leads", id).get("access")).isEqualTo("Campaign");
        assertThat(count("permissions")).isZero();
    }

    @Test
    void createWithMissingCampaignIsNotFoundAndWritesNothing() throws Exception {
        mockMvc.perform(authed(post("/api/v1/leads"), aliceBearer)
                .content("{\"lead\":{\"first_name\":\"Lee\",\"last_name\":\"Ad\"},\"campaign\":999999}"))
            .andExpect(status().isNotFound());
        assertThat(count("leads")).isZero();
        assertThat(count("versions")).isZero();
    }

    @Test
    void createWithoutNamesReportsBothRailsMessagesUnlessSettingsRelaxThem() throws Exception {
        mockMvc.perform(authed(post("/api/v1/leads"), aliceBearer)
                .content("{\"lead\":{\"first_name\":\"\",\"last_name\":\"\",\"status\":\"bogus\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{"
                + "\"first_name\":[\"^Please specify first name.\"],"
                + "\"last_name\":[\"^Please specify last name.\"],"
                + "\"status\":[\"is not included in the list\"]}}", JsonCompareMode.STRICT));
        setting("require_first_names", "--- false\n");
        mockMvc.perform(authed(post("/api/v1/leads"), aliceBearer)
                .content("{\"lead\":{\"first_name\":\"\",\"last_name\":\"\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"last_name\":[\"^Please specify last name.\"]}}",
                JsonCompareMode.STRICT));
        assertThat(count("leads")).isZero();
    }

    @Test
    void updateMovingCampaignsMovesTheLeadsCounters() throws Exception {
        long oldCampaign = campaign("Old", alice, "Public");
        long newCampaign = campaign("New", alice, "Public");
        jdbcTemplate.update("UPDATE campaigns SET leads_count = 1 WHERE id = ?", oldCampaign);
        long id = lead("Lee", "Ad", alice, "Public", oldCampaign);

        mockMvc.perform(authed(put("/api/v1/leads/" + id), aliceBearer)
                .content("{\"lead\":{\"campaign_id\":" + newCampaign + "}}"))
            .andExpect(status().isNoContent());
        assertThat(row("leads", id).get("campaign_id")).isEqualTo((int) newCampaign);
        assertThat(counter("campaigns", "leads_count", oldCampaign)).isZero();
        assertThat(counter("campaigns", "leads_count", newCampaign)).isEqualTo(1);
        assertThat((String) versions("Lead", id).get(0).get("object_changes"))
            .contains("campaign_id:\n- " + oldCampaign + "\n- " + newCampaign + "\n");

        mockMvc.perform(authed(put("/api/v1/leads/" + id), aliceBearer)
                .content("{\"lead\":{\"campaign_id\":" + newCampaign + ",\"title\":\"VP\"}}"))
            .andExpect(status().isNoContent());
        assertThat(counter("campaigns", "leads_count", newCampaign)).isEqualTo(1);

        mockMvc.perform(authed(put("/api/v1/leads/" + id), aliceBearer)
                .content("{\"lead\":{\"campaign_id\":null}}"))
            .andExpect(status().isNoContent());
        assertThat(row("leads", id).get("campaign_id")).isNull();
        assertThat(counter("campaigns", "leads_count", newCampaign)).isZero();
    }

    @Test
    void updateValidationFailureLeavesCountersAndRowUntouched() throws Exception {
        long campaignId = campaign("Camp", alice, "Public");
        jdbcTemplate.update("UPDATE campaigns SET leads_count = 1 WHERE id = ?", campaignId);
        long id = lead("Lee", "Ad", alice, "Public", campaignId);
        mockMvc.perform(authed(put("/api/v1/leads/" + id), aliceBearer)
                .content("{\"lead\":{\"campaign_id\":null,\"first_name\":\"\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.first_name[0]").value("^Please specify first name."));
        assertThat(row("leads", id).get("campaign_id")).isEqualTo((int) campaignId);
        assertThat(row("leads", id).get("first_name")).isEqualTo("Lee");
        assertThat(counter("campaigns", "leads_count", campaignId)).isEqualTo(1);
        assertThat(versions("Lead", id)).isEmpty();
    }

    @Test
    void rejectSetsStatusAndWritesUpdateThenRejectVersions() throws Exception {
        long id = lead("Lee", "Ad", alice, "Public", null);
        mockMvc.perform(authed(put("/api/v1/leads/" + id + "/reject"), aliceBearer))
            .andExpect(status().isNoContent());
        assertThat(row("leads", id).get("status")).isEqualTo("rejected");
        List<Map<String, Object>> versions = versions("Lead", id);
        assertThat(versions).extracting(v -> v.get("event")).containsExactly("update", "reject");
        assertThat((String) versions.get(0).get("object_changes"))
            .contains("status:\n- new\n- rejected\n");
        assertThat(versions.get(1).get("object")).isNull();
        assertThat(versions.get(1).get("object_changes")).isNull();
        assertThat(versions.get(1).get("whodunnit")).isEqualTo(alice.getId().toString());
    }

    @Test
    void convertRendersTheLeadLikeShow() throws Exception {
        long id = lead("Lee", "Ad", alice, "Public", null);
        mockMvc.perform(authed(get("/api/v1/leads/" + id + "/convert"), aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.first_name").value("Lee"))
            .andExpect(jsonPath("$.status").value("new"));
        assertThat(count("versions")).isZero();
    }

    @Test
    void promoteCreatesAccountOpportunityAndContactThenConvertsTheLead() throws Exception {
        long campaignId = campaign("Camp", alice, "Public");
        long id = lead("Lee", "Ad", alice, "Public", campaignId);
        jdbcTemplate.update("UPDATE leads SET email = 'lee@example.com', title = 'VP',"
            + " company = 'Lee Co', do_not_call = true, background_info = 'bg' WHERE id = ?", id);

        mockMvc.perform(authed(put("/api/v1/leads/" + id + "/promote"), aliceBearer)
                .content("{\"account\":{\"name\":\"Lee Co\",\"access\":\"Public\"},"
                    + "\"opportunity\":{\"name\":\"Lee deal\",\"stage\":\"prospecting\","
                    + "\"campaign_id\":" + campaignId + "},\"access\":\"Public\"}"))
            .andExpect(status().isNoContent());

        assertThat(row("leads", id).get("status")).isEqualTo("converted");
        Map<String, Object> account = jdbcTemplate.queryForMap(
            "SELECT * FROM accounts WHERE name = 'Lee Co'");
        assertThat(account.get("user_id")).isEqualTo(alice.getId().intValue());
        long accountId = ((Number) account.get("id")).longValue();
        Map<String, Object> opportunity = jdbcTemplate.queryForMap(
            "SELECT * FROM opportunities WHERE name = 'Lee deal'");
        long opportunityId = ((Number) opportunity.get("id")).longValue();
        assertThat(opportunity.get("campaign_id")).isEqualTo((int) campaignId);
        Map<String, Object> contact = jdbcTemplate.queryForMap(
            "SELECT * FROM contacts WHERE lead_id = ?", id);
        long contactId = ((Number) contact.get("id")).longValue();
        assertThat(contact.get("first_name")).isEqualTo("Lee");
        assertThat(contact.get("last_name")).isEqualTo("Ad");
        assertThat(contact.get("email")).isEqualTo("lee@example.com");
        assertThat(contact.get("title")).isEqualTo("VP");
        assertThat(contact.get("do_not_call")).isEqualTo(true);
        assertThat(contact.get("background_info")).isEqualTo("bg");
        assertThat(contact.get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(contact.get("access")).isEqualTo("Public");

        assertThat(count("account_opportunities", "account_id = ? AND opportunity_id = ?",
            accountId, opportunityId)).isEqualTo(1);
        assertThat(count("account_contacts", "account_id = ? AND contact_id = ?", accountId,
            contactId)).isEqualTo(1);
        assertThat(count("contact_opportunities", "contact_id = ? AND opportunity_id = ?",
            contactId, opportunityId)).isEqualTo(1);
        assertThat(counter("accounts", "opportunities_count", accountId)).isEqualTo(1);
        assertThat(counter("accounts", "contacts_count", accountId)).isEqualTo(1);
        assertThat(counter("campaigns", "opportunities_count", campaignId)).isEqualTo(1);

        assertThat(events("Lead", id)).containsExactly("update");
        assertThat((String) versions("Lead", id).get(0).get("object_changes"))
            .contains("status:\n- new\n- converted\n");
        assertThat(events("Account", accountId)).containsExactly("create");
        assertThat(events("Opportunity", opportunityId)).containsExactly("create");
        assertThat(events("Contact", contactId)).containsExactly("create");
        assertThat(count("versions", "item_type = 'AccountOpportunity' AND event = 'create'"))
            .isEqualTo(1);
        assertThat(count("versions", "item_type = 'AccountContact' AND event = 'create'"))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT whodunnit FROM versions",
            String.class)).containsExactly(alice.getId().toString());
    }

    @Test
    void promoteAcceptsPatchAndAnExistingAccountIdWithoutAnOpportunity() throws Exception {
        long accountId = account("Existing Co", bob, "Private");
        long id = lead("Lee", "Ad", alice, "Public", null);
        mockMvc.perform(authed(patch("/api/v1/leads/" + id + "/promote"), aliceBearer)
                .content("{\"account\":{\"id\":\"" + accountId + "\"},\"opportunity\":{\"name\":\"\"},"
                    + "\"access\":\"Private\"}"))
            .andExpect(status().isNoContent());
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(count("opportunities")).isZero();
        Map<String, Object> contact = jdbcTemplate.queryForMap(
            "SELECT * FROM contacts WHERE lead_id = ?", id);
        assertThat(contact.get("access")).isEqualTo("Private");
        assertThat(contact.get("user_id")).isEqualTo(bob.getId().intValue());
        assertThat(count("account_contacts", "account_id = ?", accountId)).isEqualTo(1);
        assertThat(counter("accounts", "contacts_count", accountId)).isEqualTo(1);
        assertThat(row("leads", id).get("status")).isEqualTo("converted");
    }

    @Test
    void promoteWithLeadAccessCopiesTheLeadPermissionsOntoAccountAndContact() throws Exception {
        long id = lead("Lee", "Ad", alice, "Shared", null);
        permission("Lead", id, bob, null);
        mockMvc.perform(authed(put("/api/v1/leads/" + id + "/promote"), aliceBearer)
                .content("{\"account\":{\"name\":\"Lead Co\",\"access\":\"Lead\"},"
                    + "\"opportunity\":{\"name\":\"\"},\"access\":\"Lead\"}"))
            .andExpect(status().isNoContent());
        long accountId = jdbcTemplate.queryForObject(
            "SELECT id FROM accounts WHERE name = 'Lead Co'", Long.class);
        long contactId = jdbcTemplate.queryForObject(
            "SELECT id FROM contacts WHERE lead_id = ?", Long.class, id);
        assertThat(row("accounts", accountId).get("access")).isEqualTo("Shared");
        assertThat(row("contacts", contactId).get("access")).isEqualTo("Shared");
        assertThat(permissionUserIds("Account", accountId)).containsExactly(bob.getId());
        assertThat(permissionUserIds("Contact", contactId)).containsExactly(bob.getId());
    }

    @Test
    void promoteWithBlankAccountNameIsAnOpaque500AndPersistsNothing() throws Exception {
        long id = lead("Lee", "Ad", alice, "Public", null);
        mockMvc.perform(authed(put("/api/v1/leads/" + id + "/promote"), aliceBearer)
                .content("{\"account\":{\"name\":\"\"},\"opportunity\":{\"name\":\"Deal\"}}"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
        assertThat(row("leads", id).get("status")).isEqualTo("new");
        assertThat(count("accounts")).isZero();
        assertThat(count("opportunities")).isZero();
        assertThat(count("contacts")).isZero();
        assertThat(count("versions")).isZero();
    }

    @Test
    void promoteWithInvalidOpportunityLeavesTheNewAccountOrphanedLikeRails() throws Exception {
        long id = lead("Lee", "Ad", alice, "Public", null);
        mockMvc.perform(authed(put("/api/v1/leads/" + id + "/promote"), aliceBearer)
                .content("{\"account\":{\"name\":\"Orphan Co\"},"
                    + "\"opportunity\":{\"name\":\"Deal\",\"stage\":\"bogus\"},\"access\":\"Public\"}"))
            .andExpect(status().isInternalServerError());
        // Lead#promote is not wrapped in a transaction: the account save already committed.
        assertThat(count("accounts", "name = 'Orphan Co'")).isEqualTo(1);
        assertThat(count("opportunities")).isZero();
        assertThat(count("contacts")).isZero();
        assertThat(row("leads", id).get("status")).isEqualTo("new");
        assertThat(count("versions", "item_type = 'Account' AND event = 'create'")).isEqualTo(1);
        assertThat(count("versions", "item_type = 'Lead'")).isZero();
    }

    @Test
    void promoteWithMissingAccountIdIsNotFound() throws Exception {
        long id = lead("Lee", "Ad", alice, "Public", null);
        mockMvc.perform(authed(put("/api/v1/leads/" + id + "/promote"), aliceBearer)
                .content("{\"account\":{\"id\":999999}}"))
            .andExpect(status().isNotFound());
        assertThat(row("leads", id).get("status")).isEqualTo("new");
        assertThat(count("contacts")).isZero();
    }

    @Test
    void destroyNullifiesTheConvertedContactAndDecrementsTheCampaign() throws Exception {
        long campaignId = campaign("Camp", alice, "Public");
        jdbcTemplate.update("UPDATE campaigns SET leads_count = 1 WHERE id = ?", campaignId);
        long id = lead("Lee", "Ad", alice, "Public", campaignId);
        long contactId = contact("Lee", "Ad", alice, "Public");
        jdbcTemplate.update("UPDATE contacts SET lead_id = ? WHERE id = ?", id, contactId);
        long taskId = task("Follow up", alice, "Lead", id);

        mockMvc.perform(authed(delete("/api/v1/leads/" + id), aliceBearer))
            .andExpect(status().isNoContent());

        assertThat(exists("leads", id)).isFalse();
        assertThat(exists("contacts", contactId)).isTrue();
        assertThat(row("contacts", contactId).get("lead_id")).isNull();
        assertThat(exists("tasks", taskId)).isFalse();
        assertThat(counter("campaigns", "leads_count", campaignId)).isZero();
        assertThat(events("Lead", id)).containsExactly("destroy");
        assertThat(events("Contact", contactId)).containsExactly("update");
        assertThat((String) versions("Contact", contactId).get(0).get("object_changes"))
            .contains("lead_id:\n- " + id + "\n-\n");
        assertThat(events("Task", taskId)).containsExactly("destroy");
    }

    private long create(String bearer, String body) throws Exception {
        String response = mockMvc.perform(authed(post("/api/v1/leads"), bearer).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.first_name").exists())
            .andReturn().getResponse().getContentAsString();
        return json(response).path("id").asLong();
    }
}
