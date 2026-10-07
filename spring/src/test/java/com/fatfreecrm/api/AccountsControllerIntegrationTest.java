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
import com.fatfreecrm.domain.Preference;
import com.fatfreecrm.domain.Tag;
import com.fatfreecrm.domain.Tagging;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.PreferenceRepository;
import com.fatfreecrm.repository.TagRepository;
import com.fatfreecrm.repository.TaggingRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.service.json.RailsJsonWriter;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.read.RecentlyViewedService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
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

class AccountsControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

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
    private Account aliceCustomer;
    private Account aliceOther;
    private Account bobPublic;
    private Account bobPrivate;
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

        aliceCustomer = account(alice, "Zulu Customer", "customer", "Public");
        aliceOther = account(alice, "Alpha Account", null, "Private");
        bobPublic = account(bob, "Bravo Public", "partner", "Public");
        bobPrivate = account(bob, "Hidden Private", "vendor", "Private");
        addTag(aliceCustomer, "vip");
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void requiresAuthenticationForListAndAutocomplete() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/accounts/autocomplete")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsRailsRowsInsideListEnvelopeWithFacets() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.perPage").value(20))
            .andExpect(jsonPath("$.totalCount").value(3))
            .andExpect(jsonPath("$.totalPages").value(1))
            .andExpect(jsonPath("$.facets.category.all").value(3))
            .andExpect(jsonPath("$.facets.category.customer").value(1))
            .andExpect(jsonPath("$.facets.category.partner").value(1))
            .andExpect(jsonPath("$.facets.category.other").value(1))
            .andExpect(jsonPath("$.items[0].tag_list").isArray())
            .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("items").findValuesAsText("name"))
            .contains("Zulu Customer", "Alpha Account", "Bravo Public");
        assertThat(body.path("items").findValue("user_id").isNumber()).isTrue();

        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(4));
    }

    @Test
    void appliesCategoryFilterRailsOtherSemanticsAndIgnoresItWithAdvancedSearch() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").param("category", "")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(3));

        mockMvc.perform(get("/api/v1/accounts").param("category", "customer,other")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(2));

        mockMvc.perform(get("/api/v1/accounts").param("category", "other")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Alpha Account"));

        mockMvc.perform(get("/api/v1/accounts").param("category", "vendor")
                .param("q[name_cont]", "Zulu")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Zulu Customer"));
    }

    @Test
    void preferencesSupplyDefaultsButExplicitParametersWin() throws Exception {
        preference(alice, "accounts_per_page", "2");
        preference(alice, "accounts_sort_by", "\"accounts.name ASC\"");

        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.perPage").value(2))
            .andExpect(jsonPath("$.items[0].name").value("Alpha Account"))
            .andExpect(jsonPath("$.items[1].name").value("Bravo Public"));

        aliceCustomer.setRating(5);
        accountRepository.saveAndFlush(aliceCustomer);
        mockMvc.perform(get("/api/v1/accounts").param("per_page", "1").param("sort_by", "rating")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.perPage").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Zulu Customer"));
    }

    @Test
    void ignoresInvalidPreferenceValues() throws Exception {
        preference(alice, "accounts_per_page", "\"not a number\"");
        preference(alice, "accounts_sort_by", "[]");

        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.perPage").value(20));
    }

    @Test
    void acceptsNumericStringPreferencePerPage() throws Exception {
        preference(alice, "accounts_per_page", "\"2\"");

        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.perPage").value(2));
    }

    @Test
    void showReturnsSerializerBodyAndRecordsRailsVersionRow() throws Exception {
        Instant startedAt = Instant.now();
        MvcResult result = mockMvc.perform(get("/api/v1/accounts/{id}", aliceCustomer.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString())
            .isEqualTo(jsonWriter.writeOne(railsResources.account, aliceCustomer.getId()).toString());
        List<Map<String, Object>> versions = jdbcTemplate.queryForList(
            "SELECT * FROM versions WHERE item_type = 'Account' AND item_id = ?", aliceCustomer.getId().intValue()
        );
        assertThat(versions).hasSize(1);
        Map<String, Object> version = versions.getFirst();
        assertThat(version).containsEntry("item_type", "Account")
            .containsEntry("item_id", aliceCustomer.getId().intValue())
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
            .when(recentlyViewedService).recordView(any(), any(), eq(aliceCustomer.getId()));

        mockMvc.perform(get("/api/v1/accounts/{id}", aliceCustomer.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Zulu Customer"));
    }

    @Test
    void showDistinguishesMissingForbiddenAnonymousAndNonNumericIds() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/999999").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/accounts/{id}", bobPrivate.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/accounts/{id}", aliceCustomer.getId()))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/accounts/not-a-number").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void autocompleteScopesSearchesExcludesRelatedAndLimitsToTen() throws Exception {
        List<Account> matches = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            matches.add(account(alice, "Needle Account %02d".formatted(index), "customer", "Public"));
        }

        MvcResult termResult = mockMvc.perform(get("/api/v1/accounts/autocomplete").param("term", "Needle")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode termBody = JSON.readTree(termResult.getResponse().getContentAsString());
        assertThat(termBody.path("results").size()).isEqualTo(10);
        assertThat(termBody.path("results").get(0).path("id").asLong()).isEqualTo(matches.getFirst().getId());
        assertThat(termBody.path("results").get(0).path("text").asText()).startsWith("Needle");

        JsonNode blankBody = JSON.readTree(mockMvc.perform(get("/api/v1/accounts/autocomplete")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(blankBody.path("results").size()).isEqualTo(10);
        assertThat(blankBody.path("results").findValuesAsText("text"))
            .doesNotContain("Hidden Private");
        JsonNode adminHidden = JSON.readTree(mockMvc.perform(get("/api/v1/accounts/autocomplete")
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

        JsonNode precedence = JSON.readTree(mockMvc.perform(get("/api/v1/accounts/autocomplete")
                .param("excludeRelated", bobPublic.getId().toString())
                .param("related", aliceCustomer.getId().toString())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ids(precedence)).contains(aliceCustomer.getId()).doesNotContain(bobPublic.getId());
    }

    private JsonNode autocomplete(String name, String value) throws Exception {
        return JSON.readTree(mockMvc.perform(get("/api/v1/accounts/autocomplete")
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

    private Account account(User owner, String name, String category, String access) {
        Account account = new Account();
        account.setName(name);
        account.setEmail(name.toLowerCase().replace(' ', '.') + "@example.test");
        account.setCategory(category);
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

    private void preference(User user, String name, String json) {
        Preference preference = new Preference();
        preference.setUser(user);
        preference.setName(name);
        preference.setJsonValue(json);
        preferenceRepository.saveAndFlush(preference);
    }

    private void addTag(Account account, String name) {
        Tag tag = new Tag();
        tag.setName(name);
        tagRepository.saveAndFlush(tag);
        Tagging tagging = new Tagging();
        tagging.setTag(tag);
        tagging.setTaggableType("Account");
        tagging.setTaggableId(account.getId().intValue());
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
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
