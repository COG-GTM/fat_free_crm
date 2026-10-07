package com.fatfreecrm.security.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Email;
import com.fatfreecrm.domain.Group;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.Permission;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.CrmEntity;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.PermissionRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.service.PermissionService;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.support.Repositories;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Edge cases of the AB-268 authorization components on the Rails-generated seed
 * ({@code authz/authz_fixture.sql}): evaluator inputs that are not a database user, permission names and
 * target ids, policy guards, every rule shape over HTTP, and the write side's error paths, Rails
 * {@code asset_type} and cross-asset isolation. {@link AuthorizationMatrixTest} pins the visibility matrix
 * itself; this class pins what happens around it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthorizationMatrixTest.RecordProbeController.class)
class AuthorizationEdgeCaseTest {

    private static final Map<String, String> TABLES = Map.of(
        "Account", "accounts",
        "Campaign", "campaigns",
        "Contact", "contacts",
        "Lead", "leads",
        "Opportunity", "opportunities"
    );

    @ServiceConnection
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("fat_free_crm_authz_edge")
        .withUsername("postgres")
        .withPassword("postgres");

    private static JsonNode matrix;

    static {
        POSTGRES.start();
        try {
            POSTGRES.copyFileToContainer(
                MountableFile.forClasspathResource("db/rails/rails_schema.sql"), "/tmp/rails_schema.sql");
            POSTGRES.copyFileToContainer(
                MountableFile.forClasspathResource("authz/authz_fixture.sql"), "/tmp/authz_fixture.sql");
            loadSql("/tmp/rails_schema.sql");
            loadSql("/tmp/authz_fixture.sql");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExceptionInInitializerError(exception);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired
    private AccessPolicy accessPolicy;

    @Autowired
    private CrmPermissionEvaluator permissionEvaluator;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void readMatrix() throws IOException {
        try (InputStream input = AuthorizationEdgeCaseTest.class.getResourceAsStream("/authz/authz_matrix.json")) {
            matrix = new ObjectMapper().readTree(input);
        }
    }

    @Test
    @Transactional(readOnly = true)
    void evaluatorDeniesAuthenticationsThatDoNotCarryADatabaseUser() {
        Long publicAccount = record("Account", "public");
        Authentication plain = new UsernamePasswordAuthenticationToken(
            "authz_admin", "n/a", AuthorityUtils.createAuthorityList("ROLE_USER", "ROLE_ADMIN"));
        Authentication anonymous = new AnonymousAuthenticationToken(
            "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertThat(permissionEvaluator.hasPermission(plain, publicAccount, "Account", "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(anonymous, publicAccount, "Account", "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(null, publicAccount, "Account", "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(
            plain, accountRepository.findById(publicAccount).orElseThrow(), "read")).isFalse();
    }

    @Test
    @Transactional(readOnly = true)
    void everyRailsManageActionIsEquivalentAndOtherPermissionNamesAreDenied() {
        FfcrmAuthenticationToken owner = token("owner");
        FfcrmAuthenticationToken unrelated = token("unrelated");
        Long privateOwned = record("Account", "private_owned");

        for (String action : List.of("read", "update", "destroy", "manage")) {
            assertThat(permissionEvaluator.hasPermission(owner, privateOwned, "Account", action)).as(action).isTrue();
            assertThat(permissionEvaluator.hasPermission(unrelated, privateOwned, "Account", action))
                .as(action).isFalse();
        }
        for (Object action : Arrays.asList("Read", "READ", "create", "index", "", null, 1)) {
            assertThat(permissionEvaluator.hasPermission(owner, privateOwned, "Account", action))
                .as(String.valueOf(action)).isFalse();
        }
    }

    @Test
    @Transactional(readOnly = true)
    void targetIdsAreCoercedLikePathVariablesAndAnythingElseIsDenied() {
        FfcrmAuthenticationToken owner = token("owner");
        Long id = record("Account", "private_owned");

        assertThat(permissionEvaluator.hasPermission(owner, id.toString(), "Account", "read")).isTrue();
        assertThat(permissionEvaluator.hasPermission(owner, " " + id + " ", "Account", "read")).isTrue();
        assertThat(permissionEvaluator.hasPermission(owner, id.intValue(), "Account", "read")).isTrue();
        assertThat(permissionEvaluator.hasPermission(owner, "", "Account", "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(owner, id + ".0", "Account", "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(owner, (Serializable) null, "Account", "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(owner, new ArrayList<>(List.of(id)), "Account", "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(owner, id, null, "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(owner, id, "account", "read")).isFalse();
    }

    @Test
    @Transactional(readOnly = true)
    void domainObjectChecksUnwrapProxiesAndDenyOutOfScopeUnsavedAndUnsupportedObjects() {
        Long id = record("Account", "private_owned");
        Account proxy = accountRepository.getReferenceById(id);
        Account loaded = accountRepository.findById(id).orElseThrow();

        assertThat(permissionEvaluator.hasPermission(token("owner"), proxy, "read")).isTrue();
        assertThat(permissionEvaluator.hasPermission(token("unrelated"), proxy, "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(token("owner"), loaded, "destroy")).isTrue();
        assertThat(permissionEvaluator.hasPermission(token("assignee"), loaded, "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(token("admin"), new Account(), "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(token("admin"), (Object) null, "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(token("admin"), "Account", "read")).isFalse();

        Permission permission = permissionRepository.findAll().get(0);
        assertThat(permissionEvaluator.hasPermission(token("admin"), permission, "read")).isFalse();
    }

    @Test
    void accessPolicyRejectsMissingUsersAndUnsupportedTypesInsteadOfGuessing() {
        AuthenticatedUser owner = actor("owner");

        assertThatThrownBy(() -> accessPolicy.accessibleBy(null, Account.class))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> accessPolicy.accessibleBy(owner, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> accessPolicy.accessibleBy(new AuthenticatedUser(null, "nobody", true), Account.class))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> accessPolicy.accessibleBy(owner, Group.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(Group.class.getName());

        assertThat(CrmAccessPolicy.supports(Group.class)).isFalse();
        assertThat(CrmAccessPolicy.supports(Permission.class)).isFalse();
        assertThat(CrmAccessPolicy.supports(Object.class)).isFalse();
    }

    @Test
    @Transactional(readOnly = true)
    void unknownUsersSeeNothingUnderEveryRuleShape() {
        AuthenticatedUser ghost = new AuthenticatedUser(9_999L, "ghost", true);
        for (String type : List.of("Account", "Contact", "Task", "Comment", "Email", "User")) {
            assertThat(executor(type).count(accessPolicy.accessibleBy(ghost, entityClass(type)))).as(type).isZero();
            assertThat(permissionEvaluator.hasPermission(token(ghost), 1L, type, "read")).as(type).isFalse();
        }
    }

    @Test
    void everySupportedEntityHasASpecificationCapableRepository() {
        Repositories repositories = new Repositories(applicationContext);
        List<Class<?>> supported = Arrays.stream(RailsModelType.values())
            .map(RailsModelType::entityClass)
            .filter(CrmAccessPolicy::supports)
            .toList();

        assertThat(supported).containsExactlyInAnyOrder(Account.class, Campaign.class, Contact.class, Lead.class,
            Opportunity.class, Task.class, Comment.class, Email.class, User.class);
        for (Class<?> type : supported) {
            assertThat(repositories.getRepositoryFor(type)).as(type.getSimpleName())
                .get().isInstanceOf(JpaSpecificationExecutor.class);
        }
    }

    @Test
    void recordProbeEnforcesEveryRuleShapeOverHttp() throws Exception {
        Long privateAssignedContact = record("Contact", "private_assigned");
        expectFetch("Contact", privateAssignedContact, "assignee", 200);
        expectFetch("Contact", privateAssignedContact, "unrelated", 403);
        expectFetch("Contact", privateAssignedContact, "admin", 200);

        Long sharedGroupLead = record("Lead", "shared_group");
        expectFetch("Lead", sharedGroupLead, "group_member", 200);
        expectFetch("Lead", sharedGroupLead, "stale_member", 403);

        expectFetch("Task", record("Task", "completed_by"), "completer", 200);
        expectFetch("Task", record("Task", "completed_by"), "owner", 403);
        expectFetch("Task", record("Task", "unrelated"), "owner", 403);

        Long unrelatedComment = record("Comment", "by_unrelated_on_private");
        expectFetch("Comment", unrelatedComment, "unrelated", 200);
        expectFetch("Comment", unrelatedComment, "owner", 403);

        Long ownerEmail = record("Email", "by_owner");
        expectFetch("Email", ownerEmail, "owner", 200);
        expectFetch("Email", ownerEmail, "assignee", 403);

        Long ownerId = actor("owner").id();
        expectFetch("User", ownerId, "owner", 200);
        expectFetch("User", ownerId, "unrelated", 403);
        expectFetch("User", ownerId, "admin", 200);

        expectFetch("Permission", 1L, "admin", 403);
        expectFetch("NoSuchModel", 1L, "admin", 403);
        expectFetch("Account", 999_999L, "admin", 404);

        mockMvc.perform(get("/api/v1/authz-probe/Account/not-a-number")
                .header(HttpHeaders.AUTHORIZATION, bearer("admin")))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void permissionServiceRejectsNullUnsavedAndMissingEntitiesWithoutWritingRows() {
        long before = permissionRepository.count();

        assertThatThrownBy(() -> permissionService.setAccess(null, "Shared"))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> permissionService.updateSharing(new Account(), "Shared", List.of(1L), List.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("persisted");

        Account account = new Account();
        account.setName("Authz vanished account");
        account.setUser(userRepository.getReferenceById(actor("owner").id()));
        account.setAccess("Shared");
        Account detached = accountRepository.saveAndFlush(account);
        jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", detached.getId());

        assertThatThrownBy(() -> permissionService.setUserIds(detached, List.of(actor("shared_user").id())))
            .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> permissionService.updateSharing(detached, "Shared", null, List.of(groupId("sales"))))
            .isInstanceOf(EntityNotFoundException.class);
        assertThat(permissionRepository.count()).isEqualTo(before);
    }

    @Test
    @Transactional
    void permissionRowsCarryEachEntitysRailsAssetTypeAndNeverGrantAnotherType() {
        Long salesId = groupId("sales");
        Long sharedUserId = actor("shared_user").id();
        Long privateAccount = record("Account", "private_owned");

        for (String type : List.of("Contact", "Lead", "Campaign", "Opportunity", "Account")) {
            Long id = record(type, "private_owned");
            assertThat(visibleTo("shared_user", type, id)).as(type + " before").isFalse();
            assertThat(visibleTo("group_member", type, id)).as(type + " before").isFalse();

            permissionService.updateSharing(entity(type, id), "Shared", List.of(sharedUserId), List.of(salesId));

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT asset_type, user_id, group_id FROM permissions WHERE asset_type = ? AND asset_id = ?"
                    + " ORDER BY user_id NULLS LAST", type, id);
            assertThat(rows).as(type).hasSize(2);
            assertThat(rows).extracting(row -> row.get("asset_type")).containsOnly(type);
            assertThat(rows).extracting(row -> row.get("user_id")).containsExactly(sharedUserId.intValue(), null);
            assertThat(rows).extracting(row -> row.get("group_id")).containsExactly(null, salesId.intValue());
            assertThat(jdbcTemplate.queryForObject(
                "SELECT access FROM " + TABLES.get(type) + " WHERE id = ?", String.class, id)).isEqualTo("Shared");
            assertThat(visibleTo("shared_user", type, id)).as(type + " after").isTrue();
            assertThat(visibleTo("group_member", type, id)).as(type + " after").isTrue();
            assertThat(visibleTo("unrelated", type, id)).as(type + " after").isFalse();

            if (!"Account".equals(type)) {
                assertThat(visibleTo("shared_user", "Account", privateAccount)).as("Account via " + type).isFalse();
                assertThat(visibleTo("group_member", "Account", privateAccount)).as("Account via " + type).isFalse();
            }
        }
    }

    @Test
    @Transactional
    void settingIdsWhileTheCallerAccessIsNotSharedRemovesEveryRowLikeRails() {
        Long id = record("Account", "shared_user_and_group");
        assertThat(accountRows(id)).hasSize(2);
        Account account = accountRepository.findById(id).orElseThrow();

        account.setAccess("Private");
        permissionService.setGroupIds(account, List.of(groupId("sales")));
        accountRepository.flush();

        assertThat(accountRows(id)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT access FROM accounts WHERE id = ?", String.class, id))
            .isEqualTo("Private");
        assertThat(visibleTo("group_member", "Account", id)).isFalse();
        assertThat(visibleTo("owner", "Account", id)).isTrue();
    }

    @Test
    @Transactional
    void replacingUserRowsKeepsGroupRowsLeavesOtherAssetsAloneAndCollapsesDuplicateIds() {
        Long id = record("Account", "shared_user_and_group");
        Long other = record("Account", "shared_user");
        List<Map<String, Object>> otherBefore = accountRows(other);
        Account account = accountRepository.findById(id).orElseThrow();
        Long unrelatedId = actor("unrelated").id();

        permissionService.setUserIds(account, Arrays.asList(unrelatedId.toString(), unrelatedId, " ", null));
        accountRepository.flush();

        List<Map<String, Object>> rows = accountRows(id);
        assertThat(rows).extracting(row -> row.get("user_id")).containsExactly(unrelatedId.intValue(), null);
        assertThat(rows).extracting(row -> row.get("group_id")).containsExactly(null, groupId("sales").intValue());
        assertThat(accountRows(other)).isEqualTo(otherBefore);
        assertThat(visibleTo("unrelated", "Account", id)).isTrue();
        assertThat(visibleTo("group_member", "Account", id)).isTrue();

        permissionService.updateSharing(account, null, null, null);
        accountRepository.flush();
        assertThat(accountRows(id)).isEqualTo(rows);
        assertThat(jdbcTemplate.queryForObject("SELECT access FROM accounts WHERE id = ?", String.class, id))
            .isEqualTo("Shared");
    }

    private ResultActions expectFetch(String type, Long id, String actor, int expectedStatus) throws Exception {
        ResultActions result = mockMvc.perform(get("/api/v1/authz-probe/" + type + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer(actor)))
            .andExpect(status().is(expectedStatus));
        if (expectedStatus == 200) {
            return result.andExpect(jsonPath("$.id").value(id));
        }
        return result.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    private boolean visibleTo(String actor, String type, Long id) {
        Specification<Object> byId = (root, query, cb) -> cb.equal(root.get("id"), id);
        return executor(type).exists(byId.and(accessPolicy.accessibleBy(actor(actor), entityClass(type))));
    }

    private List<Map<String, Object>> accountRows(Long id) {
        return jdbcTemplate.queryForList(
            "SELECT user_id, group_id FROM permissions WHERE asset_type = 'Account' AND asset_id = ?"
                + " ORDER BY user_id NULLS LAST, group_id NULLS LAST", id);
    }

    private String bearer(String actor) {
        User user = userRepository.findById(actor(actor).id()).orElseThrow();
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private static Long record(String type, String label) {
        return matrix.at("/records/" + type + "/" + label).asLong();
    }

    private static Long groupId(String name) {
        return matrix.at("/groups/" + name).asLong();
    }

    private static AuthenticatedUser actor(String name) {
        return new AuthenticatedUser(matrix.at("/actors/" + name).asLong(), "authz_" + name, "admin".equals(name));
    }

    private static FfcrmAuthenticationToken token(String actor) {
        return token(actor(actor));
    }

    private static FfcrmAuthenticationToken token(AuthenticatedUser user) {
        Jwt jwt = Jwt.withTokenValue("edge").header("alg", "none").subject(user.id().toString()).build();
        return new FfcrmAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_USER"), user);
    }

    @SuppressWarnings("unchecked")
    private CrmEntity entity(String railsName, Long id) {
        JpaRepository<Object, Long> repository = (JpaRepository<Object, Long>) new Repositories(applicationContext)
            .getRepositoryFor(entityClass(railsName)).orElseThrow();
        return (CrmEntity) repository.findById(id).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private JpaSpecificationExecutor<Object> executor(String railsName) {
        return (JpaSpecificationExecutor<Object>) new Repositories(applicationContext)
            .getRepositoryFor(entityClass(railsName)).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Class<Object> entityClass(String railsName) {
        return (Class<Object>) RailsModelType.fromRailsName(railsName).orElseThrow().entityClass();
    }

    private static void loadSql(String path) throws IOException, InterruptedException {
        var result = POSTGRES.execInContainer(
            "psql", "-v", "ON_ERROR_STOP=1", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
            "-f", path);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(path + " failed: " + result.getStderr());
        }
    }
}
