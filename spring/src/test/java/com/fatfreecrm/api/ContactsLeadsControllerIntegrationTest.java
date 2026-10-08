package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.AccountContact;
import com.fatfreecrm.domain.Address;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.ContactOpportunity;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.Preference;
import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.AccountContactRepository;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.AddressRepository;
import com.fatfreecrm.repository.CampaignRepository;
import com.fatfreecrm.repository.ContactOpportunityRepository;
import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.repository.LeadRepository;
import com.fatfreecrm.repository.OpportunityRepository;
import com.fatfreecrm.repository.PreferenceRepository;
import com.fatfreecrm.repository.SettingRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.service.json.RailsJsonWriter;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.read.RecentlyViewedService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

class ContactsLeadsControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountContactRepository accountContactRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private ContactOpportunityRepository contactOpportunityRepository;

    @Autowired
    private OpportunityRepository opportunityRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private PreferenceRepository preferenceRepository;

    @Autowired
    private SettingRepository settingRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private RailsJsonWriter jsonWriter;

    @Autowired
    private RailsResources railsResources;

    @MockitoSpyBean
    private RecentlyViewedService recentlyViewedService;

    private User alice;
    private User bob;
    private String aliceBearer;
    private String bobBearer;
    private Account account;
    private Contact aliceContact;
    private Contact bobPublicContact;
    private Contact bobPrivateContact;
    private Lead newLead;
    private Lead nullStatusLead;
    private Lead bobPublicLead;
    private Lead bobPrivateLead;
    private Campaign campaign;
    private Opportunity opportunity;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice", false);
        bob = user("bob", false);
        aliceBearer = bearer(alice);
        bobBearer = bearer(bob);
        account = account(alice, "Acme");
        campaign = campaign(alice, "Spring Campaign");
        opportunity = opportunity(alice, "Opportunity", campaign);
        aliceContact = contact(alice, "Alice", "Smith", "Public");
        bobPublicContact = contact(bob, "Bob", "Public", "Public");
        bobPrivateContact = contact(bob, "Bob", "Private", "Private");
        accountContact(aliceContact, account);
        accountContact(bobPublicContact, account);
        contactOpportunityRepository.saveAndFlush(link(aliceContact, opportunity));
        address(aliceContact, "Business", "1 Main", "Suite 2");
        newLead = lead(alice, "New", "Lead", "new", "Acme");
        nullStatusLead = lead(alice, "Null", "Status", null, "Other Co");
        bobPublicLead = lead(bob, "Bob", "Public", "contacted", "Public Co");
        bobPrivateLead = lead(bob, "Bob", "Private", "unknown", "Private Co");
        bobPrivateLead.setAccess("Private");
        leadRepository.saveAndFlush(bobPrivateLead);
        newLead.setCampaign(campaign);
        leadRepository.saveAndFlush(newLead);
        bobPublicLead.setCampaign(campaign);
        leadRepository.saveAndFlush(bobPublicLead);
        bobPublicLead.setAccess("Public");
        leadRepository.saveAndFlush(bobPublicLead);
    }

    @Test
    void returnsContactsWithoutFacetsAndLeadsWithAccessibleStatusFacets() throws Exception {
        mockMvc.perform(get("/api/v1/contacts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/leads")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/contacts").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(2))
            .andExpect(jsonPath("$.facets").isEmpty());

        mockMvc.perform(get("/api/v1/leads").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(3))
            .andExpect(jsonPath("$.facets.status.new").value(1))
            .andExpect(jsonPath("$.facets.status.contacted").value(1))
            .andExpect(jsonPath("$.facets.status.converted").value(0))
            .andExpect(jsonPath("$.facets.status.rejected").value(0))
            .andExpect(jsonPath("$.facets.status.all").value(3))
            .andExpect(jsonPath("$.facets.status.other").value(1));

        mockMvc.perform(get("/api/v1/contacts").header(HttpHeaders.AUTHORIZATION, bobBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(3));
    }

    @Test
    void leadFacetsUseConfiguredPsychStatusesAndCountUnknownAndNullAsOther() throws Exception {
        Setting statuses = new Setting();
        statuses.setName("lead_status");
        statuses.setValue("---\n- :new\n- :qualified\n- :contacted\n");
        settingRepository.saveAndFlush(statuses);

        mockMvc.perform(get("/api/v1/leads").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.facets.status.new").value(1))
            .andExpect(jsonPath("$.facets.status.qualified").value(0))
            .andExpect(jsonPath("$.facets.status.contacted").value(1))
            .andExpect(jsonPath("$.facets.status.all").value(3))
            .andExpect(jsonPath("$.facets.status.other").value(1));
    }

    @Test
    void appliesLeadOtherFilterAndIgnoresItForAdvancedSearch() throws Exception {
        mockMvc.perform(get("/api/v1/leads").param("status", "other")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].id").value(nullStatusLead.getId()));

        mockMvc.perform(get("/api/v1/leads").param("status", "new,other")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(2));

        mockMvc.perform(get("/api/v1/leads").param("status", "other").param("q[company_cont]", "Acme")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].id").value(newLead.getId()));
    }

    @Test
    void ignoresExplicitLeadPerPageButUsesPreference() throws Exception {
        preference(alice, "leads_per_page", "\"1\"");
        mockMvc.perform(get("/api/v1/leads").param("per_page", "2")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.perPage").value(1))
            .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void servesVcardsWithRailsHeadersAndRecordsViewsWithoutOpenInView() throws Exception {
        MvcResult contactResponse = mockMvc.perform(get("/api/v1/contacts/{id}.vcf", aliceContact.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(contactResponse.getResponse().getContentType()).isEqualTo("text/x-vcard");
        assertThat(contactResponse.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
            .isEqualTo("attachment; filename=\"Alice Smith.vcf\"; filename*=UTF-8''Alice%20Smith.vcf");
        assertThat(contactResponse.getResponse().getContentAsString())
            .contains("ORG:Acme;" + (aliceContact.getDepartment() == null ? "" : aliceContact.getDepartment()))
            .contains("ADR;TYPE=work:1 Main;Suite 2;;;;");

        MvcResult leadResponse = mockMvc.perform(get("/api/v1/leads/{id}.vcf", newLead.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(leadResponse.getResponse().getContentAsString())
            .contains("ORG:Acme\n")
            .contains("NOTE:Exported from Fat Free CRM");

        assertRailsViewVersion("Contact", aliceContact.getId(), alice);
        assertRailsViewVersion("Lead", newLead.getId(), alice);
    }

    @Test
    void showAndVcardEndpointsEnforceAuthorizationAndKeepShowAvailableIfViewRecordingFails() throws Exception {
        MvcResult contactShow = mockMvc.perform(get("/api/v1/contacts/{id}", aliceContact.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(aliceContact.getId()))
            .andReturn();
        assertThat(contactShow.getResponse().getContentAsString())
            .isEqualTo(jsonWriter.writeOne(railsResources.contact, aliceContact.getId()).toString());
        MvcResult leadShow = mockMvc.perform(get("/api/v1/leads/{id}", newLead.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(leadShow.getResponse().getContentAsString())
            .isEqualTo(jsonWriter.writeOne(railsResources.lead, newLead.getId()).toString());
        doThrow(new IllegalStateException("version store unavailable"))
            .when(recentlyViewedService).recordView(any(), any(), eq(aliceContact.getId()));
        mockMvc.perform(get("/api/v1/contacts/{id}.vcf", aliceContact.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/contacts/{id}", bobPrivateContact.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/leads/{id}.vcf", bobPrivateLead.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/contacts/999999").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/leads/999999.vcf").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/contacts/not-a-number").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/leads/{id}", aliceContact.getId()))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/contacts/{id}.vcf", aliceContact.getId()))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/leads/{id}.vcf", newLead.getId()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void autocompleteScopesResultsAppliesAllRelatedExclusionsAndLimitsResults() throws Exception {
        List<Contact> manyContacts = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            manyContacts.add(contact(alice, "Needle", "Contact %02d".formatted(index), "Public"));
        }
        JsonNode contactResults = autocomplete("/api/v1/contacts/autocomplete", aliceBearer, "term", "Needle");
        assertThat(contactResults.path("results").size()).isEqualTo(10);
        assertThat(ids(contactResults)).contains(manyContacts.getFirst().getId());
        assertThat(ids(autocomplete("/api/v1/contacts/autocomplete", aliceBearer, "related",
            "accounts/" + account.getId()))).doesNotContain(aliceContact.getId(), bobPublicContact.getId());
        assertThat(ids(autocomplete("/api/v1/contacts/autocomplete", aliceBearer, "related",
            "opportunities/" + opportunity.getId()))).doesNotContain(aliceContact.getId());
        assertThat(ids(autocomplete("/api/v1/contacts/autocomplete", aliceBearer, "related",
            "users/" + alice.getId()))).doesNotContain(aliceContact.getId());
        assertThat(ids(autocomplete("/api/v1/contacts/autocomplete", aliceBearer, "related",
            aliceContact.getId().toString()))).doesNotContain(aliceContact.getId());
        assertThat(ids(autocomplete("/api/v1/contacts/autocomplete", aliceBearer, "related",
            "accounts/999999"))).contains(aliceContact.getId());
        assertThat(ids(autocomplete("/api/v1/contacts/autocomplete", aliceBearer, "related",
            "users/" + bob.getId()))).doesNotContain(bobPublicContact.getId());

        List<Lead> manyLeads = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            manyLeads.add(lead(alice, "Needle", "Lead %02d".formatted(index), "new", "Needle Co"));
        }
        JsonNode leadResults = autocomplete("/api/v1/leads/autocomplete", aliceBearer, "term", "Needle");
        assertThat(leadResults.path("results").size()).isEqualTo(10);
        assertThat(ids(leadResults)).contains(manyLeads.getFirst().getId());
        assertThat(ids(autocomplete("/api/v1/leads/autocomplete", aliceBearer, "related",
            "campaigns/" + campaign.getId()))).doesNotContain(newLead.getId(), bobPublicLead.getId());
        assertThat(ids(autocomplete("/api/v1/leads/autocomplete", aliceBearer, "related",
            "users/" + alice.getId()))).doesNotContain(newLead.getId());
        assertThat(ids(autocomplete("/api/v1/leads/autocomplete", aliceBearer, "related",
            bobPrivateLead.getId().toString()))).contains(newLead.getId());
        assertThat(ids(autocomplete("/api/v1/leads/autocomplete", aliceBearer, "related",
            "users/" + bob.getId()))).doesNotContain(bobPublicLead.getId());

        mockMvc.perform(get("/api/v1/leads/autocomplete"))
            .andExpect(status().isUnauthorized());
    }

    private JsonNode autocomplete(String path, String bearer, String param, String value) throws Exception {
        return JSON.readTree(mockMvc.perform(get(path).param(param, value)
                .header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
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

    private Account account(User owner, String name) {
        Account account = new Account();
        account.setName(name);
        account.setAccess("Public");
        account.setUser(owner);
        return accountRepository.saveAndFlush(account);
    }

    private Campaign campaign(User owner, String name) {
        Campaign campaign = new Campaign();
        campaign.setName(name);
        campaign.setUser(owner);
        campaign.setAccess("Public");
        return campaignRepository.saveAndFlush(campaign);
    }

    private Opportunity opportunity(User owner, String name, Campaign campaign) {
        Opportunity opportunity = new Opportunity();
        opportunity.setName(name);
        opportunity.setCampaign(campaign);
        opportunity.setUser(owner);
        opportunity.setAccess("Public");
        return opportunityRepository.saveAndFlush(opportunity);
    }

    private Contact contact(User owner, String firstName, String lastName, String access) {
        Contact contact = new Contact();
        contact.setFirstName(firstName);
        contact.setLastName(lastName);
        contact.setEmail(firstName.toLowerCase() + "@example.test");
        contact.setAccess(access);
        contact.setUser(owner);
        return contactRepository.saveAndFlush(contact);
    }

    private void accountContact(Contact contact, Account account) {
        AccountContact link = new AccountContact();
        link.setContact(contact);
        link.setAccount(account);
        accountContactRepository.saveAndFlush(link);
    }

    private ContactOpportunity link(Contact contact, Opportunity opportunity) {
        ContactOpportunity link = new ContactOpportunity();
        link.setContact(contact);
        link.setOpportunity(opportunity);
        return link;
    }

    private void address(Contact contact, String type, String street1, String street2) {
        Address address = new Address();
        address.setAddressableModelType(RailsModelType.CONTACT);
        address.setAddressableId(contact.getId().intValue());
        address.setAddressType(type);
        address.setStreet1(street1);
        address.setStreet2(street2);
        addressRepository.saveAndFlush(address);
    }

    private Lead lead(User owner, String firstName, String lastName, String state, String company) {
        Lead lead = new Lead();
        lead.setFirstName(firstName);
        lead.setLastName(lastName);
        lead.setCompany(company);
        lead.setEmail(firstName.toLowerCase() + "@example.test");
        lead.setStatus(state);
        lead.setUser(owner);
        lead.setAccess("Public");
        return leadRepository.saveAndFlush(lead);
    }

    private void preference(User owner, String name, String jsonValue) {
        Preference preference = new Preference();
        preference.setUser(owner);
        preference.setName(name);
        preference.setJsonValue(jsonValue);
        preferenceRepository.saveAndFlush(preference);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private void assertRailsViewVersion(String type, Long itemId, User viewer) {
        List<Map<String, Object>> versions = jdbcTemplate.queryForList(
            "SELECT * FROM versions WHERE item_type = ? AND item_id = ? AND event = 'view'",
            type, itemId.intValue());
        assertThat(versions).hasSize(1);
        Map<String, Object> version = versions.getFirst();
        assertThat(version).containsEntry("item_type", type)
            .containsEntry("item_id", itemId.intValue())
            .containsEntry("event", "view")
            .containsEntry("whodunnit", viewer.getId().toString())
            .containsEntry("object", null)
            .containsEntry("object_changes", null)
            .containsEntry("related_id", null)
            .containsEntry("related_type", null)
            .containsEntry("transaction_id", null);
    }

    private static List<Long> ids(JsonNode body) {
        List<Long> ids = new ArrayList<>();
        body.path("results").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM settings WHERE name = 'lead_status'");
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM preferences");
        jdbcTemplate.update("DELETE FROM taggings");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM contact_opportunities");
        jdbcTemplate.update("DELETE FROM account_contacts");
        jdbcTemplate.update("DELETE FROM addresses");
        jdbcTemplate.update("DELETE FROM leads");
        jdbcTemplate.update("DELETE FROM contacts");
        jdbcTemplate.update("DELETE FROM opportunities");
        jdbcTemplate.update("DELETE FROM campaigns");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
