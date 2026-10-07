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
import com.fatfreecrm.domain.Permission;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.service.PermissionService;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.support.Repositories;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Asserts the Spring Specifications against Rails' {@code Klass.my(user)} / {@code accessible_by(user.ability)}
 * recorded by {@code rake ffcrm:migration:authz_matrix} on the identical seed ({@code authz/authz_fixture.sql}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({AuthorizationMatrixTest.RecordProbeController.class, AuthorizationMatrixTest.AdminProbeController.class})
class AuthorizationMatrixTest {

    @ServiceConnection
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("fat_free_crm_authz")
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
        try (InputStream input = AuthorizationMatrixTest.class.getResourceAsStream("/authz/authz_matrix.json")) {
            matrix = new ObjectMapper().readTree(input);
        }
    }

    @Test
    @Transactional(readOnly = true)
    void listIdsAndPageTotalsMatchRailsForEveryActorAndType() {
        int cells = 0;
        for (Map.Entry<String, JsonNode> type : matrix.get("visible").properties()) {
            JpaSpecificationExecutor<Object> executor = executor(type.getKey());
            for (Map.Entry<String, AuthenticatedUser> actor : actors().entrySet()) {
                JsonNode expected = type.getValue().get(actor.getKey());
                Specification<Object> spec = accessPolicy.accessibleBy(actor.getValue(), entityClass(type.getKey()));
                String cell = type.getKey() + " as " + actor.getKey();

                List<Long> ids = executor.findAll(spec, Sort.by("id")).stream()
                    .map(AuthorizationMatrixTest::idOf)
                    .toList();
                assertThat(ids).as(cell).containsExactlyElementsOf(longs(expected.get("ids")));
                assertThat(executor.count(spec)).as(cell).isEqualTo(expected.get("count").asLong());

                Page<Object> page = executor.findAll(spec, PageRequest.of(0, 3, Sort.by("id")));
                assertThat(page.getTotalElements()).as(cell + " page total").isEqualTo(expected.get("count").asLong());
                assertThat(page.getContent()).as(cell + " page size")
                    .hasSize(Math.min(3, expected.get("count").asInt()));
                cells++;
            }
        }
        assertThat(cells).isEqualTo(9 * 8);
    }

    @Test
    @Transactional(readOnly = true)
    void permissionEvaluatorAgreesWithSpecificationForEveryCell() {
        for (Map.Entry<String, JsonNode> type : matrix.get("visible").properties()) {
            List<Long> allIds = longs(type.getValue().get("admin").get("ids"));
            for (Map.Entry<String, AuthenticatedUser> actor : actors().entrySet()) {
                List<Long> visible = longs(type.getValue().get(actor.getKey()).get("ids"));
                FfcrmAuthenticationToken authentication = token(actor.getValue());
                for (Long id : allIds) {
                    String cell = type.getKey() + "#" + id + " as " + actor.getKey();
                    boolean allowed;
                    try {
                        allowed = permissionEvaluator.hasPermission(authentication, id, type.getKey(), "read");
                    } catch (EntityNotFoundException exception) {
                        throw new AssertionError(cell + " should exist", exception);
                    }
                    assertThat(allowed).as(cell).isEqualTo(visible.contains(id));
                    assertThat(permissionEvaluator.hasPermission(authentication, id, type.getKey(), "update"))
                        .as(cell + " update").isEqualTo(allowed);
                }
            }
        }
    }

    @Test
    @Transactional(readOnly = true)
    void evaluatorRejectsUnknownPermissionsTypesAndMissingRows() {
        FfcrmAuthenticationToken admin = token(actors().get("admin"));
        assertThat(permissionEvaluator.hasPermission(admin, 1L, "Account", "fly")).isFalse();
        assertThat(permissionEvaluator.hasPermission(admin, 1L, "Permission", "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(admin, 1L, "NoSuchModel", "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(admin, "not-a-number", "Account", "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(admin, accountRepository.findById(1L).orElseThrow(), "read"))
            .isTrue();
        assertThatThrownBy(() -> permissionEvaluator.hasPermission(admin, 999_999L, "Account", "read"))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void unsupportedEntityTypesAreRejectedInsteadOfAllowingEverything() {
        AuthenticatedUser admin = actors().get("admin");
        assertThatThrownBy(() -> accessPolicy.accessibleBy(admin, Permission.class))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accessPolicy.accessibleBy(admin, Object.class))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional(readOnly = true)
    void adminFlagIsReloadedFromTheDatabaseNotTrustedFromTheToken() {
        AuthenticatedUser forgedAdmin = new AuthenticatedUser(actors().get("unrelated").id(), "authz_unrelated", true);
        Specification<Account> spec = accessPolicy.accessibleBy(forgedAdmin, Account.class);
        assertThat(accountRepository.count(spec)).isEqualTo(matrix.at("/visible/Account/unrelated/count").asLong());

        AuthenticatedUser unknown = new AuthenticatedUser(9_999L, "ghost", true);
        assertThat(accountRepository.count(accessPolicy.accessibleBy(unknown, Account.class))).isZero();
    }

    @Test
    @Transactional
    void permissionServiceWritesTheSameRowsAsRails() {
        JsonNode writes = matrix.get("permission_writes");
        Map<String, AuthenticatedUser> actors = actors();
        Long salesId = jdbcTemplate.queryForObject("SELECT id FROM groups WHERE name = 'Authz Sales'", Long.class);
        Account account = new Account();
        account.setName("Java permission writes");
        account.setUser(userRepository.getReferenceById(actors.get("owner").id()));
        account.setAccess("Private");
        account = accountRepository.saveAndFlush(account);

        for (JsonNode step : writes.get("steps")) {
            String access = step.hasNonNull("access") ? step.get("access").asText() : null;
            List<Object> userIds = null;
            if (step.hasNonNull("user_ids")) {
                userIds = new ArrayList<>();
                for (JsonNode name : step.get("user_ids")) {
                    userIds.add(String.valueOf(actors.get(name.asText()).id()));
                }
                userIds.add("");
            }
            List<Object> groupIds = null;
            if (step.hasNonNull("group_ids")) {
                groupIds = new ArrayList<>();
                for (JsonNode ignored : step.get("group_ids")) {
                    groupIds.add(salesId);
                }
            }
            permissionService.updateSharing(account, access, userIds, groupIds);
            accountRepository.flush();

            String name = step.get("name").asText();
            assertThat(jdbcTemplate.queryForObject(
                "SELECT access FROM accounts WHERE id = ?", String.class, account.getId()))
                .as(name).isEqualTo(step.get("result_access").asText());
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT u.username, p.group_id, p.asset_type,
                       p.created_at IS NOT NULL AND p.updated_at IS NOT NULL AS ts,
                       p.created_at = p.updated_at AS same
                FROM permissions p LEFT JOIN users u ON u.id = p.user_id
                WHERE p.asset_type = 'Account' AND p.asset_id = ?
                ORDER BY p.user_id NULLS LAST, p.group_id NULLS LAST
                """, account.getId());
            List<String> actual = rows.stream().map(row -> describe(
                row.get("username") == null ? null : ((String) row.get("username")).substring("authz_".length()),
                row.get("group_id") == null ? null : "sales",
                (String) row.get("asset_type"), (Boolean) row.get("ts"), (Boolean) row.get("same"))).toList();
            List<String> expected = new ArrayList<>();
            for (JsonNode row : step.get("rows")) {
                expected.add(describe(
                    row.hasNonNull("user") ? row.get("user").asText() : null,
                    row.hasNonNull("group") ? row.get("group").asText() : null,
                    row.get("asset_type").asText(), row.get("timestamps_present").asBoolean(),
                    row.get("created_equals_updated").asBoolean()));
            }
            assertThat(actual).as(name).containsExactlyElementsOf(expected);
        }
    }

    @Test
    @Transactional
    void sharedRowsWrittenByJavaGrantVisibilityThroughTheSameSpecification() {
        Map<String, AuthenticatedUser> actors = actors();
        Long salesId = jdbcTemplate.queryForObject("SELECT id FROM groups WHERE name = 'Authz Sales'", Long.class);
        Account account = new Account();
        account.setName("Java shared account");
        account.setUser(userRepository.getReferenceById(actors.get("owner").id()));
        account.setAccess("Private");
        account = accountRepository.saveAndFlush(account);
        Long id = account.getId();

        permissionService.updateSharing(account, "Shared", List.of(actors.get("shared_user").id()), List.of(salesId));
        accountRepository.flush();
        assertThat(visibleTo("shared_user", id)).isTrue();
        assertThat(visibleTo("group_member", id)).isTrue();
        assertThat(visibleTo("unrelated", id)).isFalse();

        permissionService.setAccess(account, "Private");
        accountRepository.flush();
        assertThat(visibleTo("shared_user", id)).isFalse();
        assertThat(visibleTo("group_member", id)).isFalse();
    }

    @Test
    void sharingADetachedEntityWritesTheRequestedAccessAndBothGrantKinds() {
        Map<String, AuthenticatedUser> actors = actors();
        Long salesId = jdbcTemplate.queryForObject("SELECT id FROM groups WHERE name = 'Authz Sales'", Long.class);
        Account account = new Account();
        account.setName("Java detached account");
        account.setUser(userRepository.getReferenceById(actors.get("owner").id()));
        account.setAccess("Private");
        Account detached = accountRepository.saveAndFlush(account);
        Long id = detached.getId();
        try {
            permissionService.updateSharing(detached, "Shared", List.of(actors.get("shared_user").id()),
                List.of(salesId));
            assertThat(jdbcTemplate.queryForObject("SELECT access FROM accounts WHERE id = ?", String.class, id))
                .isEqualTo("Shared");
            assertThat(detached.getAccess()).isEqualTo("Shared");
            assertThat(jdbcTemplate.queryForList(
                "SELECT COALESCE(user_id, -group_id) FROM permissions WHERE asset_type = 'Account' AND asset_id = ?"
                    + " ORDER BY 1", Long.class, id))
                .containsExactly(-salesId, actors.get("shared_user").id());
            assertThat(visibleTo("shared_user", id)).isTrue();
            assertThat(visibleTo("group_member", id)).isTrue();

            Account reloaded = accountRepository.findById(id).orElseThrow();
            permissionService.setAccess(reloaded, "Private");
            reloaded.setAccess("Shared");
            permissionService.setUserIds(reloaded, List.of(actors.get("unrelated").id()));
            assertThat(jdbcTemplate.queryForObject("SELECT access FROM accounts WHERE id = ?", String.class, id))
                .isEqualTo("Shared");
            assertThat(visibleTo("unrelated", id)).isTrue();
            assertThat(visibleTo("group_member", id)).isFalse();
        } finally {
            jdbcTemplate.update("DELETE FROM permissions WHERE asset_type = 'Account' AND asset_id = ?", id);
            jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", id);
        }
    }

    @Test
    void recordFetchIs401WithoutToken200InScope403OutOfScopeAnd404WhenMissing() throws Exception {
        mockMvc.perform(get("/api/v1/authz-probe/Account/2"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mockMvc.perform(get("/api/v1/authz-probe/Account/2").header(HttpHeaders.AUTHORIZATION, bearer("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(2));
        mockMvc.perform(get("/api/v1/authz-probe/Account/2").header(HttpHeaders.AUTHORIZATION, bearer("unrelated")))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(403));
        mockMvc.perform(get("/api/v1/authz-probe/Account/999999")
                .header(HttpHeaders.AUTHORIZATION, bearer("unrelated")))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(get("/api/v1/authz-probe/Account/2").header(HttpHeaders.AUTHORIZATION, bearer("admin")))
            .andExpect(status().isOk());
    }

    @Test
    void adminNamespaceRequiresRoleAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/authz-probe")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/authz-probe").header(HttpHeaders.AUTHORIZATION, bearer("owner")))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mockMvc.perform(get("/api/v1/admin/authz-probe").header(HttpHeaders.AUTHORIZATION, bearer("admin")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/authz-probe/admin-only").header(HttpHeaders.AUTHORIZATION, bearer("owner")))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mockMvc.perform(get("/api/v1/authz-probe/admin-only").header(HttpHeaders.AUTHORIZATION, bearer("admin")))
            .andExpect(status().isOk());
    }

    private boolean visibleTo(String actor, Long id) {
        Specification<Account> byId = (root, query, cb) -> cb.equal(root.get("id"), id);
        return accountRepository.exists(byId.and(accessPolicy.accessibleBy(actors().get(actor), Account.class)));
    }

    private String bearer(String actor) {
        User user = userRepository.findById(actors().get(actor).id()).orElseThrow();
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private static String describe(String user, String group, String assetType, boolean timestamps, boolean same) {
        return user + "|" + group + "|" + assetType + "|" + timestamps + "|" + same;
    }

    private Map<String, AuthenticatedUser> actors() {
        Map<String, AuthenticatedUser> actors = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> actor : matrix.get("actors").properties()) {
            actors.put(actor.getKey(), new AuthenticatedUser(
                actor.getValue().asLong(), "authz_" + actor.getKey(), "admin".equals(actor.getKey())));
        }
        return actors;
    }

    private static FfcrmAuthenticationToken token(AuthenticatedUser user) {
        Jwt jwt = Jwt.withTokenValue("matrix").header("alg", "none").subject(user.id().toString()).build();
        return new FfcrmAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_USER"), user);
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

    private static Long idOf(Object entity) {
        try {
            return ((Number) entity.getClass().getMethod("getId").invoke(entity)).longValue();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asLong()));
        return values;
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
    static class RecordProbeController {

        @GetMapping("/api/v1/authz-probe/{type}/{id}")
        @PreAuthorize("hasPermission(#id, #type, 'read')")
        Map<String, Object> fetch(@PathVariable("type") String type, @PathVariable("id") Long id) {
            return Map.of("type", type, "id", id);
        }

        @GetMapping("/api/v1/authz-probe/admin-only")
        @AdminOnly
        Map<String, Object> adminOnly() {
            return Map.of("admin", true);
        }
    }

    @RestController
    static class AdminProbeController {

        @GetMapping("/api/v1/admin/authz-probe")
        Map<String, Object> probe() {
            return Map.of("admin", true);
        }
    }
}
