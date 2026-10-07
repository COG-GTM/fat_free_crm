package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Preference;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.PreferenceRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Edge cases of the AB-270 account read endpoints, each derived from the Rails controller:
 * {@code ApplicationController#auto_complete}, {@code EntitiesController#get_list_of_records} and
 * {@code EntitiesController#update_recently_viewed}.
 */
class AccountsReadEdgeCaseIntegrationTest extends AbstractPostgresIntegrationTest {

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
    private JwtTokenService jwtTokenService;

    private User alice;
    private User bob;
    private Account aliceCustomer;
    private Account aliceOther;
    private Account bobPublic;
    private String aliceBearer;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice");
        bob = user("bob");
        aliceBearer = bearer(alice);
        aliceCustomer = account(alice, "Zulu Customer", "customer", "Public");
        aliceOther = account(alice, "Alpha Account", null, "Private");
        bobPublic = account(bob, "Bravo Public", "partner", "Public");
        account(bob, "Hidden Private", "vendor", "Private");
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void autocompleteItemsUseRailsIdTextShapeOrderedById() throws Exception {
        JsonNode body = autocomplete(Map.of());

        assertThat(body.fieldNames()).toIterable().containsExactly("results");
        assertThat(body.path("results")).hasSize(3);
        body.path("results").forEach(item -> assertThat(item.fieldNames()).toIterable().containsExactly("id", "text"));
        assertThat(ids(body)).containsExactly(aliceCustomer.getId(), aliceOther.getId(), bobPublic.getId());
        assertThat(body.path("results").get(0).path("text").asText()).isEqualTo("Zulu Customer");
    }

    @Test
    void autocompleteRelatedWithUnknownOrMalformedIdExcludesNothing() throws Exception {
        List<Long> all = List.of(aliceCustomer.getId(), aliceOther.getId(), bobPublic.getId());

        assertThat(ids(autocomplete(Map.of("related", "users/999999")))).containsExactlyElementsOf(all);
        assertThat(ids(autocomplete(Map.of("related", "users/abc")))).containsExactlyElementsOf(all);
        assertThat(ids(autocomplete(Map.of("related", "abc")))).containsExactlyElementsOf(all);
        assertThat(ids(autocomplete(Map.of("related", "")))).containsExactlyElementsOf(all);
        assertThat(ids(autocomplete(Map.of("related", "/")))).containsExactlyElementsOf(all);
        assertThat(ids(autocomplete(Map.of("related", "users/")))).containsExactlyElementsOf(all);
    }

    @Test
    void autocompleteRelatedUserExcludesOnlyThatUsersOwnedAccounts() throws Exception {
        jdbcTemplate.update("UPDATE accounts SET assigned_to = ? WHERE id = ?", bob.getId(), aliceCustomer.getId());

        assertThat(ids(autocomplete(Map.of("related", "users/" + bob.getId()))))
            .containsExactly(aliceCustomer.getId(), aliceOther.getId());
        assertThat(ids(autocomplete(Map.of("related", "users/" + alice.getId()))))
            .containsExactly(bobPublic.getId());
    }

    @Test
    void autocompleteBareIdExcludesThatAccountAndCombinesWithTerm() throws Exception {
        assertThat(ids(autocomplete(Map.of("related", String.valueOf(aliceCustomer.getId())))))
            .containsExactly(aliceOther.getId(), bobPublic.getId());
        assertThat(ids(autocomplete(Map.of("related", String.valueOf(aliceCustomer.getId()), "term", "zulu"))))
            .isEmpty();
        assertThat(ids(autocomplete(Map.of("term", "ZULU")))).containsExactly(aliceCustomer.getId());
        assertThat(ids(autocomplete(Map.of("term", "no such account")))).isEmpty();
    }

    @Test
    void categoryFilterComposesWithSimpleQueryButNotWithAdvancedSearch() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").param("category", "customer").param("query", "Zulu")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Zulu Customer"));

        mockMvc.perform(get("/api/v1/accounts").param("category", "other").param("query", "Zulu")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(0));

        mockMvc.perform(get("/api/v1/accounts").param("category", "other").param("q[name_cont]", "Zulu")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Zulu Customer"));
    }

    @Test
    void categoryFilterToleratesUnknownValuesAndTrailingCommas() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").param("category", "customer,")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Zulu Customer"));

        mockMvc.perform(get("/api/v1/accounts").param("category", "nonexistent")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(0))
            .andExpect(jsonPath("$.totalPages").value(0))
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.facets.category.all").value(3));

        mockMvc.perform(get("/api/v1/accounts").param("category", "customer,partner,other")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(3));
    }

    @Test
    void facetsIgnoreTheActiveCategoryFilterLikeRailsSidebar() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").param("category", "partner")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.facets.category.all").value(3))
            .andExpect(jsonPath("$.facets.category.customer").value(1))
            .andExpect(jsonPath("$.facets.category.partner").value(1))
            .andExpect(jsonPath("$.facets.category.other").value(1));
    }

    @Test
    void unknownSortPreferenceFallsBackToDefaultOrdering() throws Exception {
        preference(alice, "accounts_sort_by", "\"accounts.bogus ASC; DROP TABLE accounts\"");

        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(3))
            .andExpect(jsonPath("$.items[0].name").value("Bravo Public"))
            .andExpect(jsonPath("$.items[2].name").value("Zulu Customer"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class)).isEqualTo(4);
    }

    @Test
    void sortPreferenceIsHonouredAndExplicitSortByWinsOverIt() throws Exception {
        preference(alice, "accounts_sort_by", "\"accounts.name ASC\"");

        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].name").value("Alpha Account"))
            .andExpect(jsonPath("$.items[1].name").value("Bravo Public"))
            .andExpect(jsonPath("$.items[2].name").value("Zulu Customer"));

        mockMvc.perform(get("/api/v1/accounts").param("sort_by", "accounts.created_at DESC")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].name").value("Bravo Public"))
            .andExpect(jsonPath("$.items[1].name").value("Alpha Account"))
            .andExpect(jsonPath("$.items[2].name").value("Zulu Customer"));
    }

    @Test
    void sortPreferenceOutsideTheRailsSortableWhitelistFallsBackToDefault() throws Exception {
        // Account is `sortable by: ["name ASC", "rating DESC", "created_at DESC", "updated_at DESC"]`;
        // "name DESC" is not an allowed clause, so Rails falls back to the default `created_at DESC`.
        preference(alice, "accounts_sort_by", "\"accounts.name DESC\"");

        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].name").value("Bravo Public"))
            .andExpect(jsonPath("$.items[1].name").value("Alpha Account"))
            .andExpect(jsonPath("$.items[2].name").value("Zulu Customer"));
    }

    @Test
    void showRejectsIdsOutsideTheRailsIntegerRangeWithNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/0").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/accounts/2147483648").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/accounts/9223372036854775807").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM versions", Integer.class)).isZero();
    }

    @Test
    void repeatedShowsAppendOneViewVersionEachWithoutTouchingTheAccount() throws Exception {
        String before = jdbcTemplate.queryForObject(
            "SELECT updated_at::text FROM accounts WHERE id = ?", String.class, aliceCustomer.getId());

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/v1/accounts/" + aliceCustomer.getId())
                    .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Zulu Customer"));
        }

        List<Map<String, Object>> versions = jdbcTemplate.queryForList(
            "SELECT event, item_type, item_id, whodunnit, object FROM versions ORDER BY id");
        assertThat(versions).hasSize(3);
        for (Map<String, Object> version : versions) {
            assertThat(version.get("event")).isEqualTo("view");
            assertThat(version.get("item_type")).isEqualTo("Account");
            assertThat(((Number) version.get("item_id")).longValue()).isEqualTo(aliceCustomer.getId());
            assertThat(version.get("whodunnit")).isEqualTo(String.valueOf(alice.getId()));
            assertThat(version.get("object")).isNull();
        }
        String after = jdbcTemplate.queryForObject(
            "SELECT updated_at::text FROM accounts WHERE id = ?", String.class, aliceCustomer.getId());
        assertThat(after).isEqualTo(before);
    }

    private JsonNode autocomplete(Map<String, String> params) throws Exception {
        var request = get("/api/v1/accounts/autocomplete").header(HttpHeaders.AUTHORIZATION, aliceBearer);
        params.forEach(request::param);
        return JSON.readTree(mockMvc.perform(request)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<Long> ids(JsonNode body) {
        List<Long> ids = new ArrayList<>();
        body.path("results").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(false);
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

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM preferences");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
