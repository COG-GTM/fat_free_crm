package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Group;
import com.fatfreecrm.domain.Permission;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.GroupRepository;
import com.fatfreecrm.repository.PermissionRepository;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * Rails {@code Ability} parity for the Shared access level across the three AB-270 read endpoints:
 * {@code Account.my(user)} includes rows that are Public, owned, assigned, or granted through a
 * user or group permission row, and {@code can :manage} on {@code show} follows the same rule.
 */
class AccountsControllerAccessIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User owner;
    private User member;
    private User outsider;
    private Account sharedWithMember;
    private Account sharedWithGroup;
    private Account sharedWithNobody;
    private Account assignedToMember;
    private Account privateAccount;
    private String memberBearer;
    private String outsiderBearer;

    @BeforeEach
    void seed() {
        clearData();
        owner = user("share_owner");
        member = user("share_member");
        outsider = user("share_outsider");
        memberBearer = bearer(member);
        outsiderBearer = bearer(outsider);

        sharedWithMember = account(owner, "Shared With Member", "Shared");
        sharedWithGroup = account(owner, "Shared With Group", "Shared");
        sharedWithNobody = account(owner, "Shared With Nobody", "Shared");
        assignedToMember = account(owner, "Assigned Private", "Private");
        assignedToMember.setAssignedTo(member);
        accountRepository.saveAndFlush(assignedToMember);
        privateAccount = account(owner, "Owner Private", "Private");

        userPermission(sharedWithMember, member);
        Group sales = new Group();
        sales.setName("sales");
        groupRepository.saveAndFlush(sales);
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)",
            sales.getId(), member.getId());
        groupPermission(sharedWithGroup, sales);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void listIncludesSharedAssignedRowsAndExcludesUnsharedOnes() throws Exception {
        assertThat(listNames(memberBearer))
            .containsExactlyInAnyOrder("Shared With Member", "Shared With Group", "Assigned Private");
        assertThat(listNames(outsiderBearer)).isEmpty();
        assertThat(listNames(bearer(owner))).hasSize(5);
    }

    @Test
    void listFacetsOnlyCountVisibleRows() throws Exception {
        jdbcTemplate.update("UPDATE accounts SET category = 'customer' WHERE id IN (?, ?)",
            sharedWithMember.getId(), sharedWithNobody.getId());

        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, memberBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(3))
            .andExpect(jsonPath("$.facets.category.all").value(3))
            .andExpect(jsonPath("$.facets.category.customer").value(1))
            .andExpect(jsonPath("$.facets.category.other").value(2));

        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, outsiderBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(0))
            .andExpect(jsonPath("$.facets.category.all").value(0))
            .andExpect(jsonPath("$.facets.category.customer").value(0));
    }

    @Test
    void showAllowsSharedAssignedRowsAndForbidsUnsharedOnes() throws Exception {
        for (Account visible : List.of(sharedWithMember, sharedWithGroup, assignedToMember)) {
            mockMvc.perform(get("/api/v1/accounts/{id}", visible.getId())
                    .header(HttpHeaders.AUTHORIZATION, memberBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(visible.getId()))
                .andExpect(jsonPath("$.name").value(visible.getName()));
        }
        for (Account hidden : List.of(sharedWithNobody, privateAccount)) {
            mockMvc.perform(get("/api/v1/accounts/{id}", hidden.getId())
                    .header(HttpHeaders.AUTHORIZATION, memberBearer))
                .andExpect(status().isForbidden());
        }
        for (Account account : List.of(sharedWithMember, sharedWithGroup, assignedToMember, sharedWithNobody)) {
            mockMvc.perform(get("/api/v1/accounts/{id}", account.getId())
                    .header(HttpHeaders.AUTHORIZATION, outsiderBearer))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void forbiddenShowDoesNotRecordARecentlyViewedVersion() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}", privateAccount.getId())
                .header(HttpHeaders.AUTHORIZATION, memberBearer))
            .andExpect(status().isForbidden());

        assertThat(viewCount(privateAccount)).isZero();
    }

    @Test
    void everySuccessfulShowRecordsItsOwnViewVersion() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/v1/accounts/{id}", sharedWithGroup.getId())
                    .header(HttpHeaders.AUTHORIZATION, memberBearer))
                .andExpect(status().isOk());
        }

        assertThat(viewCount(sharedWithGroup)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
            "SELECT whodunnit FROM versions WHERE item_type = 'Account' AND item_id = ? AND event = 'view'",
            String.class, sharedWithGroup.getId()))
            .containsOnly(String.valueOf(member.getId()));
    }

    @Test
    void autocompleteFollowsTheSameVisibilityRules() throws Exception {
        assertThat(autocompleteNames(memberBearer, "Shared"))
            .containsExactlyInAnyOrder("Shared With Member", "Shared With Group");
        assertThat(autocompleteNames(memberBearer, "Private")).containsExactly("Assigned Private");
        assertThat(autocompleteNames(outsiderBearer, "Shared")).isEmpty();
    }

    @Test
    void permissionsForOtherAssetTypesOrDestroyedGroupsDoNotGrantAccess() throws Exception {
        Permission contactPermission = new Permission();
        contactPermission.setUser(member);
        contactPermission.setAssetType("Contact");
        contactPermission.setAssetId(sharedWithNobody.getId().intValue());
        permissionRepository.saveAndFlush(contactPermission);
        jdbcTemplate.update("DELETE FROM groups");

        assertThat(listNames(memberBearer)).containsExactlyInAnyOrder("Shared With Member", "Assigned Private");
        mockMvc.perform(get("/api/v1/accounts/{id}", sharedWithNobody.getId())
                .header(HttpHeaders.AUTHORIZATION, memberBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/accounts/{id}", sharedWithGroup.getId())
                .header(HttpHeaders.AUTHORIZATION, memberBearer))
            .andExpect(status().isForbidden());
    }

    @Test
    void malformedRelatedExclusionsAreIgnoredByAutocomplete() throws Exception {
        for (String related : new String[] {"abc", "campaigns", "users/not-a-number", "a/b/c", "users/999999", ""}) {
            MvcResult result = mockMvc.perform(get("/api/v1/accounts/autocomplete")
                    .header(HttpHeaders.AUTHORIZATION, memberBearer)
                    .param("term", "Shared")
                    .param("related", related))
                .andExpect(status().isOk())
                .andReturn();
            JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
            assertThat(body.path("results").findValuesAsText("text"))
                .as("related=%s", related)
                .containsExactlyInAnyOrder("Shared With Member", "Shared With Group");
        }
    }

    @Test
    void blankAndTrailingCommaCategoryFiltersMatchRailsSplit() throws Exception {
        jdbcTemplate.update("UPDATE accounts SET category = 'customer' WHERE id = ?", sharedWithMember.getId());

        mockMvc.perform(get("/api/v1/accounts").param("category", ",,")
                .header(HttpHeaders.AUTHORIZATION, memberBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(3));
        mockMvc.perform(get("/api/v1/accounts").param("category", "customer,")
                .header(HttpHeaders.AUTHORIZATION, memberBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Shared With Member"));
        mockMvc.perform(get("/api/v1/accounts").param("category", ",customer")
                .header(HttpHeaders.AUTHORIZATION, memberBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1));
        mockMvc.perform(get("/api/v1/accounts").param("category", "nonexistent")
                .header(HttpHeaders.AUTHORIZATION, memberBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(0))
            .andExpect(jsonPath("$.facets.category.all").value(3));
    }

    private List<String> listNames(String bearer) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk())
            .andReturn();
        return new ArrayList<>(JSON.readTree(result.getResponse().getContentAsString())
            .path("items").findValuesAsText("name"));
    }

    private List<String> autocompleteNames(String bearer, String term) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/accounts/autocomplete")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .param("term", term))
            .andExpect(status().isOk())
            .andReturn();
        return new ArrayList<>(JSON.readTree(result.getResponse().getContentAsString())
            .path("results").findValuesAsText("text"));
    }

    private int viewCount(Account account) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM versions WHERE item_type = 'Account' AND item_id = ? AND event = 'view'",
            Integer.class, account.getId());
        return count == null ? 0 : count;
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

    private Account account(User accountOwner, String name, String access) {
        Account account = new Account();
        account.setName(name);
        account.setEmail(name.toLowerCase().replace(' ', '.') + "@example.test");
        account.setAccess(access);
        account.setUser(accountOwner);
        Instant at = Instant.now();
        account.setCreatedAt(at);
        account.setUpdatedAt(at);
        return accountRepository.saveAndFlush(account);
    }

    private void userPermission(Account account, User grantee) {
        Permission permission = new Permission();
        permission.setUser(grantee);
        permission.setAssetType("Account");
        permission.setAssetId(account.getId().intValue());
        permissionRepository.saveAndFlush(permission);
    }

    private void groupPermission(Account account, Group group) {
        Permission permission = new Permission();
        permission.setGroup(group);
        permission.setAssetType("Account");
        permission.setAssetId(account.getId().intValue());
        permissionRepository.saveAndFlush(permission);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM permissions");
        jdbcTemplate.update("DELETE FROM groups_users");
        jdbcTemplate.update("DELETE FROM groups");
        jdbcTemplate.update("DELETE FROM taggings");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
