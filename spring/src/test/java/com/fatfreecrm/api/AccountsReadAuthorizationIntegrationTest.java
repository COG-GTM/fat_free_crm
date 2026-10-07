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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rails {@code Ability#initialize} grants non-admins access to an account when it is Public, owned by them,
 * assigned to them, or Shared with them directly or through a group in {@code permissions}. Every read
 * endpoint added by AB-270 (index, show, autocomplete) must enforce exactly that set and nothing wider.
 */
class AccountsReadAuthorizationIntegrationTest extends AbstractPostgresIntegrationTest {

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

    private User owner;
    private User grantee;
    private User outsider;
    private User admin;
    private Account sharedDirect;
    private Account sharedViaGroup;
    private Account assigned;
    private Account privateOnly;
    private Account publicAccount;
    private long groupId;

    @BeforeEach
    void seed() {
        clearData();
        owner = user("owner", false);
        grantee = user("grantee", false);
        outsider = user("outsider", false);
        admin = user("admin", true);

        sharedDirect = account(owner, "Shared Direct", "customer", "Shared");
        sharedViaGroup = account(owner, "Shared Group", "partner", "Shared");
        assigned = account(owner, "Assigned Account", "vendor", "Private");
        privateOnly = account(owner, "Owner Secret", "vendor", "Private");
        publicAccount = account(owner, "Public Account", "customer", "Public");

        jdbcTemplate.update("UPDATE accounts SET assigned_to = ? WHERE id = ?", grantee.getId(), assigned.getId());
        grantToUser(sharedDirect, grantee);
        groupId = group("sales");
        addMember(groupId, grantee);
        grantToGroup(sharedViaGroup, groupId);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void granteeSeesSharedAssignedAndPublicRowsAcrossAllReadEndpoints() throws Exception {
        List<Long> expected = List.of(
            sharedDirect.getId(), sharedViaGroup.getId(), assigned.getId(), publicAccount.getId());

        assertThat(listIds(grantee)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(autocompleteIds(grantee, "")).containsExactlyInAnyOrderElementsOf(expected);
        for (Long id : expected) {
            mockMvc.perform(get("/api/v1/accounts/" + id).header(HttpHeaders.AUTHORIZATION, bearer(grantee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        }
        mockMvc.perform(get("/api/v1/accounts/" + privateOnly.getId())
                .header(HttpHeaders.AUTHORIZATION, bearer(grantee)))
            .andExpect(status().isForbidden());
    }

    @Test
    void outsiderOnlySeesPublicRows() throws Exception {
        assertThat(listIds(outsider)).containsExactly(publicAccount.getId());
        assertThat(autocompleteIds(outsider, "")).containsExactly(publicAccount.getId());
        assertThat(autocompleteIds(outsider, "Shared")).isEmpty();
        assertThat(autocompleteIds(outsider, "Secret")).isEmpty();

        for (Account hidden : List.of(sharedDirect, sharedViaGroup, assigned, privateOnly)) {
            mockMvc.perform(get("/api/v1/accounts/" + hidden.getId())
                    .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void listFacetsAndFiltersNeverCountInaccessibleRows() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.facets.category.all").value(1))
            .andExpect(jsonPath("$.facets.category.customer").value(1))
            .andExpect(jsonPath("$.facets.category.vendor").value(0))
            .andExpect(jsonPath("$.facets.category.partner").value(0));

        mockMvc.perform(get("/api/v1/accounts").param("category", "vendor")
                .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(0));

        mockMvc.perform(get("/api/v1/accounts").param("q[name_cont]", "Secret")
                .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(0));

        mockMvc.perform(get("/api/v1/accounts").param("query", "Secret")
                .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(0));
    }

    @Test
    void permissionsForOtherUsersOrGroupsDoNotLeak() throws Exception {
        long otherGroup = group("marketing");
        addMember(otherGroup, outsider);

        assertThat(listIds(outsider)).containsExactly(publicAccount.getId());
        mockMvc.perform(get("/api/v1/accounts/" + sharedViaGroup.getId())
                .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/accounts/" + sharedDirect.getId())
                .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
            .andExpect(status().isForbidden());
    }

    @Test
    void groupGrantStopsWhenGroupRowIsGoneEvenIfMembershipRowRemains() throws Exception {
        jdbcTemplate.update("DELETE FROM groups WHERE id = ?", groupId);

        assertThat(listIds(grantee)).doesNotContain(sharedViaGroup.getId())
            .contains(sharedDirect.getId(), assigned.getId(), publicAccount.getId());
        mockMvc.perform(get("/api/v1/accounts/" + sharedViaGroup.getId())
                .header(HttpHeaders.AUTHORIZATION, bearer(grantee)))
            .andExpect(status().isForbidden());
    }

    @Test
    void revokingDirectPermissionImmediatelyHidesTheRow() throws Exception {
        jdbcTemplate.update("DELETE FROM permissions WHERE asset_id = ? AND user_id = ?",
            sharedDirect.getId(), grantee.getId());

        assertThat(listIds(grantee)).doesNotContain(sharedDirect.getId());
        assertThat(autocompleteIds(grantee, "Direct")).isEmpty();
        mockMvc.perform(get("/api/v1/accounts/" + sharedDirect.getId())
                .header(HttpHeaders.AUTHORIZATION, bearer(grantee)))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminSeesEveryRowIncludingPrivateOnes() throws Exception {
        assertThat(listIds(admin)).containsExactlyInAnyOrder(
            sharedDirect.getId(), sharedViaGroup.getId(), assigned.getId(), privateOnly.getId(),
            publicAccount.getId());
        assertThat(autocompleteIds(admin, "Secret")).containsExactly(privateOnly.getId());
        mockMvc.perform(get("/api/v1/accounts/" + privateOnly.getId()).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Owner Secret"));
    }

    @Test
    void deniedShowsNeverRecordARecentlyViewedVersion() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + privateOnly.getId())
                .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/accounts/" + privateOnly.getId()))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/accounts/999999").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
            .andExpect(status().isNotFound());

        Integer versions = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM versions", Integer.class);
        assertThat(versions).isZero();
    }

    @Test
    void successfulShowRecordsWhodunnitOfTheViewerNotTheOwner() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + publicAccount.getId())
                .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/accounts/" + publicAccount.getId())
                .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
            .andExpect(status().isOk());

        List<String> whodunnit = jdbcTemplate.queryForList(
            "SELECT whodunnit FROM versions WHERE item_type = 'Account' AND item_id = ? AND event = 'view'"
                + " ORDER BY id",
            String.class,
            publicAccount.getId());
        assertThat(whodunnit).containsExactly(String.valueOf(outsider.getId()), String.valueOf(outsider.getId()));
    }

    @Test
    void listPreferencesAreScopedToTheUserAndController() throws Exception {
        preference(owner, "accounts_per_page", "1");
        preference(grantee, "contacts_per_page", "2");
        preference(grantee, "accounts_sort_by", "\"accounts.name ASC\"");

        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.perPage").value(1))
            .andExpect(jsonPath("$.totalPages").value(5));

        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer(grantee)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.perPage").value(20))
            .andExpect(jsonPath("$.items[0].name").value("Assigned Account"))
            .andExpect(jsonPath("$.items[1].name").value("Public Account"))
            .andExpect(jsonPath("$.items[2].name").value("Shared Direct"))
            .andExpect(jsonPath("$.items[3].name").value("Shared Group"));
    }

    private List<Long> listIds(User viewer) throws Exception {
        JsonNode body = JSON.readTree(mockMvc.perform(get("/api/v1/accounts").param("per_page", "100")
                .header(HttpHeaders.AUTHORIZATION, bearer(viewer)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<Long> ids = new ArrayList<>();
        body.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private List<Long> autocompleteIds(User viewer, String term) throws Exception {
        JsonNode body = JSON.readTree(mockMvc.perform(get("/api/v1/accounts/autocomplete").param("term", term)
                .header(HttpHeaders.AUTHORIZATION, bearer(viewer)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<Long> ids = new ArrayList<>();
        body.path("results").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
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

    private Account account(User accountOwner, String name, String category, String access) {
        Account account = new Account();
        account.setName(name);
        account.setEmail(name.toLowerCase().replace(' ', '.') + "@example.test");
        account.setCategory(category);
        account.setAccess(access);
        account.setUser(accountOwner);
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

    private long group(String name) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO groups (name, created_at, updated_at) VALUES (?, NOW(), NOW()) RETURNING id",
            Long.class,
            name);
    }

    private void addMember(long group, User member) {
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", group, member.getId());
    }

    private void grantToUser(Account account, User user) {
        jdbcTemplate.update(
            "INSERT INTO permissions (user_id, asset_type, asset_id, created_at, updated_at)"
                + " VALUES (?, 'Account', ?, NOW(), NOW())",
            user.getId(), account.getId());
    }

    private void grantToGroup(Account account, long group) {
        jdbcTemplate.update(
            "INSERT INTO permissions (group_id, asset_type, asset_id, created_at, updated_at)"
                + " VALUES (?, 'Account', ?, NOW(), NOW())",
            group, account.getId());
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM preferences");
        jdbcTemplate.update("DELETE FROM permissions");
        jdbcTemplate.update("DELETE FROM groups_users");
        jdbcTemplate.update("DELETE FROM groups");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
