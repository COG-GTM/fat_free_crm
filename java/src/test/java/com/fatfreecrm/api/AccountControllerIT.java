package com.fatfreecrm.api;

import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PRIVATE;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.security.TrustedHeaderAuthenticationFilter;
import com.fatfreecrm.support.TestDataSeeder.AccountRow;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * End-to-end tests for {@code /api/v1/accounts}.
 *
 * <pre>
 * users:    A (1), B (2, member of group G=100), admin C (3), Z (4)
 * accounts: 10 "Acme"    Public   owned by A, email sales@acme.example, rating 1, created 2024-01-01
 *           11 "Alpha"   Private  owned by A,                            rating 5, created 2024-06-01
 *           12 "Bravo"   Private  owned by Z, assigned_to B
 *           13 "Charlie" Private  owned by Z, permission -> user B
 *           14 "Delta"   Private  owned by Z, permission -> group G
 *           15 "Echo"    Public   owned by Z, soft-deleted
 *           16 "Foxtrot" Private  owned by Z (visible to Z and admins only)
 * </pre>
 */
class AccountControllerIT extends AbstractIntegrationTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long C = 3;
    private static final long Z = 4;
    private static final long G = 100;

    /** Property order of the {@code Account} component schema in docs/migration/openapi.yaml. */
    private static final List<String> OPENAPI_ACCOUNT_FIELDS = List.of(
            "id", "user_id", "assigned_to", "name", "access", "website", "toll_free_phone", "phone", "fax",
            "email", "background_info", "rating", "category", "subscribed_users", "contacts_count",
            "opportunities_count", "wikidata_id", "latitude", "longitude", "blog", "linkedin", "facebook",
            "twitter", "bluesky", "instagram", "mastodon", "deleted_at", "created_at", "updated_at");

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
        seeder.insertGroup(G, "sales");
        seeder.addUserToGroup(B, G);

        seeder.insertAccount(new AccountRow(10, A, null, "Acme", ACCESS_PUBLIC, "https://acme.example",
                "1-800-ACME", "555-0100", "555-0101", "sales@acme.example", "Biggest customer", 1, "Customer",
                "---\n- 1\n- 2\n", 3, 2, "Q42", new BigDecimal("51.500000"), new BigDecimal("-0.120000"),
                "https://blog.acme.example", "acme", "acme.inc", "acme", "acme.bsky.social", "acme_inc",
                "@acme@mastodon.example", null,
                LocalDateTime.of(2024, 1, 1, 10, 0, 0), LocalDateTime.of(2024, 1, 2, 10, 0, 0)));
        seeder.insertAccount(11, "Alpha", A, null, ACCESS_PRIVATE);
        seeder.jdbc().update("UPDATE accounts SET rating = 5, created_at = ?, updated_at = ? WHERE id = 11",
                LocalDateTime.of(2024, 6, 1, 10, 0, 0), LocalDateTime.of(2024, 6, 1, 10, 0, 0));
        seeder.insertAccount(12, "Bravo", Z, B, ACCESS_PRIVATE);
        seeder.insertAccount(13, "Charlie", Z, null, ACCESS_PRIVATE);
        seeder.insertPermission("Account", 13, B, null);
        seeder.insertAccount(14, "Delta", Z, null, ACCESS_PRIVATE);
        seeder.insertPermission("Account", 14, null, G);
        seeder.insertAccount(15, "Echo", Z, null, ACCESS_PUBLIC, LocalDateTime.now().minusDays(1));
        seeder.insertAccount(16, "Foxtrot", Z, null, ACCESS_PRIVATE);
    }

    @Nested
    class List_ {

        @Test
        void returnsOnlyRowsVisibleToTheCaller() {
            assertThat(listIds(A, "?sortBy=name")).containsExactly(10L, 11L);
            assertThat(listIds(B, "?sortBy=name")).containsExactly(10L, 12L, 13L, 14L);
            assertThat(listIds(C, "?sortBy=name")).containsExactly(10L, 11L, 12L, 13L, 14L, 16L);
        }

        @Test
        void envelopeCarriesTotalCountAndEffectivePaging() {
            JsonNode body = ok(get("/api/v1/accounts", C)).getBody();

            assertThat(body.get("totalCount").asLong()).isEqualTo(6);
            assertThat(body.get("page").asInt()).isEqualTo(1);
            assertThat(body.get("perPage").asInt()).isEqualTo(20);
            assertThat(body.get("items")).hasSize(6);
            assertThat(fieldNames(body)).containsExactly("items", "page", "perPage", "totalCount");
        }

        @Test
        void paginatesOneBasedPages() {
            JsonNode body = ok(get("/api/v1/accounts?sortBy=name&page=2&perPage=2", C)).getBody();

            assertThat(ids(body)).containsExactly(12L, 13L);
            assertThat(body.get("page").asInt()).isEqualTo(2);
            assertThat(body.get("perPage").asInt()).isEqualTo(2);
            assertThat(body.get("totalCount").asLong()).isEqualTo(6);

            assertThat(ids(ok(get("/api/v1/accounts?sortBy=name&page=4&perPage=2", C)).getBody())).isEmpty();
        }

        @Test
        void perPageAboveMaximumIsClampedTo200() {
            JsonNode body = ok(get("/api/v1/accounts?perPage=500", C)).getBody();

            assertThat(body.get("perPage").asInt()).isEqualTo(200);
        }

        @Test
        void snakeCaseAliasesAreAccepted() {
            JsonNode body = ok(get("/api/v1/accounts?per_page=1&sort_by=name", C)).getBody();

            assertThat(body.get("perPage").asInt()).isEqualTo(1);
            assertThat(ids(body)).containsExactly(10L);
        }

        @Test
        void camelCaseWinsOverSnakeCaseAlias() {
            JsonNode body = ok(get("/api/v1/accounts?perPage=2&per_page=1&sortBy=name&sort_by=rating", C)).getBody();

            assertThat(body.get("perPage").asInt()).isEqualTo(2);
            assertThat(ids(body)).containsExactly(10L, 11L);
        }

        @Test
        void queryMatchesNameOrEmailSubstringCaseInsensitively() {
            assertThat(listIds(A, "?query=ACM")).containsExactly(10L);
            assertThat(listIds(A, "?query=lph")).containsExactly(11L);
            assertThat(listIds(A, "?query=%40acme.example")).containsExactly(10L);
            assertThat(listIds(A, "?query=nomatch")).isEmpty();
            assertThat(listIds(A, "?query=&sortBy=name")).containsExactly(10L, 11L);
        }

        @Test
        void queryTreatsSqlWildcardsLiterally() {
            assertThat(listIds(A, "?query=%25")).isEmpty();
            assertThat(listIds(A, "?query=_")).isEmpty();
        }

        @Test
        void queryOnlySearchesVisibleRows() {
            assertThat(listIds(A, "?query=Foxtrot")).isEmpty();
            assertThat(listIds(C, "?query=Foxtrot")).containsExactly(16L);
        }

        @Test
        void sortsByEachRailsSortableValue() {
            assertThat(listIds(A, "")).containsExactly(11L, 10L);
            assertThat(listIds(A, "?sortBy=created_at%20DESC")).containsExactly(11L, 10L);
            assertThat(listIds(A, "?sortBy=name%20ASC")).containsExactly(10L, 11L);
            assertThat(listIds(A, "?sortBy=name")).containsExactly(10L, 11L);
            assertThat(listIds(A, "?sortBy=rating%20DESC")).containsExactly(11L, 10L);
            assertThat(listIds(A, "?sortBy=rating")).containsExactly(11L, 10L);
            assertThat(listIds(A, "?sortBy=updated_at%20DESC")).containsExactly(11L, 10L);
            assertThat(listIds(A, "?sortBy=updatedAt")).containsExactly(11L, 10L);

            seeder.jdbc().update("UPDATE accounts SET updated_at = ? WHERE id = 10", LocalDateTime.of(2025, 1, 1, 0, 0));
            assertThat(listIds(A, "?sortBy=updated_at%20DESC")).containsExactly(10L, 11L);
        }

        @Test
        void unknownSortByIs400ProblemJson() {
            assertProblem(get("/api/v1/accounts?sortBy=email", A), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
            assertProblem(get("/api/v1/accounts?sortBy=name%20DESC", A), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
            assertProblem(get("/api/v1/accounts?sort_by=id", A), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
        }

        @Test
        void nonPositivePagingIs400ProblemJson() {
            assertProblem(get("/api/v1/accounts?page=0", A), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
            assertProblem(get("/api/v1/accounts?perPage=0", A), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
            assertProblem(get("/api/v1/accounts?per_page=-1", A), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
            assertProblem(get("/api/v1/accounts?page=abc", A), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
        }

        @Test
        void missingUserHeaderIs401ProblemJson() {
            assertProblem(get("/api/v1/accounts", null), HttpStatus.UNAUTHORIZED, "/api/v1/accounts");
        }
    }

    @Nested
    class Show {

        @Test
        void rendersTheOpenApiAccountShapeInSnakeCase() {
            JsonNode body = ok(get("/api/v1/accounts/10", A)).getBody();

            assertThat(fieldNames(body)).containsExactlyElementsOf(OPENAPI_ACCOUNT_FIELDS);
            assertThat(body.get("id").asLong()).isEqualTo(10);
            assertThat(body.get("user_id").asLong()).isEqualTo(A);
            assertThat(body.get("assigned_to").isNull()).isTrue();
            assertThat(body.get("name").asText()).isEqualTo("Acme");
            assertThat(body.get("access").asText()).isEqualTo("Public");
            assertThat(body.get("website").asText()).isEqualTo("https://acme.example");
            assertThat(body.get("toll_free_phone").asText()).isEqualTo("1-800-ACME");
            assertThat(body.get("phone").asText()).isEqualTo("555-0100");
            assertThat(body.get("fax").asText()).isEqualTo("555-0101");
            assertThat(body.get("email").asText()).isEqualTo("sales@acme.example");
            assertThat(body.get("background_info").asText()).isEqualTo("Biggest customer");
            assertThat(body.get("rating").asInt()).isEqualTo(1);
            assertThat(body.get("category").asText()).isEqualTo("Customer");
            assertThat(body.get("subscribed_users").isArray()).isTrue();
            assertThat(longs(body.get("subscribed_users"))).containsExactly(1L, 2L);
            assertThat(body.get("contacts_count").asInt()).isEqualTo(3);
            assertThat(body.get("opportunities_count").asInt()).isEqualTo(2);
            assertThat(body.get("wikidata_id").asText()).isEqualTo("Q42");
            assertThat(body.get("latitude").isNumber()).isTrue();
            assertThat(body.get("latitude").decimalValue()).isEqualByComparingTo("51.5");
            assertThat(body.get("longitude").decimalValue()).isEqualByComparingTo("-0.12");
            assertThat(body.get("blog").asText()).isEqualTo("https://blog.acme.example");
            assertThat(body.get("linkedin").asText()).isEqualTo("acme");
            assertThat(body.get("facebook").asText()).isEqualTo("acme.inc");
            assertThat(body.get("twitter").asText()).isEqualTo("acme");
            assertThat(body.get("bluesky").asText()).isEqualTo("acme.bsky.social");
            assertThat(body.get("instagram").asText()).isEqualTo("acme_inc");
            assertThat(body.get("mastodon").asText()).isEqualTo("@acme@mastodon.example");
            assertThat(body.get("deleted_at").isNull()).isTrue();
            assertThat(body.get("created_at").asText()).isEqualTo("2024-01-01T10:00:00Z");
            assertThat(body.get("updated_at").asText()).isEqualTo("2024-01-02T10:00:00Z");
        }

        @Test
        void emitsNullsAndEmptySubscribedUsersForSparseRows() {
            JsonNode body = ok(get("/api/v1/accounts/11", A)).getBody();

            assertThat(fieldNames(body)).containsExactlyElementsOf(OPENAPI_ACCOUNT_FIELDS);
            assertThat(body.get("website").isNull()).isTrue();
            assertThat(body.get("email").isNull()).isTrue();
            assertThat(body.get("latitude").isNull()).isTrue();
            assertThat(body.get("subscribed_users").isArray()).isTrue();
            assertThat(body.get("subscribed_users")).isEmpty();
        }

        @Test
        void listItemsUseTheSameShape() {
            JsonNode body = ok(get("/api/v1/accounts?sortBy=name", A)).getBody();

            assertThat(fieldNames(body.get("items").get(0))).containsExactlyElementsOf(OPENAPI_ACCOUNT_FIELDS);
        }

        @Test
        void sharedAndAssignedRowsAreVisibleToGrantees() {
            assertThat(ok(get("/api/v1/accounts/12", B)).getBody().get("name").asText()).isEqualTo("Bravo");
            assertThat(ok(get("/api/v1/accounts/13", B)).getBody().get("name").asText()).isEqualTo("Charlie");
            assertThat(ok(get("/api/v1/accounts/14", B)).getBody().get("name").asText()).isEqualTo("Delta");
            assertThat(ok(get("/api/v1/accounts/16", C)).getBody().get("name").asText()).isEqualTo("Foxtrot");
        }

        @Test
        void missingIdIs404ProblemJson() {
            assertProblem(get("/api/v1/accounts/999", A), HttpStatus.NOT_FOUND, "/api/v1/accounts/999");
        }

        @Test
        void softDeletedIdIs404ProblemJsonEvenForAdmins() {
            assertProblem(get("/api/v1/accounts/15", A), HttpStatus.NOT_FOUND, "/api/v1/accounts/15");
            assertProblem(get("/api/v1/accounts/15", C), HttpStatus.NOT_FOUND, "/api/v1/accounts/15");
        }

        @Test
        void invisibleRowIs404NotForbidden() {
            assertProblem(get("/api/v1/accounts/16", A), HttpStatus.NOT_FOUND, "/api/v1/accounts/16");
            assertProblem(get("/api/v1/accounts/11", B), HttpStatus.NOT_FOUND, "/api/v1/accounts/11");
        }

        @Test
        void nonNumericIdIs404ProblemJson() {
            ResponseEntity<String> response = get("/api/v1/accounts/acme", A);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getHeaders().getContentType()).isNotNull();
            assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
            assertThat(parse(response.getBody()).get("status").asInt()).isEqualTo(404);
        }

        @Test
        void missingUserHeaderIs401ProblemJson() {
            assertProblem(get("/api/v1/accounts/10", null), HttpStatus.UNAUTHORIZED, "/api/v1/accounts/10");
        }
    }

    @Nested
    class Autocomplete {

        @Test
        void returnsIdAndNameOfVisibleMatchesOrderedByName() {
            JsonNode body = ok(get("/api/v1/accounts/autocomplete?term=a", B)).getBody();

            assertThat(fieldNames(body)).containsExactly("results");
            JsonNode results = body.get("results");
            assertThat(results).allSatisfy(item -> assertThat(fieldNames(item)).containsExactly("id", "text"));
            assertThat(longs(results.findValues("id"))).containsExactly(10L, 12L, 13L, 14L);
            assertThat(results.findValuesAsText("text")).containsExactly("Acme", "Bravo", "Charlie", "Delta");
        }

        @Test
        void termMatchesEmailTooAndBlankTermMatchesEverythingVisible() {
            assertThat(longs(ok(get("/api/v1/accounts/autocomplete?term=sales%40", A)).getBody()
                    .get("results").findValues("id"))).containsExactly(10L);
            assertThat(longs(ok(get("/api/v1/accounts/autocomplete", A)).getBody()
                    .get("results").findValues("id"))).containsExactly(10L, 11L);
            assertThat(longs(ok(get("/api/v1/accounts/autocomplete?term=Foxtrot", A)).getBody()
                    .get("results").findValues("id"))).isEmpty();
        }

        @Test
        void isLimitedToTenResults() {
            for (int i = 1; i <= 15; i++) {
                seeder.insertAccount(100 + i, String.format("Zeta %02d", i), Z, null, ACCESS_PUBLIC);
            }

            JsonNode results = ok(get("/api/v1/accounts/autocomplete?term=zeta", A)).getBody().get("results");

            assertThat(results).hasSize(10);
            assertThat(results.findValuesAsText("text")).first().isEqualTo("Zeta 01");
            assertThat(results.findValuesAsText("text")).last().isEqualTo("Zeta 10");
        }

        @Test
        void missingUserHeaderIs401ProblemJson() {
            assertProblem(get("/api/v1/accounts/autocomplete?term=a", null), HttpStatus.UNAUTHORIZED,
                    "/api/v1/accounts/autocomplete");
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
        return ids(ok(get("/api/v1/accounts" + queryString, userId)).getBody());
    }

    private ResponseEntity<JsonNode> ok(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        return ResponseEntity.ok(parse(response.getBody()));
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body);
        } catch (java.io.IOException e) {
            throw new AssertionError("Response is not JSON: " + body, e);
        }
    }

    private static List<Long> ids(JsonNode envelope) {
        return longs(envelope.get("items").findValues("id"));
    }

    private static List<Long> longs(Iterable<JsonNode> nodes) {
        return StreamSupport.stream(nodes.spliterator(), false).map(JsonNode::asLong).toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void assertProblem(ResponseEntity<String> response, HttpStatus status, String instance) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        JsonNode body = parse(response.getBody());
        assertThat(body.get("status").asInt()).isEqualTo(status.value());
        assertThat(body.get("title").asText()).isEqualTo(status.getReasonPhrase());
        assertThat(body.get("detail").asText()).isNotBlank();
        assertThat(body.get("instance").asText()).isEqualTo(instance);
    }
}
