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
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.Preference;
import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.domain.Tag;
import com.fatfreecrm.domain.Tagging;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.CampaignRepository;
import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.repository.OpportunityRepository;
import com.fatfreecrm.repository.PreferenceRepository;
import com.fatfreecrm.repository.SettingRepository;
import com.fatfreecrm.repository.TagRepository;
import com.fatfreecrm.repository.TaggingRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.service.json.RailsJsonWriter;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.read.RecentlyViewedService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

class OpportunitiesControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OpportunityRepository opportunityRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private PreferenceRepository preferenceRepository;

    @Autowired
    private SettingRepository settingRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private TaggingRepository taggingRepository;

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
    private User admin;
    private Opportunity won;
    private Opportunity nullStage;
    private Opportunity unknownStage;
    private Opportunity bobPublic;
    private Opportunity bobPrivate;
    private String aliceBearer;
    private String adminBearer;

    @BeforeEach
    void seed() {
        clearData();

        alice = user("alice", false);
        bob = user("bob", false);
        admin = user("admin", true);
        aliceBearer = bearer(alice);
        adminBearer = bearer(admin);

        won = opportunity(alice, "Won Deal", "won", "Private");
        won.setAmount(new BigDecimal("1500.00"));
        won.setDiscount(BigDecimal.ZERO.setScale(2));
        won.setProbability(50);
        won.setClosesOn(LocalDate.parse("2025-04-01"));
        won = opportunityRepository.saveAndFlush(won);
        nullStage = opportunity(alice, "Nullable Deal", null, "Public");
        nullStage.setAmount(new BigDecimal("0.00"));
        nullStage.setProbability(0);
        nullStage = opportunityRepository.saveAndFlush(nullStage);
        unknownStage = opportunity(alice, "Custom Stage Deal", "custom_stage", "Public");
        unknownStage.setAmount(new BigDecimal("1234.50"));
        unknownStage.setDiscount(new BigDecimal("12.50"));
        unknownStage.setProbability(100);
        unknownStage = opportunityRepository.saveAndFlush(unknownStage);
        opportunity(alice, "Prospecting Deal", "prospecting", "Public");
        bobPublic = opportunity(bob, "Bob Public Deal", "won", "Public");
        bobPrivate = opportunity(bob, "Bob Private Deal", "won", "Private");
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void requiresAuthenticationForListAndAutocomplete() throws Exception {
        mockMvc.perform(get("/api/v1/opportunities")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/opportunities/autocomplete")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsRailsRowsInsideListEnvelopeWithAccessScopedOrderedStageFacets() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/opportunities")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.perPage").value(20))
            .andExpect(jsonPath("$.totalCount").value(5))
            .andExpect(jsonPath("$.facets.stage.all").value(5))
            .andExpect(jsonPath("$.facets.stage.other").value(2))
            .andExpect(jsonPath("$.facets.stage.prospecting").value(1))
            .andExpect(jsonPath("$.facets.stage.analysis").value(0))
            .andExpect(jsonPath("$.facets.stage.won").value(2))
            .andExpect(jsonPath("$.items[0].tag_list").isArray())
            .andReturn();
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("facets").path("stage").fieldNames())
            .toIterable()
            .containsExactly("all", "other", "prospecting", "analysis", "presentation", "proposal",
                "negotiation", "final_review", "won", "lost");
        for (String stage : List.of("analysis", "presentation", "proposal", "negotiation", "final_review", "lost")) {
            assertThat(body.path("facets").path("stage").path(stage).asLong()).isZero();
        }

        mockMvc.perform(get("/api/v1/opportunities").header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(6))
            .andExpect(jsonPath("$.facets.stage.all").value(6))
            .andExpect(jsonPath("$.facets.stage.won").value(3));
    }

    @Test
    void configuredStageSettingAcceptsSymbolsAndFacetsIgnoreSearchAndFilters() throws Exception {
        setting("opportunity_stage", "---\n- :won\n- :custom_stage\n- :prospecting\n");
        MvcResult configured = mockMvc.perform(get("/api/v1/opportunities")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode configuredStages = JSON.readTree(configured.getResponse().getContentAsString())
            .path("facets").path("stage");
        assertThat(configuredStages.fieldNames()).toIterable()
            .containsExactly("all", "other", "won", "custom_stage", "prospecting");
        assertThat(configuredStages.path("won").asLong()).isEqualTo(2);
        assertThat(configuredStages.path("other").asLong()).isEqualTo(1);

        JsonNode filtered = JSON.readTree(mockMvc.perform(get("/api/v1/opportunities")
                .param("stage", "won")
                .param("q[name_cont]", "Won")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(filtered.path("totalCount").asLong()).isEqualTo(1);
        assertThat(filtered.path("facets").path("stage")).isEqualTo(configuredStages);
    }

    @Test
    void appliesStageFilterRailsOtherSemanticsAndIgnoresItWithAdvancedSearch() throws Exception {
        listWithStage("won").andExpect(jsonPath("$.totalCount").value(2));
        listWithStage("won,other").andExpect(jsonPath("$.totalCount").value(3));
        listWithStage("other")
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].id").value(nullStage.getId()));
        listWithStage("custom_stage")
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].id").value(unknownStage.getId()));
        listWithStage("bogus").andExpect(jsonPath("$.totalCount").value(0));
        listWithStage("").andExpect(jsonPath("$.totalCount").value(5));

        mockMvc.perform(get("/api/v1/opportunities").param("stage", "won")
                .param("q[name_cont]", "Nullable")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].id").value(nullStage.getId()));
    }

    @Test
    void preferencesSupplyDefaultsIncludingWeightedSortAndExplicitParametersWin() throws Exception {
        opportunity(alice, "Low Product", "prospecting", "Public", "10.00", 10);
        opportunity(alice, "High Product", "prospecting", "Public", "100.00", 50);
        preference(alice, "opportunities_per_page", "2");
        preference(alice, "opportunities_sort_by", "\"opportunities.amount*probability DESC\"");

        mockMvc.perform(get("/api/v1/opportunities").param("query", "Product")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.perPage").value(2))
            .andExpect(jsonPath("$.items[0].name").value("High Product"));

        mockMvc.perform(get("/api/v1/opportunities").param("query", "Product").param("per_page", "5")
                .param("sort_by", "name ASC")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.perPage").value(5))
            .andExpect(jsonPath("$.items[0].name").value("High Product"));

        preference(alice, "opportunities_sort_by", "\"opportunities.amount*probability DESC\"");
        mockMvc.perform(get("/api/v1/opportunities").param("query", "Product").param("per_page", "20")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].name").value("High Product"));
    }

    @Test
    void weightedSortPlacesNullProductsWithRailsPostgresOrdering() throws Exception {
        opportunity(alice, "Null Product", "prospecting", "Public", null, 90);
        opportunity(alice, "Second Product", "prospecting", "Public", "20.00", 50);
        mockMvc.perform(get("/api/v1/opportunities").param("stage", "prospecting")
                .param("sort_by", "amount*probability DESC")
                .param("per_page", "20")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].name").value("Prospecting Deal"))
            .andExpect(jsonPath("$.items[1].name").value("Null Product"))
            .andExpect(jsonPath("$.items[2].name").value("Second Product"));
    }

    @Test
    void showSerializesRailsDecimalsDatesTagsAndRecordsViewVersion() throws Exception {
        addTag(won, "vip");
        MvcResult result = mockMvc.perform(get("/api/v1/opportunities/{id}", won.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amount").value("1500.0"))
            .andExpect(jsonPath("$.discount").value("0.0"))
            .andExpect(jsonPath("$.probability").value(50))
            .andExpect(jsonPath("$.closes_on").value("2025-04-01"))
            .andExpect(jsonPath("$.tag_list[0]").value("vip"))
            .andReturn();
        assertThat(result.getResponse().getContentAsString())
            .isEqualTo(jsonWriter.writeOne(railsResources.opportunity, won.getId()).toString());

        mockMvc.perform(get("/api/v1/opportunities/{id}", unknownStage.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amount").value("1234.5"));
        MvcResult nullResult = mockMvc.perform(get("/api/v1/opportunities/{id}", nullStage.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amount").value("0.0"))
            .andReturn();
        assertThat(JSON.readTree(nullResult.getResponse().getContentAsString()).path("discount").isNull()).isTrue();

        List<Map<String, Object>> versions = jdbcTemplate.queryForList(
            "SELECT * FROM versions WHERE item_type = 'Opportunity' AND item_id = ?", won.getId().intValue()
        );
        assertThat(versions).hasSize(1);
        Map<String, Object> version = versions.getFirst();
        assertThat(version).containsEntry("item_type", "Opportunity")
            .containsEntry("item_id", won.getId().intValue())
            .containsEntry("event", "view")
            .containsEntry("whodunnit", alice.getId().toString())
            .containsEntry("object", null)
            .containsEntry("object_changes", null)
            .containsEntry("related_id", null)
            .containsEntry("related_type", null)
            .containsEntry("transaction_id", null);
    }

    @Test
    void recordingFailureDoesNotFailShowResponse() throws Exception {
        doThrow(new IllegalStateException("version store unavailable"))
            .when(recentlyViewedService).recordView(any(), any(), eq(won.getId()));
        mockMvc.perform(get("/api/v1/opportunities/{id}", won.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Won Deal"));
    }

    @Test
    void showDistinguishesMissingForbiddenAnonymousAndNonNumericIds() throws Exception {
        mockMvc.perform(get("/api/v1/opportunities/999999").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/opportunities/{id}", bobPrivate.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/opportunities/{id}", won.getId()))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/opportunities/not-a-number")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void autocompleteSupportsNumericSearchScopeLimitAndAllRelatedExclusions() throws Exception {
        Opportunity userOpportunity = opportunity(alice, "Needle Owned", "prospecting", "Public");
        Account account = account(alice, "Related Account");
        Contact contact = contact(alice, "Related", "Contact");
        Campaign campaign = campaign(alice, "Related Campaign");
        Opportunity accountOpportunity = opportunity(alice, "Needle Account", "prospecting", "Public");
        Opportunity contactOpportunity = opportunity(alice, "Needle Contact", "prospecting", "Public");
        Opportunity campaignOpportunity = opportunity(alice, "Needle Campaign", "prospecting", "Public");
        jdbcTemplate.update(
            "INSERT INTO account_opportunities (account_id, opportunity_id, created_at, updated_at) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            account.getId(), accountOpportunity.getId());
        jdbcTemplate.update(
            "INSERT INTO contact_opportunities (contact_id, opportunity_id, created_at, updated_at) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            contact.getId(), contactOpportunity.getId());
        campaignOpportunity.setCampaign(campaign);
        opportunityRepository.saveAndFlush(campaignOpportunity);
        List<Opportunity> matches = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            matches.add(opportunity(alice, "Needle Match %02d".formatted(index), "prospecting", "Public"));
        }

        JsonNode termResults = autocomplete("term", "Needle");
        assertThat(termResults.path("results").size()).isEqualTo(10);
        assertThat(termResults.path("results").get(0).path("text").asText()).startsWith("Needle");
        assertThat(ids(termResults))
            .contains(userOpportunity.getId())
            .doesNotContain(bobPrivate.getId());
        assertThat(ids(autocomplete("related", userOpportunity.getId().toString())))
            .doesNotContain(userOpportunity.getId());
        assertThat(ids(autocomplete("excludeRelated", userOpportunity.getId().toString())))
            .doesNotContain(userOpportunity.getId());
        assertThat(ids(autocomplete("related", "users/" + alice.getId()))).doesNotContain(userOpportunity.getId());
        assertThat(ids(autocomplete("related", "accounts/" + account.getId())))
            .doesNotContain(accountOpportunity.getId());
        assertThat(ids(autocomplete("related", "contacts/" + contact.getId())))
            .doesNotContain(contactOpportunity.getId());
        assertThat(ids(autocomplete("related", "campaigns/" + campaign.getId())))
            .doesNotContain(campaignOpportunity.getId());
        assertThat(ids(autocomplete("related", "leads/301"))).contains(userOpportunity.getId());
        assertThat(ids(autocomplete("related", "users/999999"))).contains(userOpportunity.getId());
        assertThat(ids(autocomplete("term", won.getId().toString()))).contains(won.getId());

        JsonNode blankResults = autocomplete(null, null);
        assertThat(blankResults.path("results").size()).isEqualTo(10);
        assertThat(blankResults.path("results").findValuesAsText("text"))
            .doesNotContain("Bob Private Deal");
        JsonNode adminResults = JSON.readTree(mockMvc.perform(get("/api/v1/opportunities/autocomplete")
                .param("term", "Bob Private")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ids(adminResults)).contains(bobPrivate.getId());
    }

    @Test
    void untransactionalListShowAndAutocompleteDoNotRequireOpenInView() throws Exception {
        JsonNode list = JSON.readTree(mockMvc.perform(get("/api/v1/opportunities")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(list.path("items").findValuesAsText("name")).contains("Won Deal");
        mockMvc.perform(get("/api/v1/opportunities/{id}", won.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user_id").value(alice.getId()));
        mockMvc.perform(get("/api/v1/opportunities/autocomplete").param("term", "Won")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results[0].text").value("Won Deal"));
    }

    private org.springframework.test.web.servlet.ResultActions listWithStage(String stage) throws Exception {
        return mockMvc.perform(get("/api/v1/opportunities").param("stage", stage)
            .header(HttpHeaders.AUTHORIZATION, aliceBearer)).andExpect(status().isOk());
    }

    private JsonNode autocomplete(String name, String value) throws Exception {
        var request = get("/api/v1/opportunities/autocomplete").header(HttpHeaders.AUTHORIZATION, aliceBearer);
        if (name != null) {
            request = request.param(name, value);
        }
        return JSON.readTree(mockMvc.perform(request).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
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

    private Opportunity opportunity(User owner, String name, String stage, String access) {
        return opportunity(owner, name, stage, access, null, null);
    }

    private Opportunity opportunity(
        User owner,
        String name,
        String stage,
        String access,
        String amount,
        Integer probability
    ) {
        Opportunity opportunity = new Opportunity();
        opportunity.setName(name);
        opportunity.setStage(stage);
        opportunity.setAccess(access);
        opportunity.setUser(owner);
        opportunity.setAmount(amount == null ? null : new BigDecimal(amount));
        opportunity.setProbability(probability);
        Instant at = Instant.now();
        opportunity.setCreatedAt(at);
        opportunity.setUpdatedAt(at);
        return opportunityRepository.saveAndFlush(opportunity);
    }

    private Account account(User owner, String name) {
        Account account = new Account();
        account.setName(name);
        account.setUser(owner);
        account.setAccess("Public");
        return accountRepository.saveAndFlush(account);
    }

    private Contact contact(User owner, String firstName, String lastName) {
        Contact contact = new Contact();
        contact.setFirstName(firstName);
        contact.setLastName(lastName);
        contact.setUser(owner);
        contact.setAccess("Public");
        return contactRepository.saveAndFlush(contact);
    }

    private Campaign campaign(User owner, String name) {
        Campaign campaign = new Campaign();
        campaign.setName(name);
        campaign.setUser(owner);
        campaign.setAccess("Public");
        return campaignRepository.saveAndFlush(campaign);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private void preference(User user, String name, String json) {
        Preference preference = new Preference();
        preference.setUser(user);
        preference.setName(name);
        preference.setJsonValue(json);
        preferenceRepository.saveAndFlush(preference);
    }

    private void setting(String name, String value) {
        Setting setting = new Setting();
        setting.setName(name);
        setting.setValue(value);
        settingRepository.saveAndFlush(setting);
    }

    private void addTag(Opportunity opportunity, String name) {
        Tag tag = new Tag();
        tag.setName(name);
        tagRepository.saveAndFlush(tag);
        Tagging tagging = new Tagging();
        tagging.setTag(tag);
        tagging.setTaggableType("Opportunity");
        tagging.setTaggableId(opportunity.getId().intValue());
        tagging.setContext("tags");
        taggingRepository.saveAndFlush(tagging);
    }

    private static List<Long> ids(JsonNode body) {
        List<Long> ids = new ArrayList<>();
        body.path("results").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM preferences");
        jdbcTemplate.update("DELETE FROM taggings");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM account_opportunities");
        jdbcTemplate.update("DELETE FROM contact_opportunities");
        jdbcTemplate.update("DELETE FROM opportunities");
        jdbcTemplate.update("DELETE FROM contacts");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM campaigns");
        jdbcTemplate.update("DELETE FROM settings");
        jdbcTemplate.update("DELETE FROM users");
    }
}
