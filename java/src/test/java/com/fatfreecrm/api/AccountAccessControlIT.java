package com.fatfreecrm.api;

import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PRIVATE;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PUBLIC;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_SHARED;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.security.TrustedHeaderAuthenticationFilter;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Negative access-control and regression cases for {@code /api/v1/accounts} that complement
 * {@link AccountControllerIT}: every denial path must be a 404/401 on all three endpoints, not
 * just absent from a happy-path list.
 *
 * <pre>
 * users:    A (1), B (2, member of group G=100), admin C (3), Z (4), D (5, no groups),
 *           S (6, suspended), X (7, soft-deleted), H (8, member of group H=101 only)
 * accounts: 10 "Acme"    Public   owned by A
 *           12 "Bravo"   Private  owned by Z, assigned_to B
 *           13 "Charlie" Private  owned by Z, permission -> user B
 *           14 "Delta"   Private  owned by Z, permission -> group G
 *           15 "Echo"    Public   owned by Z, soft-deleted
 *           17 "Golf"    Shared   owned by Z, no permission rows
 *           18 "Hotel"   Private  owned by Z, permission -> user D on asset_type Contact (not Account)
 * </pre>
 */
class AccountAccessControlIT extends AbstractIntegrationTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long C = 3;
    private static final long Z = 4;
    private static final long D = 5;
    private static final long S = 6;
    private static final long X = 7;
    private static final long H = 8;
    private static final long G = 100;
    private static final long GROUP_H = 101;

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
        seeder.insertUser(D, "dave", false);
        seeder.insertUser(S, "sue", false, LocalDateTime.now().minusDays(1));
        seeder.insertUser(X, "xavier", false);
        seeder.softDeleteUser(X);
        seeder.insertUser(H, "hank", false);
        seeder.insertGroup(G, "sales");
        seeder.insertGroup(GROUP_H, "hr");
        seeder.addUserToGroup(B, G);
        seeder.addUserToGroup(H, GROUP_H);

        seeder.insertAccount(10, "Acme", A, null, ACCESS_PUBLIC);
        seeder.insertAccount(12, "Bravo", Z, B, ACCESS_PRIVATE);
        seeder.insertAccount(13, "Charlie", Z, null, ACCESS_PRIVATE);
        seeder.insertPermission("Account", 13, B, null);
        seeder.insertAccount(14, "Delta", Z, null, ACCESS_PRIVATE);
        seeder.insertPermission("Account", 14, null, G);
        seeder.insertAccount(15, "Echo", Z, null, ACCESS_PUBLIC, LocalDateTime.now().minusDays(1));
        seeder.insertAccount(17, "Golf", Z, null, ACCESS_SHARED);
        seeder.insertAccount(18, "Hotel", Z, null, ACCESS_PRIVATE);
        seeder.insertPermission("Contact", 18, D, null);
    }

    @Nested
    class CrossUserDenial {

        @Test
        void userWithNoGrantsSeesOnlyPublicRows() {
            assertThat(listIds(D, "?sortBy=name")).containsExactly(10L);
            assertThat(autocompleteIds(D, "")).containsExactly(10L);
        }

        @ParameterizedTest
        @ValueSource(longs = {12, 13, 14, 17, 18})
        void userWithNoGrantsGets404ForEachNonPublicRow(long accountId) {
            assertProblem(get("/api/v1/accounts/" + accountId, D), HttpStatus.NOT_FOUND, "/api/v1/accounts/" + accountId);
        }

        @Test
        void grantsToAnotherUserDoNotLeakToTheOwnerOfOtherRows() {
            assertProblem(get("/api/v1/accounts/12", A), HttpStatus.NOT_FOUND, "/api/v1/accounts/12");
            assertProblem(get("/api/v1/accounts/13", A), HttpStatus.NOT_FOUND, "/api/v1/accounts/13");
            assertProblem(get("/api/v1/accounts/14", A), HttpStatus.NOT_FOUND, "/api/v1/accounts/14");
            assertThat(autocompleteIds(A, "")).containsExactly(10L);
        }

        @Test
        void groupGrantDoesNotReachMembersOfOtherGroups() {
            assertThat(listIds(H, "")).containsExactly(10L);
            assertProblem(get("/api/v1/accounts/14", H), HttpStatus.NOT_FOUND, "/api/v1/accounts/14");
            assertThat(autocompleteIds(H, "delta")).isEmpty();
        }

        @Test
        void sharedRowWithoutPermissionRowsIsOnlyVisibleToOwnerAndAdmin() {
            assertThat(ok(get("/api/v1/accounts/17", Z)).getBody().get("name").asText()).isEqualTo("Golf");
            assertThat(ok(get("/api/v1/accounts/17", C)).getBody().get("name").asText()).isEqualTo("Golf");
            assertProblem(get("/api/v1/accounts/17", B), HttpStatus.NOT_FOUND, "/api/v1/accounts/17");
            assertThat(listIds(B, "?sortBy=name")).containsExactly(10L, 12L, 13L, 14L);
            assertThat(autocompleteIds(B, "golf")).isEmpty();
            assertThat(autocompleteIds(Z, "golf")).containsExactly(17L);
        }

        @Test
        void permissionOnAnotherAssetTypeDoesNotGrantAccountAccess() {
            assertProblem(get("/api/v1/accounts/18", D), HttpStatus.NOT_FOUND, "/api/v1/accounts/18");
            assertThat(autocompleteIds(D, "hotel")).isEmpty();
            assertThat(ok(get("/api/v1/accounts/18", Z)).getBody().get("name").asText()).isEqualTo("Hotel");
        }

        @Test
        void ownerSeesOwnPrivateAndSharedRows() {
            assertThat(listIds(Z, "?sortBy=name")).containsExactly(10L, 12L, 13L, 14L, 17L, 18L);
            assertThat(autocompleteIds(Z, "")).containsExactly(10L, 12L, 13L, 14L, 17L, 18L);
        }

        @Test
        void adminSeesEverythingExceptSoftDeletedRows() {
            assertThat(listIds(C, "?sortBy=name")).containsExactly(10L, 12L, 13L, 14L, 17L, 18L);
            assertThat(autocompleteIds(C, "echo")).isEmpty();
            assertProblem(get("/api/v1/accounts/15", C), HttpStatus.NOT_FOUND, "/api/v1/accounts/15");
        }

        @Test
        void revokingGroupMembershipRevokesAccess() {
            assertThat(ok(get("/api/v1/accounts/14", B)).getBody().get("id").asLong()).isEqualTo(14);

            seeder.jdbc().update("DELETE FROM groups_users WHERE user_id = ? AND group_id = ?", B, G);

            assertProblem(get("/api/v1/accounts/14", B), HttpStatus.NOT_FOUND, "/api/v1/accounts/14");
            assertThat(listIds(B, "?sortBy=name")).containsExactly(10L, 12L, 13L);
        }

        @Test
        void revokingUserPermissionRevokesAccess() {
            seeder.jdbc().update("DELETE FROM permissions WHERE asset_type = 'Account' AND asset_id = 13");

            assertProblem(get("/api/v1/accounts/13", B), HttpStatus.NOT_FOUND, "/api/v1/accounts/13");
            assertThat(autocompleteIds(B, "charlie")).isEmpty();
        }
    }

    @Nested
    class Authentication {

        @ParameterizedTest
        @ValueSource(strings = {"/api/v1/accounts", "/api/v1/accounts/10", "/api/v1/accounts/autocomplete?term=a"})
        void suspendedUserIsRejectedWith401(String path) {
            assertProblem(get(path, S), HttpStatus.UNAUTHORIZED, path.replace("?term=a", ""));
        }

        @ParameterizedTest
        @ValueSource(strings = {"/api/v1/accounts", "/api/v1/accounts/10", "/api/v1/accounts/autocomplete?term=a"})
        void softDeletedUserIsRejectedWith401(String path) {
            assertProblem(get(path, X), HttpStatus.UNAUTHORIZED, path.replace("?term=a", ""));
        }

        @ParameterizedTest
        @ValueSource(strings = {"/api/v1/accounts", "/api/v1/accounts/10", "/api/v1/accounts/autocomplete?term=a"})
        void unknownUserIdIsRejectedWith401(String path) {
            assertProblem(get(path, 999L), HttpStatus.UNAUTHORIZED, path.replace("?term=a", ""));
        }

        @Test
        void nonNumericUserHeaderIsRejectedWith401() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(TrustedHeaderAuthenticationFilter.HEADER, "1 OR 1=1");
            ResponseEntity<String> response = rest.exchange(URI.create(url("/api/v1/accounts")), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);

            assertProblem(response, HttpStatus.UNAUTHORIZED, "/api/v1/accounts");
        }
    }

    @Nested
    class ReadOnlySurface {

        @Test
        void writeVerbsAreNotSupportedOnTheCollection() {
            assertProblem(exchange(HttpMethod.POST, "/api/v1/accounts", C, "{\"name\":\"New\"}"),
                    HttpStatus.METHOD_NOT_ALLOWED, "/api/v1/accounts");
            assertThat(seeder.jdbc().queryForObject("SELECT count(*) FROM accounts WHERE name = 'New'", Long.class))
                    .isZero();
        }

        @ParameterizedTest
        @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
        void writeVerbsAreNotSupportedOnAMember(String verb) {
            assertProblem(exchange(HttpMethod.valueOf(verb), "/api/v1/accounts/10", C, "{\"name\":\"Renamed\"}"),
                    HttpStatus.METHOD_NOT_ALLOWED, "/api/v1/accounts/10");
            assertThat(seeder.jdbc().queryForObject("SELECT name FROM accounts WHERE id = 10", String.class))
                    .isEqualTo("Acme");
        }

        @Test
        void writeVerbsWithoutAuthenticationAre401NotLeakingRouteExistence() {
            assertProblem(exchange(HttpMethod.DELETE, "/api/v1/accounts/10", null, null),
                    HttpStatus.UNAUTHORIZED, "/api/v1/accounts/10");
        }
    }

    @Nested
    class InputValidation {

        @Test
        void nonNumericPagingParametersAre400() {
            assertProblem(get("/api/v1/accounts?page=abc", C), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
            assertProblem(get("/api/v1/accounts?perPage=abc", C), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
            assertProblem(get("/api/v1/accounts?per_page=1.5", C), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
        }

        @Test
        void bothAliasesAreValidatedEvenWhenOnlyTheCamelCaseValueIsUsed() {
            assertProblem(get("/api/v1/accounts?perPage=5&per_page=0", C), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
            assertProblem(get("/api/v1/accounts?sortBy=name&sort_by=bogus", C), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
            assertProblem(get("/api/v1/accounts?perPage=0&per_page=5", C), HttpStatus.BAD_REQUEST, "/api/v1/accounts");
            assertProblem(get("/api/v1/accounts?page=0&per_page=5", C), HttpStatus.BAD_REQUEST, "/api/v1/accounts");

            JsonNode body = ok(get("/api/v1/accounts?perPage=5&per_page=7&sortBy=name&sort_by=rating", C)).getBody();
            assertThat(body.get("perPage").asInt()).isEqualTo(5);
            assertThat(ids(body)).containsExactly(10L, 12L, 13L, 14L, 17L);
        }

        @Test
        void validationProblemDetailListsEveryViolation() {
            ResponseEntity<String> response = get("/api/v1/accounts?page=0&perPage=0&sortBy=bogus", C);

            assertProblem(response, HttpStatus.BAD_REQUEST, "/api/v1/accounts");
            String detail = parse(response.getBody()).get("detail").asText();
            assertThat(detail).contains("page").contains("perPage").contains("sortBy")
                    .contains("name ASC, rating DESC, created_at DESC, updated_at DESC");
        }

        @Test
        void unknownIdBoundariesAre404Or400() {
            assertProblem(get("/api/v1/accounts/0", C), HttpStatus.NOT_FOUND, "/api/v1/accounts/0");
            assertProblem(get("/api/v1/accounts/-1", C), HttpStatus.NOT_FOUND, "/api/v1/accounts/-1");
            assertProblem(get("/api/v1/accounts/9223372036854775807", C), HttpStatus.NOT_FOUND,
                    "/api/v1/accounts/9223372036854775807");
            assertProblem(get("/api/v1/accounts/9223372036854775808", C), HttpStatus.BAD_REQUEST,
                    "/api/v1/accounts/9223372036854775808");
        }
    }

    @Nested
    class Ordering {

        @Test
        void equalSortKeysFallBackToIdSoPagesAreStable() {
            LocalDateTime same = LocalDateTime.of(2024, 3, 3, 12, 0, 0);
            seeder.jdbc().update("UPDATE accounts SET created_at = ?, updated_at = ?, rating = 3", same, same);

            assertThat(listIds(C, "")).containsExactly(18L, 17L, 14L, 13L, 12L, 10L);
            assertThat(listIds(C, "?sortBy=updated_at")).containsExactly(18L, 17L, 14L, 13L, 12L, 10L);
            assertThat(listIds(C, "?sortBy=rating")).containsExactly(18L, 17L, 14L, 13L, 12L, 10L);

            seeder.jdbc().update("UPDATE accounts SET name = 'Same'");
            assertThat(listIds(C, "?sortBy=name")).containsExactly(10L, 12L, 13L, 14L, 17L, 18L);
            assertThat(autocompleteIds(C, "same")).containsExactly(10L, 12L, 13L, 14L, 17L, 18L);

            assertThat(listIds(C, "?sortBy=name&perPage=2&page=1")).containsExactly(10L, 12L);
            assertThat(listIds(C, "?sortBy=name&perPage=2&page=2")).containsExactly(13L, 14L);
            assertThat(listIds(C, "?sortBy=name&perPage=2&page=3")).containsExactly(17L, 18L);
        }

        @Test
        void ratingDescOrdersHighestFirstWithUnratedRowsLast() {
            seeder.jdbc().update("UPDATE accounts SET rating = 2 WHERE id = 13");
            seeder.jdbc().update("UPDATE accounts SET rating = 4 WHERE id = 10");

            assertThat(listIds(C, "?sortBy=rating")).containsExactly(10L, 13L, 18L, 17L, 14L, 12L);
        }
    }

    @Nested
    class Timestamps {

        @Test
        void fractionalSecondsSurviveTheRoundTripAsUtc() {
            LocalDateTime created = LocalDateTime.of(2023, 11, 5, 23, 59, 59, 123_456_000);
            LocalDateTime updated = LocalDateTime.of(2024, 2, 29, 0, 0, 0, 999_999_000);
            seeder.jdbc().update("UPDATE accounts SET created_at = ?, updated_at = ? WHERE id = 10", created, updated);

            JsonNode body = ok(get("/api/v1/accounts/10", A)).getBody();

            assertThat(OffsetDateTime.parse(body.get("created_at").asText()))
                    .isEqualTo(created.atOffset(ZoneOffset.UTC));
            assertThat(OffsetDateTime.parse(body.get("updated_at").asText()))
                    .isEqualTo(updated.atOffset(ZoneOffset.UTC));
            assertThat(body.get("created_at").asText()).endsWith("Z");
            assertThat(body.get("deleted_at").isNull()).isTrue();
        }

        @Test
        void listAndShowSerialiseTheSameInstant() {
            JsonNode show = ok(get("/api/v1/accounts/10", A)).getBody();
            JsonNode list = ok(get("/api/v1/accounts?query=acme", A)).getBody().get("items").get(0);

            assertThat(list.get("created_at").asText()).isEqualTo(show.get("created_at").asText());
            assertThat(list.get("updated_at").asText()).isEqualTo(show.get("updated_at").asText());
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private ResponseEntity<String> get(String path, Long userId) {
        return exchange(HttpMethod.GET, path, userId, null);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, Long userId, String body) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(TrustedHeaderAuthenticationFilter.HEADER, String.valueOf(userId));
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(URI.create(url(path)), method, new HttpEntity<>(body, headers), String.class);
    }

    private List<Long> listIds(long userId, String queryString) {
        return ids(ok(get("/api/v1/accounts" + queryString, userId)).getBody());
    }

    private List<Long> autocompleteIds(long userId, String term) {
        JsonNode body = ok(get("/api/v1/accounts/autocomplete?term=" + term, userId)).getBody();
        return longs(body.get("results").findValues("id"));
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
