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
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Preference;
import com.fatfreecrm.domain.Tag;
import com.fatfreecrm.domain.Tagging;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.CampaignRepository;
import com.fatfreecrm.repository.PreferenceRepository;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

class CampaignsControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private PreferenceRepository preferenceRepository;

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
    private Campaign alicePlanned;
    private Campaign aliceOther;
    private Campaign aliceActive;
    private Campaign bobPublic;
    private Campaign bobPrivate;
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

        alicePlanned = campaign(alice, "Zulu Planned", "planned", "Public");
        aliceOther = campaign(alice, "Alpha Campaign", null, "Private");
        aliceActive = campaign(alice, "Active Custom", "active", "Public");
        bobPublic = campaign(bob, "Bravo Public", "started", "Public");
        bobPrivate = campaign(bob, "Hidden Private", "completed", "Private");
        addTag(alicePlanned, "vip");
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void requiresAuthenticationForListAndAutocomplete() throws Exception {
        mockMvc.perform(get("/api/v1/campaigns")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/campaigns/autocomplete")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsRailsRowsInsideListEnvelopeWithFacets() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/campaigns")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.perPage").value(20))
            .andExpect(jsonPath("$.totalCount").value(4))
            .andExpect(jsonPath("$.totalPages").value(1))
            .andExpect(jsonPath("$.facets.status.all").value(4))
            .andExpect(jsonPath("$.facets.status.other").value(2))
            .andExpect(jsonPath("$.facets.status.planned").value(1))
            .andExpect(jsonPath("$.facets.status.started").value(1))
            .andExpect(jsonPath("$.facets.status.completed").value(0))
            .andExpect(jsonPath("$.facets.status.on_hold").value(0))
            .andExpect(jsonPath("$.facets.status.called_off").value(0))
            .andExpect(jsonPath("$.items[0].tag_list").isArray())
            .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("items").findValuesAsText("name"))
            .contains("Zulu Planned", "Alpha Campaign", "Active Custom", "Bravo Public");
        assertThat(body.path("items").findValue("user_id").isNumber()).isTrue();
        assertThat(body.path("items").get(0).path("subscribed_users").isArray()).isTrue();
        for (JsonNode item : body.path("items")) {
            assertThat(item.has("custom_fields")).isFalse();
        }

        JsonNode facetKeys = body.path("facets").path("status");
        List<String> keys = new ArrayList<>();
        facetKeys.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("all", "other", "planned", "started", "completed", "on_hold", "called_off");

        mockMvc.perform(get("/api/v1/campaigns").header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(5));
    }

    @Test
    void appliesStatusFilterRailsOtherSemanticsAndIgnoresItWithAdvancedSearch() throws Exception {
        mockMvc.perform(get("/api/v1/campaigns").param("status", "")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(4));

        mockMvc.perform(get("/api/v1/campaigns").param("status", "planned")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Zulu Planned"));

        mockMvc.perform(get("/api/v1/campaigns").param("status", "planned,other")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(2));

        mockMvc.perform(get("/api/v1/campaigns").param("status", "other")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Alpha Campaign"));

        mockMvc.perform(get("/api/v1/campaigns").param("status", "active")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Active Custom"));

        mockMvc.perform(get("/api/v1/campaigns").param("status", "started")
                .param("q[name_cont]", "Zulu")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Zulu Planned"));

        mockMvc.perform(get("/api/v1/campaigns").param("status", "started")
                .param("query", "Bravo")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Bravo Public"));
    }

    @Test
    void preferencesSupplyDefaultsButExplicitParametersWin() throws Exception {
        preference(alice, "campaigns_per_page", "2");
        preference(alice, "campaigns_sort_by", "\"campaigns.name ASC\"");

        mockMvc.perform(get("/api/v1/campaigns").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.perPage").value(2))
            .andExpect(jsonPath("$.items[0].name").value("Active Custom"))
            .andExpect(jsonPath("$.items[1].name").value("Alpha Campaign"));

        alicePlanned.setTargetLeads(99);
        campaignRepository.saveAndFlush(alicePlanned);
        mockMvc.perform(get("/api/v1/campaigns").param("per_page", "1").param("sort_by", "target_leads")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.perPage").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Zulu Planned"));
    }

    @Test
    void showReturnsSerializerBodyAndRecordsRailsVersionRow() throws Exception {
        Instant startedAt = Instant.now();
        MvcResult result = mockMvc.perform(get("/api/v1/campaigns/{id}", alicePlanned.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString())
            .isEqualTo(jsonWriter.writeOne(railsResources.campaign, alicePlanned.getId()).toString());
        List<Map<String, Object>> versions = jdbcTemplate.queryForList(
            "SELECT * FROM versions WHERE item_type = 'Campaign' AND item_id = ?", alicePlanned.getId().intValue()
        );
        assertThat(versions).hasSize(1);
        Map<String, Object> version = versions.getFirst();
        assertThat(version).containsEntry("item_type", "Campaign")
            .containsEntry("item_id", alicePlanned.getId().intValue())
            .containsEntry("event", "view")
            .containsEntry("whodunnit", alice.getId().toString())
            .containsEntry("object", null)
            .containsEntry("object_changes", null)
            .containsEntry("related_id", null)
            .containsEntry("related_type", null)
            .containsEntry("transaction_id", null);
        String createdAt = jdbcTemplate.queryForObject(
            "SELECT to_char(created_at, 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM versions WHERE id = ?",
            String.class,
            version.get("id")
        );
        Instant recordedAt = LocalDateTime.parse(createdAt.substring(0, 26).replace(' ', 'T'))
            .toInstant(ZoneOffset.UTC);
        assertThat(recordedAt).isBetween(startedAt.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    @Test
    void recordingFailureDoesNotFailShowResponse() throws Exception {
        doThrow(new IllegalStateException("version store unavailable"))
            .when(recentlyViewedService).recordView(any(), any(), eq(alicePlanned.getId()));

        mockMvc.perform(get("/api/v1/campaigns/{id}", alicePlanned.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Zulu Planned"));
    }

    @Test
    void showDistinguishesMissingForbiddenAnonymousAndNonNumericIds() throws Exception {
        mockMvc.perform(get("/api/v1/campaigns/999999").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/campaigns/{id}", bobPrivate.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/campaigns/{id}", alicePlanned.getId()))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/campaigns/not-a-number").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void autocompleteScopesSearchesExcludesRelatedAndLimitsToTen() throws Exception {
        List<Campaign> matches = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            matches.add(campaign(alice, "Needle Campaign %02d".formatted(index), "planned", "Public"));
        }

        MvcResult termResult = mockMvc.perform(get("/api/v1/campaigns/autocomplete").param("term", "Needle")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode termBody = JSON.readTree(termResult.getResponse().getContentAsString());
        assertThat(termBody.path("results").size()).isEqualTo(10);
        assertThat(termBody.path("results").get(0).path("id").asLong()).isEqualTo(matches.getFirst().getId());
        assertThat(termBody.path("results").get(0).path("text").asText()).startsWith("Needle");

        JsonNode blankBody = JSON.readTree(mockMvc.perform(get("/api/v1/campaigns/autocomplete")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(blankBody.path("results").size()).isEqualTo(10);
        assertThat(blankBody.path("results").findValuesAsText("text"))
            .doesNotContain("Hidden Private");
        JsonNode adminHidden = JSON.readTree(mockMvc.perform(get("/api/v1/campaigns/autocomplete")
                .param("term", "Hidden")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ids(adminHidden)).contains(bobPrivate.getId());

        JsonNode bareRelated = autocomplete("related", aliceOther.getId().toString());
        assertThat(ids(bareRelated)).doesNotContain(aliceOther.getId());
        JsonNode relatedUser = autocomplete("related", "users/" + bob.getId());
        assertThat(ids(relatedUser)).doesNotContain(bobPublic.getId());
        assertThat(ids(autocomplete("related", "contacts/201"))).contains(bobPublic.getId());
        assertThat(ids(autocomplete("related", "unknown/201"))).contains(bobPublic.getId());

        JsonNode precedence = JSON.readTree(mockMvc.perform(get("/api/v1/campaigns/autocomplete")
                .param("excludeRelated", bobPublic.getId().toString())
                .param("related", alicePlanned.getId().toString())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ids(precedence)).contains(alicePlanned.getId()).doesNotContain(bobPublic.getId());
    }

    private JsonNode autocomplete(String name, String value) throws Exception {
        return JSON.readTree(mockMvc.perform(get("/api/v1/campaigns/autocomplete")
                .param(name, value)
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
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

    private Campaign campaign(User owner, String name, String status, String access) {
        Campaign campaign = new Campaign();
        campaign.setName(name);
        campaign.setStatus(status);
        campaign.setAccess(access);
        campaign.setUser(owner);
        campaign.setBudget(new BigDecimal("100.50"));
        campaign.setTargetLeads(10);
        campaign.setTargetRevenue(new BigDecimal("200.75"));
        campaign.setRevenue(new BigDecimal("150.25"));
        campaign.setStartsOn(LocalDate.parse("2025-03-01"));
        campaign.setEndsOn(LocalDate.parse("2025-04-01"));
        Instant at = Instant.now();
        campaign.setCreatedAt(at);
        campaign.setUpdatedAt(at);
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

    private void addTag(Campaign campaign, String name) {
        Tag tag = new Tag();
        tag.setName(name);
        tagRepository.saveAndFlush(tag);
        Tagging tagging = new Tagging();
        tagging.setTag(tag);
        tagging.setTaggableType("Campaign");
        tagging.setTaggableId(campaign.getId().intValue());
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
        jdbcTemplate.update("DELETE FROM campaigns");
        jdbcTemplate.update("DELETE FROM users");
    }
}
