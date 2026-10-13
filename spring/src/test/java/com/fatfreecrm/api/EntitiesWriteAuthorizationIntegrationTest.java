package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Authorization boundary of {@code EntitiesWriteController}: every member write mirrors Rails
 * {@code load_and_authorize_resource} + {@code Ability} (Public, owner, assignee, user/group
 * permission row, admin). Unauthenticated → 401; a row outside the caller's scope → 403 (Rails
 * 401, documented gap); a missing row → 404. None of the denied requests may write anything.
 */
class EntitiesWriteAuthorizationIntegrationTest extends AbstractEntityWriteIntegrationTest {

    private static final String ACCOUNT_BODY = "{\"account\":{\"name\":\"Renamed\"}}";

    @Test
    void everyEntityWriteRouteRequiresAuthentication() throws Exception {
        long accountId = account("Alice Co", alice, "Public");
        long leadId = lead("Lee", "Ad", alice, "Public", null);
        long contactId = contact("Con", "Tact", alice, "Public");
        List<MockHttpServletRequestBuilder> requests = List.of(
            post("/api/v1/accounts").content(ACCOUNT_BODY),
            post("/api/v1/campaigns").content("{\"campaign\":{\"name\":\"x\"}}"),
            post("/api/v1/contacts").content("{\"contact\":{\"first_name\":\"a\",\"last_name\":\"b\"}}"),
            post("/api/v1/leads").content("{\"lead\":{\"first_name\":\"a\",\"last_name\":\"b\"}}"),
            post("/api/v1/opportunities").content("{\"opportunity\":{\"name\":\"x\"},\"account\":{}}"),
            put("/api/v1/accounts/" + accountId).content(ACCOUNT_BODY),
            delete("/api/v1/accounts/" + accountId),
            put("/api/v1/leads/" + leadId + "/promote").content("{}"),
            put("/api/v1/leads/" + leadId + "/reject"),
            get("/api/v1/leads/" + leadId + "/convert"),
            put("/api/v1/accounts/" + accountId + "/attach?assets=contacts&asset_id=" + contactId),
            post("/api/v1/accounts/" + accountId + "/discard?attachment=contacts&attachment_id="
                + contactId),
            post("/api/v1/accounts/" + accountId + "/subscribe"),
            post("/api/v1/accounts/" + accountId + "/unsubscribe"));
        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        }
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(count("campaigns")).isZero();
        assertThat(count("opportunities")).isZero();
        assertThat(row("accounts", accountId).get("name")).isEqualTo("Alice Co");
        assertThat(row("leads", leadId).get("status")).isEqualTo("new");
        assertThat(count("account_contacts")).isZero();
        assertThat(count("versions")).isZero();
    }

    @Test
    void privateEntityOfAnotherUserIsForbiddenOnEveryMemberWrite() throws Exception {
        long accountId = account("Alice Private", alice, "Private");
        long leadId = lead("Lee", "Ad", alice, "Private", null);
        long contactId = contact("Con", "Tact", alice, "Public");
        List<MockHttpServletRequestBuilder> requests = List.of(
            put("/api/v1/accounts/" + accountId).content(ACCOUNT_BODY),
            delete("/api/v1/accounts/" + accountId),
            put("/api/v1/leads/" + leadId).content("{\"lead\":{\"first_name\":\"Bob\"}}"),
            delete("/api/v1/leads/" + leadId),
            put("/api/v1/leads/" + leadId + "/promote").content(
                "{\"account\":{\"name\":\"Promoted\"}}"),
            put("/api/v1/leads/" + leadId + "/reject"),
            get("/api/v1/leads/" + leadId + "/convert"),
            put("/api/v1/accounts/" + accountId + "/attach?assets=contacts&asset_id=" + contactId),
            post("/api/v1/accounts/" + accountId + "/discard?attachment=contacts&attachment_id="
                + contactId),
            post("/api/v1/accounts/" + accountId + "/subscribe"),
            post("/api/v1/accounts/" + accountId + "/unsubscribe"),
            post("/api/v1/leads/" + leadId + "/subscribe"));
        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(authed(request, bobBearer))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("You are not allowed to access this resource."));
        }
        assertThat(row("accounts", accountId).get("name")).isEqualTo("Alice Private");
        assertThat(row("leads", leadId).get("status")).isEqualTo("new");
        assertThat(row("leads", leadId).get("first_name")).isEqualTo("Lee");
        assertThat(subscribedUsers("accounts", accountId)).isEmpty();
        assertThat(count("account_contacts")).isZero();
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(count("contacts")).isEqualTo(1);
        assertThat(count("versions")).isZero();
    }

    @Test
    void missingEntityIsNotFoundOnMemberWritesAndFamilyActions() throws Exception {
        long contactId = contact("Con", "Tact", alice, "Public");
        List<MockHttpServletRequestBuilder> requests = List.of(
            put("/api/v1/accounts/999999").content(ACCOUNT_BODY),
            delete("/api/v1/campaigns/999999"),
            put("/api/v1/contacts/999999").content("{\"contact\":{\"first_name\":\"x\"}}"),
            put("/api/v1/leads/999999/reject"),
            put("/api/v1/leads/999999/promote").content("{}"),
            get("/api/v1/leads/999999/convert"),
            put("/api/v1/opportunities/999999").content("{\"opportunity\":{\"name\":\"x\"}}"),
            put("/api/v1/accounts/999999/attach?assets=contacts&asset_id=" + contactId),
            post("/api/v1/accounts/999999/discard?attachment=contacts&attachment_id=" + contactId),
            post("/api/v1/opportunities/999999/subscribe"),
            post("/api/v1/opportunities/999999/unsubscribe"));
        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(authed(request, aliceBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("The requested entity was not found."));
        }
        assertThat(count("versions")).isZero();
    }

    @Test
    void publicEntityOfAnotherUserIsWritable() throws Exception {
        long accountId = account("Alice Public", alice, "Public");
        mockMvc.perform(authed(put("/api/v1/accounts/" + accountId), bobBearer)
            .content(ACCOUNT_BODY)).andExpect(status().isNoContent());
        assertThat(row("accounts", accountId).get("name")).isEqualTo("Renamed");
        assertThat(versions("Account", accountId)).singleElement()
            .satisfies(v -> assertThat(v.get("whodunnit")).isEqualTo(bob.getId().toString()));
    }

    @Test
    void sharedEntityIsWritableOnlyByUsersWithAPermissionRow() throws Exception {
        long sharedWithBob = account("Shared with Bob", alice, "Shared");
        permission("Account", sharedWithBob, bob, null);
        long sharedWithAdminOnly = account("Shared elsewhere", alice, "Shared");
        permission("Account", sharedWithAdminOnly, admin, null);

        mockMvc.perform(authed(put("/api/v1/accounts/" + sharedWithBob), bobBearer)
            .content(ACCOUNT_BODY)).andExpect(status().isNoContent());
        mockMvc.perform(authed(put("/api/v1/accounts/" + sharedWithAdminOnly), bobBearer)
            .content(ACCOUNT_BODY)).andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/v1/accounts/" + sharedWithAdminOnly + "/unsubscribe"),
            bobBearer)).andExpect(status().isForbidden());

        assertThat(row("accounts", sharedWithBob).get("name")).isEqualTo("Renamed");
        assertThat(row("accounts", sharedWithAdminOnly).get("name")).isEqualTo("Shared elsewhere");
    }

    @Test
    void groupPermissionGrantsAccessToGroupMembers() throws Exception {
        long groupId = group("sales", bob);
        long accountId = account("Group shared", alice, "Shared");
        permission("Account", accountId, null, groupId);
        long leadId = lead("Lee", "Ad", alice, "Shared", null);
        permission("Lead", leadId, null, groupId);

        mockMvc.perform(authed(put("/api/v1/accounts/" + accountId), bobBearer)
            .content(ACCOUNT_BODY)).andExpect(status().isNoContent());
        mockMvc.perform(authed(put("/api/v1/leads/" + leadId + "/reject"), bobBearer))
            .andExpect(status().isNoContent());
        assertThat(row("leads", leadId).get("status")).isEqualTo("rejected");
    }

    @Test
    void assigneeCanWritePrivateEntityOfAnotherOwner() throws Exception {
        long accountId = account("Assigned to Bob", alice, "Private");
        jdbcTemplate.update("UPDATE accounts SET assigned_to = ? WHERE id = ?", bob.getId(),
            accountId);
        mockMvc.perform(authed(post("/api/v1/accounts/" + accountId + "/unsubscribe"), bobBearer))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(accountId));
        mockMvc.perform(authed(delete("/api/v1/accounts/" + accountId), bobBearer))
            .andExpect(status().isNoContent());
        assertThat(exists("accounts", accountId)).isFalse();
    }

    @Test
    void adminBypassesPrivateScopeLikeCanCanManageAll() throws Exception {
        long leadId = lead("Lee", "Ad", alice, "Private", null);
        long opportunityId = opportunity("Big deal", alice, "Private", null);
        mockMvc.perform(authed(put("/api/v1/leads/" + leadId + "/reject"), adminBearer))
            .andExpect(status().isNoContent());
        mockMvc.perform(authed(delete("/api/v1/opportunities/" + opportunityId), adminBearer))
            .andExpect(status().isNoContent());
        assertThat(row("leads", leadId).get("status")).isEqualTo("rejected");
        assertThat(exists("opportunities", opportunityId)).isFalse();
    }

    @Test
    void attachAuthorizesTheTargetEntityNotTheAttachedAsset() throws Exception {
        long bobsAccount = account("Bob Co", bob, "Private");
        long alicesPrivateContact = contact("Hidden", "Contact", alice, "Private");
        // Rails only runs load_and_authorize_resource on the entity; the asset is a plain find.
        mockMvc.perform(authed(put("/api/v1/accounts/" + bobsAccount
            + "/attach?assets=contacts&asset_id=" + alicesPrivateContact), bobBearer))
            .andExpect(status().isNoContent());
        assertThat(count("account_contacts", "account_id = ? AND contact_id = ?", bobsAccount,
            alicesPrivateContact)).isEqualTo(1);

        long alicesAccount = account("Alice Co", alice, "Private");
        mockMvc.perform(authed(put("/api/v1/accounts/" + alicesAccount
            + "/attach?assets=contacts&asset_id=" + alicesPrivateContact), bobBearer))
            .andExpect(status().isForbidden());
        assertThat(count("account_contacts", "account_id = ?", alicesAccount)).isZero();
    }

    @Test
    void unknownFamilyAndNonNumericIdsDoNotMatchTheWriteRoutes() throws Exception {
        mockMvc.perform(authed(put("/api/v1/widgets/1/attach?assets=contacts&asset_id=1"),
            aliceBearer)).andExpect(status().isNotFound());
        mockMvc.perform(authed(put("/api/v1/accounts/abc"), aliceBearer).content(ACCOUNT_BODY))
            .andExpect(status().isNotFound());
    }
}
