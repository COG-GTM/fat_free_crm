package com.fatfreecrm.security.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.support.Repositories;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Asserts the Specifications on the AB-266 contract-diff corpus ({@code db/contract_fixtures.rb}), the data
 * the harness runs against. Expected ids are Rails' {@code Klass.my(user)} recorded by
 * {@code rake ffcrm:migration:authz_matrix} ({@code authz/contract_corpus_matrix.json}).
 */
@SpringBootTest
class ContractCorpusAuthorizationTest {

    @ServiceConnection
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("fat_free_crm_contract_corpus")
        .withUsername("postgres")
        .withPassword("postgres");

    private static JsonNode matrix;

    static {
        POSTGRES.start();
        try {
            POSTGRES.copyFileToContainer(
                MountableFile.forClasspathResource("db/rails/rails_schema.sql"), "/tmp/rails_schema.sql");
            POSTGRES.copyFileToContainer(
                MountableFile.forClasspathResource("authz/contract_corpus.sql"), "/tmp/contract_corpus.sql");
            loadSql("/tmp/rails_schema.sql");
            loadSql("/tmp/contract_corpus.sql");
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
    private ApplicationContext applicationContext;

    @BeforeAll
    static void readMatrix() throws IOException {
        try (InputStream input = ContractCorpusAuthorizationTest.class
            .getResourceAsStream("/authz/contract_corpus_matrix.json")) {
            matrix = new ObjectMapper().readTree(input);
        }
    }

    @Test
    @Transactional(readOnly = true)
    void listsAndFetchesMatchRailsForEveryCorpusUserAndType() {
        int cells = 0;
        for (Map.Entry<String, JsonNode> type : matrix.get("visible").properties()) {
            JpaSpecificationExecutor<Object> executor = executor(type.getKey());
            List<Long> allIds = longs(type.getValue().get("admin").get("ids"));
            for (Map.Entry<String, JsonNode> user : matrix.get("users").properties()) {
                AuthenticatedUser actor = user(user.getKey());
                JsonNode expected = type.getValue().get(user.getKey());
                List<Long> visible = longs(expected.get("ids"));
                String cell = type.getKey() + " as " + user.getKey();
                Specification<Object> spec = accessPolicy.accessibleBy(actor, entityClass(type.getKey()));

                assertThat(executor.findAll(spec, Sort.by("id")).stream().map(ContractCorpusAuthorizationTest::idOf))
                    .as(cell).containsExactlyElementsOf(visible);
                assertThat(executor.findAll(spec, PageRequest.of(0, 2, Sort.by("id"))).getTotalElements())
                    .as(cell + " page total").isEqualTo(expected.get("count").asLong());
                for (Long id : allIds) {
                    assertThat(permissionEvaluator.hasPermission(token(actor), id, type.getKey(), "read"))
                        .as(type.getKey() + "#" + id + " as " + user.getKey()).isEqualTo(visible.contains(id));
                }
                cells++;
            }
        }
        assertThat(cells).isEqualTo(7 * 5);
    }

    @Test
    @Transactional(readOnly = true)
    void bobIsDeniedAlicesPrivateAccountForTheAccountsShowPrivateDeniedBobCase() {
        assertThat(permissionEvaluator.hasPermission(token(user("bob")), 102L, "Account", "read")).isFalse();
        assertThat(permissionEvaluator.hasPermission(token(user("alice")), 102L, "Account", "read")).isTrue();
    }

    private static AuthenticatedUser user(String username) {
        return new AuthenticatedUser(matrix.at("/users/" + username).asLong(), username, "admin".equals(username));
    }

    private static FfcrmAuthenticationToken token(AuthenticatedUser user) {
        Jwt jwt = Jwt.withTokenValue("corpus").header("alg", "none").subject(user.id().toString()).build();
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
}
