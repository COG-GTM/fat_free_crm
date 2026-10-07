package com.fatfreecrm.security.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Permission;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.service.PermissionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Edge cases around the AB-268 authorization port that the Rails matrix ({@link AuthorizationMatrixTest}) does
 * not enumerate: id coercion, action equivalence, unknown principals, proxies, the admin column over HTTP and
 * {@link PermissionService} failure paths. Uses the same {@code authz/authz_fixture.sql} seed (actors 1..8,
 * Account 2 owned by {@code owner}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
    AuthorizationEdgeCasesTest.EdgeProbeController.class,
    AuthorizationEdgeCasesTest.ClassLevelAdminController.class
})
class AuthorizationEdgeCasesTest {

    private static final long OWNER = 1L;
    private static final long UNRELATED = 7L;
    private static final long ADMIN = 8L;
    private static final long OWNED_ACCOUNT = 2L;
    private static final long MISSING = 999_999L;

    @ServiceConnection
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("fat_free_crm_authz_edge")
        .withUsername("postgres")
        .withPassword("postgres");

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
    private ContactRepository contactRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Transactional(readOnly = true)
    void targetIdsAreCoercedLikeRailsParamsBeforeTheRowIsLookedUp() {
        FfcrmAuthenticationToken owner = token(OWNER);
        for (Serializable id : new Serializable[] {"2", " 2 ", 2, 2L, (short) 2, 2.0d}) {
            assertThat(permissionEvaluator.hasPermission(owner, id, "Account", "read")).as(id.toString()).isTrue();
        }
        for (Serializable id : new Serializable[] {"2.0", "two", "", " ", "2,0"}) {
            assertThat(permissionEvaluator.hasPermission(owner, id, "Account", "read")).as(id.toString()).isFalse();
        }
        assertThat(permissionEvaluator.hasPermission(owner, (Serializable) null, "Account", "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(owner, OWNED_ACCOUNT, null, "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(owner, OWNED_ACCOUNT, "account", "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(owner, OWNED_ACCOUNT, "Account", null)).isFalse();
    }

    @Test
    @Transactional(readOnly = true)
    void railsManageCoversReadUpdateDestroyAndManageEquallyAndNothingElse() {
        for (String action : List.of("read", "update", "destroy", "manage")) {
            assertThat(permissionEvaluator.hasPermission(token(OWNER), OWNED_ACCOUNT, "Account", action))
                .as("owner " + action).isTrue();
            assertThat(permissionEvaluator.hasPermission(token(ADMIN), OWNED_ACCOUNT, "Account", action))
                .as("admin " + action).isTrue();
            assertThat(permissionEvaluator.hasPermission(token(UNRELATED), OWNED_ACCOUNT, "Account", action))
                .as("unrelated " + action).isFalse();
        }
        for (Object action : new Object[] {"create", "index", "show", "READ", "", 1, List.of("read")}) {
            assertThat(permissionEvaluator.hasPermission(token(ADMIN), OWNED_ACCOUNT, "Account", action))
                .as("admin " + action).isFalse();
        }
    }

    @Test
    @Transactional(readOnly = true)
    void principalsWithoutAUserRowGetForbiddenForExistingRowsAndNotFoundForMissingOnes() {
        FfcrmAuthenticationToken ghost = token(new AuthenticatedUser(9_999L, "ghost", true));
        assertThat(permissionEvaluator.hasPermission(ghost, OWNED_ACCOUNT, "Account", "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(ghost, ADMIN, "User", "read")).isFalse();
        assertThatThrownBy(() -> permissionEvaluator.hasPermission(ghost, 9_999L, "User", "read"))
            .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> permissionEvaluator.hasPermission(ghost, MISSING, "Account", "read"))
            .isInstanceOf(EntityNotFoundException.class);
        assertThat(contactRepository.count(accessPolicy.accessibleBy(ghost.getAuthenticatedUser(), Contact.class)))
            .isZero();
    }

    @Test
    @Transactional(readOnly = true)
    void domainObjectChecksUnwrapProxiesAndRejectTransientOrForeignObjects() {
        Account proxy = accountRepository.getReferenceById(OWNED_ACCOUNT);
        assertThat(permissionEvaluator.hasPermission(token(OWNER), proxy, "read")).isTrue();
        assertThat(permissionEvaluator.hasPermission(token(UNRELATED), proxy, "read")).isFalse();

        User self = userRepository.findById(OWNER).orElseThrow();
        assertThat(permissionEvaluator.hasPermission(token(OWNER), self, "update")).isTrue();
        assertThat(permissionEvaluator.hasPermission(token(UNRELATED), self, "update")).isFalse();
        assertThat(permissionEvaluator.hasPermission(token(ADMIN), self, "update")).isTrue();

        assertThat(permissionEvaluator.hasPermission(token(ADMIN), new Account(), "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(token(ADMIN), new Permission(), "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(token(ADMIN), "Account", "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(token(ADMIN), Map.of("id", 2L), "read")).isFalse();
    }

    @Test
    void aDeletedRowHeldAsADetachedEntityIsNotFoundForBothEvaluatorAndPermissionWrites() {
        Account account = new Account();
        account.setName("Edge deleted account");
        account.setUser(userRepository.getReferenceById(OWNER));
        account.setAccess("Private");
        Account detached = accountRepository.saveAndFlush(account);
        jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", detached.getId());

        assertThatThrownBy(() -> permissionEvaluator.hasPermission(token(OWNER), detached, "read"))
            .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> permissionService.setAccess(detached, "Shared"))
            .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> permissionService.setUserIds(detached, List.of(UNRELATED)))
            .isInstanceOf(EntityNotFoundException.class);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM permissions WHERE asset_type = 'Account' AND asset_id = ?", Long.class,
            detached.getId())).isZero();
    }

    @Test
    void permissionWritesRequireAPersistedEntity() {
        Account transientAccount = new Account();
        transientAccount.setAccess("Shared");
        assertThatThrownBy(() -> permissionService.setUserIds(transientAccount, List.of(UNRELATED)))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("persisted");
        assertThatThrownBy(() -> permissionService.updateSharing(null, "Shared", null, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @Transactional
    void permissionRowsAreScopedToTheirOwnAssetAndUntouchedByNoOpUpdates() {
        Account shared = newAccount("Edge shared");
        Account other = newAccount("Edge other");
        permissionService.updateSharing(shared, "Shared", List.of(UNRELATED), null);
        permissionService.updateSharing(other, "Shared", List.of(UNRELATED), null);
        assertThat(grants(shared)).containsExactly(UNRELATED);
        assertThat(grants(other)).containsExactly(UNRELATED);

        permissionService.updateSharing(shared, null, null, null);
        assertThat(grants(shared)).containsExactly(UNRELATED);
        assertThat(jdbcTemplate.queryForObject("SELECT access FROM accounts WHERE id = ?", String.class,
            shared.getId())).isEqualTo("Shared");

        permissionService.setAccess(other, "Private");
        assertThat(grants(other)).isEmpty();
        assertThat(grants(shared)).containsExactly(UNRELATED);
        assertThat(visibleTo(UNRELATED, shared.getId())).isTrue();
        assertThat(visibleTo(UNRELATED, other.getId())).isFalse();
    }

    @Test
    @Transactional
    void accessComparisonIsCaseSensitiveExactlyLikeRails() {
        Account account = newAccount("Edge lowercase shared");
        permissionService.updateSharing(account, "Shared", List.of(UNRELATED), null);
        assertThat(grants(account)).containsExactly(UNRELATED);

        permissionService.setAccess(account, "shared");
        entityManager.flush();
        assertThat(grants(account)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT access FROM accounts WHERE id = ?", String.class,
            account.getId())).isEqualTo("shared");
        assertThat(visibleTo(UNRELATED, account.getId())).isFalse();
        assertThat(visibleTo(OWNER, account.getId())).isTrue();
    }

    @Test
    void destroyOverHttpFollowsTheSamePolicyAsRead() throws Exception {
        mockMvc.perform(delete("/api/v1/authz-edge/Account/" + OWNED_ACCOUNT))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/authz-edge/Account/" + OWNED_ACCOUNT).header(HttpHeaders.AUTHORIZATION,
                bearer(UNRELATED)))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mockMvc.perform(delete("/api/v1/authz-edge/Account/" + MISSING).header(HttpHeaders.AUTHORIZATION,
                bearer(OWNER)))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/authz-edge/Account/" + OWNED_ACCOUNT).header(HttpHeaders.AUTHORIZATION,
                bearer(OWNER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.destroyed").value(2));
    }

    @Test
    void typesWithoutARailsRuleAreForbiddenOverHttpEvenForAdmins() throws Exception {
        for (String type : List.of("Permission", "Group", "Setting", "NoSuchModel", "account")) {
            mockMvc.perform(get("/api/v1/authz-edge/" + type + "/1").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403));
        }
    }

    @Test
    void adminRoleOverHttpFollowsTheUsersAdminColumnNotTheIssuedToken() throws Exception {
        String adminToken = bearer(ADMIN);
        String unrelatedToken = bearer(UNRELATED);
        try {
            jdbcTemplate.update("UPDATE users SET admin = false WHERE id = ?", ADMIN);
            jdbcTemplate.update("UPDATE users SET admin = true WHERE id = ?", UNRELATED);

            mockMvc.perform(get("/api/v1/admin/authz-edge").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/authz-edge/Account/" + OWNED_ACCOUNT)
                    .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/admin/authz-edge").header(HttpHeaders.AUTHORIZATION, unrelatedToken))
                .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/authz-edge/Account/" + OWNED_ACCOUNT)
                    .header(HttpHeaders.AUTHORIZATION, unrelatedToken))
                .andExpect(status().isOk());
        } finally {
            jdbcTemplate.update("UPDATE users SET admin = true WHERE id = ?", ADMIN);
            jdbcTemplate.update("UPDATE users SET admin = false WHERE id = ?", UNRELATED);
        }
        mockMvc.perform(get("/api/v1/admin/authz-edge").header(HttpHeaders.AUTHORIZATION, adminToken))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/authz-edge").header(HttpHeaders.AUTHORIZATION, unrelatedToken))
            .andExpect(status().isForbidden());
    }

    @Test
    void classLevelAdminOnlyGuardsEveryHandlerOfTheController() throws Exception {
        mockMvc.perform(get("/api/v1/authz-edge/class-admin")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/authz-edge/class-admin").header(HttpHeaders.AUTHORIZATION, bearer(OWNER)))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mockMvc.perform(get("/api/v1/authz-edge/class-admin/other").header(HttpHeaders.AUTHORIZATION, bearer(OWNER)))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/authz-edge/class-admin").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/authz-edge/class-admin/other").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
            .andExpect(status().isOk());
    }

    private Account newAccount(String name) {
        Account account = new Account();
        account.setName(name);
        account.setUser(userRepository.getReferenceById(OWNER));
        account.setAccess("Private");
        return accountRepository.saveAndFlush(account);
    }

    private List<Long> grants(Account account) {
        return jdbcTemplate.queryForList(
            "SELECT user_id FROM permissions WHERE asset_type = 'Account' AND asset_id = ? ORDER BY user_id",
            Long.class, account.getId());
    }

    private boolean visibleTo(long userId, Long accountId) {
        entityManager.flush();
        Specification<Account> byId = (root, query, cb) -> cb.equal(root.get("id"), accountId);
        AuthenticatedUser user = new AuthenticatedUser(userId, "authz_" + userId, false);
        return accountRepository.exists(byId.and(accessPolicy.accessibleBy(user, Account.class)));
    }

    private String bearer(long userId) {
        return "Bearer " + jwtTokenService.issue(userRepository.findById(userId).orElseThrow()).accessToken();
    }

    private static FfcrmAuthenticationToken token(long userId) {
        return token(new AuthenticatedUser(userId, "authz_" + userId, userId == ADMIN));
    }

    private static FfcrmAuthenticationToken token(AuthenticatedUser user) {
        Jwt jwt = Jwt.withTokenValue("edge").header("alg", "none").subject(user.id().toString()).build();
        return new FfcrmAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_USER"), user);
    }

    private static void loadSql(String path) throws IOException, InterruptedException {
        var result = POSTGRES.execInContainer(
            "psql", "-v", "ON_ERROR_STOP=1", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
            "-f", path);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(path + " failed: " + result.getStderr());
        }
    }

    @RestController
    static class EdgeProbeController {

        @GetMapping("/api/v1/authz-edge/{type}/{id}")
        @PreAuthorize("hasPermission(#id, #type, 'read')")
        Map<String, Object> fetch(@PathVariable("type") String type, @PathVariable("id") Long id) {
            return Map.of("type", type, "id", id);
        }

        @DeleteMapping("/api/v1/authz-edge/{type}/{id}")
        @PreAuthorize("hasPermission(#id, #type, 'destroy')")
        Map<String, Object> destroy(@PathVariable("type") String type, @PathVariable("id") Long id) {
            return Map.of("destroyed", id);
        }

        @GetMapping("/api/v1/admin/authz-edge")
        Map<String, Object> admin() {
            return Map.of("admin", true);
        }
    }

    @RestController
    @AdminOnly
    static class ClassLevelAdminController {

        @GetMapping("/api/v1/authz-edge/class-admin")
        Map<String, Object> first() {
            return Map.of("admin", true);
        }

        @GetMapping("/api/v1/authz-edge/class-admin/other")
        Map<String, Object> second() {
            return Map.of("admin", true);
        }
    }
}
