package com.fatfreecrm.api;

import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PRIVATE;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PUBLIC;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_SHARED;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.security.TrustedHeaderAuthenticationFilter;
import com.fatfreecrm.service.AccountSort;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Access-control and parameter-boundary cases for {@code /api/v1/accounts} that complement
 * {@link AccountControllerIT}: the Rails {@code Shared} access level over HTTP, rejected
 * identities on the account endpoints, the {@code perPage} / {@code sortBy} boundaries and the
 * problem+json details the client actually receives.
 *
 * <pre>
 * users:    A (1), B (2, member of group G=100), admin C (3), Z (4),
 *           S (5, suspended), D (6, soft-deleted)
 * accounts: 20 "Shared-User"  Shared  owned by Z, permission -> user B
 *           21 "Shared-Group" Shared  owned by Z, permission -> group G
 *           22 "Shared-None"  Shared  owned by Z, no permission rows
 *           23 "Public"       Public  owned by Z
 *           24 "Private"      Private owned by Z
 * </pre>
 */
class AccountControllerAuthzIT extends AbstractIntegrationTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long C = 3;
    private static final long Z = 4;
    private static final long S = 5;
    private static final long D = 6;
    private static final long G = 100;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void seed() {
        seeder.insertUser(A, "alice", false);
        seeder.insertUser(B, "bob", false);
        seeder.insertUser(C, "carol", true);
        seeder.insertUser(Z, "zed", false);
        seeder.insertUser(S, "suspended", false, LocalDateTime.now().minusDays(1));
        seeder.insertUser(D, "deleted", false);
        seeder.softDeleteUser(D);
        seeder.insertGroup(G, "sales");
        seeder.addUserToGroup(B, G);

        seeder.insertAccount(20, "Shared-User", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Account", 20, B, null);
        seeder.insertAccount(21, "Shared-Group", Z, null, ACCESS_SHARED);
        seeder.insertPermission("Account", 21, null, G);
        seeder.insertAccount(22, "Shared-None", Z, null, ACCESS_SHARED);
        seeder.insertAccount(23, "Public", Z, null, ACCESS_PUBLIC);
        seeder.insertAccount(24, "Private", Z, null, ACCESS_PRIVATE);
    }

    @Nested
    class SharedAccessLevel {

        @Test
        void granteeSeesSharedRowsThroughUserAndGroupPermissions() {
            assertThat(listIds(B, "?sortBy=name")).containsExactly(23L, 21L, 20L);
            assertThat(ok(get("/api/v1/accounts/20", B)).getBody().get("name").asText()).isEqualTo("Shared-User");
            assertThat(ok(get("/api/v1/accounts/21", B)).getBody().get("name").asText()).isEqualTo("Shared-Group");
            assertThat(autocompleteIds(B, "shared")).containsExactly(21L, 20L);
        }

        @Test
        void nonGranteeCannotSeeSharedRowsAnywhere() {
            assertThat(listIds(A, "")).containsExactly(23L);
            assertThat(listIds(A, "?query=shared")).isEmpty();
            assertThat(autocompleteIds(A, "shared")).isEmpty();
            assertProblem(get("/api/v1/accounts/20", A), HttpStatus.NOT_FOUND, "/api/v1/accounts/20");
            assertProblem(get("/api/v1/accounts/21", A), HttpStatus.NOT_FOUND, "/api/v1/accounts/21");
            assertProblem(get("/api/v1/accounts/22", A), HttpStatus.NOT_FOUND, "/api/v1/accounts/22");
        }

        @Test
        void sharedWithoutAnyPermissionIsVisibleOnlyToOwnerAndAdmin() {
            assertProblem(get("/api/v1/accounts/22", B), HttpStatus.NOT_FOUND, "/api/v1/accounts/22");
            assertThat(ok(get("/api/v1/accounts/22", Z)).getBody().get("name").asText()).isEqualTo("Shared-None");
            assertThat(ok(get("/api/v1/accounts/22", C)).getBody().get("name").asText()).isEqualTo("Shared-None");
            assertThat(listIds(Z, "?sortBy=name")).containsExactly(24L, 23L, 21L, 22L, 20L);
            assertThat(listIds(C, "?sortBy=name")).containsExactly(24L, 23L, 21L, 22L, 20L);
        }

        @Test
        void notFoundDetailIsIdenticalForMissingAndInvisibleIds() {
            JsonNode invisible = problem(get("/api/v1/accounts/24", A), HttpStatus.NOT_FOUND);
            JsonNode missing = problem(get("/api/v1/accounts/999", A), HttpStatus.NOT_FOUND);

            assertThat(invisible.get("detail").asText()).isEqualTo("Account with id 24 not found");
            assertThat(missing.get("detail").asText()).isEqualTo("Account with id 999 not found");
            assertThat(fieldNames(invisible)).containsExactlyElementsOf(fieldNames(missing));
        }
    }

    @Nested
    class RejectedIdentities {

        @Test
        void unknownUserIdIs401OnEveryAccountEndpoint() {
            assertProblem(get("/api/v1/accounts", 9999L), HttpStatus.UNAUTHORIZED, "/api/v1/accounts");
            assertProblem(get("/api/v1/accounts/23", 9999L), HttpStatus.UNAUTHORIZED, "/api/v1/accounts/23");
            assertProblem(get("/api/v1/accounts/autocomplete?term=p", 9999L), HttpStatus.UNAUTHORIZED,
                    "/api/v1/accounts/autocomplete");
        }

        @Test
        void suspendedAndSoftDeletedUsersAre401EvenForPublicRows() {
            assertProblem(get("/api/v1/accounts/23", S), HttpStatus.UNAUTHORIZED, "/api/v1/accounts/23");
            assertProblem(get("/api/v1/accounts/23", D), HttpStatus.UNAUTHORIZED, "/api/v1/accounts/23");
            assertProblem(get("/api/v1/accounts", S), HttpStatus.UNAUTHORIZED, "/api/v1/accounts");
            assertProblem(get("/api/v1/accounts", D), HttpStatus.UNAUTHORIZED, "/api/v1/accounts");
        }

        @Test
        void nonNumericUserHeaderIs401() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(TrustedHeaderAuthenticationFilter.HEADER, "alice");
            ResponseEntity<String> response = rest.exchange(
                    URI.create(url("/api/v1/accounts")), HttpMethod.GET, new HttpEntity<>(headers), String.class);

            assertProblem(response, HttpStatus.UNAUTHORIZED, "/api/v1/accounts");
        }

        @Test
        void unauthenticatedProblemDoesNotRevealWhetherTheAccountExists() {
            JsonNode existing = problem(get("/api/v1/accounts/23", null), HttpStatus.UNAUTHORIZED);
            JsonNode missing = problem(get("/api/v1/accounts/999", null), HttpStatus.UNAUTHORIZED);

            assertThat(existing.get("detail").asText()).isEqualTo(missing.get("detail").asText());
        }
    }

    @Nested
    class ParameterBoundaries {

        @Test
        void perPageAcceptsTheWholeInclusiveRangeAndClampsAbove() {
            assertThat(ok(get("/api/v1/accounts?perPage=1", C)).getBody().get("perPage").asInt()).isEqualTo(1);
            assertThat(ok(get("/api/v1/accounts?perPage=200", C)).getBody().get("perPage").asInt()).isEqualTo(200);
            assertThat(ok(get("/api/v1/accounts?perPage=201", C)).getBody().get("perPage").asInt()).isEqualTo(200);
            assertThat(ok(get("/api/v1/accounts?perPage=999999999", C)).getBody().get("perPage").asInt())
                    .isEqualTo(200);
        }

        @Test
        void perPageToleratesSurroundingWhitespaceAndOverflowButNotSignsOrLeadingZeros() {
            assertThat(ok(get("/api/v1/accounts?perPage=%205%20", C)).getBody().get("perPage").asInt()).isEqualTo(5);
            assertThat(ok(get("/api/v1/accounts?perPage=2147483648", C)).getBody().get("perPage").asInt())
                    .isEqualTo(200);
            assertThat(ok(get("/api/v1/accounts?perPage=" + "9".repeat(40), C)).getBody().get("perPage").asInt())
                    .isEqualTo(200);
            assertProblem(get("/api/v1/accounts?perPage=%2B5", C), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
            assertProblem(get("/api/v1/accounts?perPage=-5", C), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
            assertProblem(get("/api/v1/accounts?perPage=05", C), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
        }

        @Test
        void pageOneIsTheLowerBoundAndFarPagesAreEmptyWithTotalCount() {
            JsonNode first = ok(get("/api/v1/accounts?page=1&perPage=1&sortBy=name", C)).getBody();
            assertThat(first.get("page").asInt()).isEqualTo(1);
            assertThat(first.get("items")).hasSize(1);

            JsonNode far = ok(get("/api/v1/accounts?page=1000&perPage=200", C)).getBody();
            assertThat(far.get("items")).isEmpty();
            assertThat(far.get("page").asInt()).isEqualTo(1000);
            assertThat(far.get("totalCount").asLong()).isEqualTo(5);

            assertProblem(get("/api/v1/accounts?page=-1", C), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
        }

        @Test
        void blankOrPaddedSortByFallsBackToOrIsParsedAsTheRailsOrder() {
            assertThat(listIds(C, "?sortBy=")).isEqualTo(listIds(C, ""));
            assertThat(listIds(C, "?sortBy=%20%20")).isEqualTo(listIds(C, "?sortBy=created_at%20DESC"));
            assertThat(listIds(C, "?sortBy=%20name%20%20asc%20")).containsExactly(24L, 23L, 21L, 22L, 20L);
        }

        @Test
        void validationProblemsCarryTheConstraintMessageInDetail() {
            assertThat(problem(get("/api/v1/accounts?sortBy=email", C), HttpStatus.BAD_REQUEST).get("detail").asText())
                    .contains("sortBy").contains(AccountSort.PATTERN_MESSAGE);
            assertThat(problem(get("/api/v1/accounts?perPage=0", C), HttpStatus.BAD_REQUEST).get("detail").asText())
                    .contains("perPage").contains("must be a positive integer");
            assertThat(problem(get("/api/v1/accounts?perPage=0&sortBy=id", C), HttpStatus.BAD_REQUEST)
                    .get("detail").asText())
                    .contains("perPage must be a positive integer")
                    .contains("sortBy " + AccountSort.PATTERN_MESSAGE);
            assertThat(problem(get("/api/v1/accounts?page=0", C), HttpStatus.BAD_REQUEST).get("detail").asText())
                    .contains("page");
            assertThat(problem(get("/api/v1/accounts?page=x", C), HttpStatus.BAD_REQUEST).get("detail").asText())
                    .isEqualTo("Parameter 'page' has invalid value 'x' (expected int)");
        }

        @Test
        void autocompleteTermTreatsWildcardsLiterallyAndWhitespaceAsBlank() {
            assertThat(autocompleteIds(C, "%25")).isEmpty();
            assertThat(autocompleteIds(C, "_")).isEmpty();
            assertThat(autocompleteIds(C, "%20%20")).containsExactly(24L, 23L, 21L, 22L, 20L);
            assertThat(autocompleteIds(C, "")).containsExactly(24L, 23L, 21L, 22L, 20L);
        }
    }

    @Nested
    class ReadOnlySurface {

        @Test
        void writesAreRejectedWith405ProblemJson() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(TrustedHeaderAuthenticationFilter.HEADER, String.valueOf(C));
            headers.setContentType(MediaType.APPLICATION_JSON);

            for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
                ResponseEntity<String> response = rest.exchange(URI.create(url("/api/v1/accounts/23")), method,
                        new HttpEntity<>("{\"name\":\"x\"}", headers), String.class);
                assertThat(response.getStatusCode()).as("%s -> %s", method, response.getBody())
                        .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
                assertThat(response.getHeaders().getContentType()).isNotNull();
                assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .isTrue();
            }
            assertThat(ok(get("/api/v1/accounts/23", C)).getBody().get("name").asText()).isEqualTo("Public");
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private ResponseEntity<String> get(String path, Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(TrustedHeaderAuthenticationFilter.HEADER, String.valueOf(userId));
        }
        return rest.exchange(URI.create(url(path)), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private List<Long> listIds(long userId, String queryString) {
        return longs(ok(get("/api/v1/accounts" + queryString, userId)).getBody().get("items").findValues("id"));
    }

    private List<Long> autocompleteIds(long userId, String encodedTerm) {
        return longs(ok(get("/api/v1/accounts/autocomplete?term=" + encodedTerm, userId)).getBody()
                .get("results").findValues("id"));
    }

    private ResponseEntity<JsonNode> ok(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        return ResponseEntity.ok(parse(response.getBody()));
    }

    private JsonNode problem(ResponseEntity<String> response, HttpStatus status) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        JsonNode body = parse(response.getBody());
        assertThat(body.get("status").asInt()).isEqualTo(status.value());
        return body;
    }

    private void assertProblem(ResponseEntity<String> response, HttpStatus status, String instance) {
        JsonNode body = problem(response, status);
        assertThat(body.get("title").asText()).isEqualTo(status.getReasonPhrase());
        assertThat(body.get("detail").asText()).isNotBlank();
        assertThat(body.get("instance").asText()).isEqualTo(instance);
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body);
        } catch (java.io.IOException e) {
            throw new AssertionError("Response is not JSON: " + body, e);
        }
    }

    private static List<Long> longs(Iterable<JsonNode> nodes) {
        return StreamSupport.stream(nodes.spliterator(), false).map(JsonNode::asLong).toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
