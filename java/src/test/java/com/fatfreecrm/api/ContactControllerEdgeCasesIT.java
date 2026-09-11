package com.fatfreecrm.api;

import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PRIVATE;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PUBLIC;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_SHARED;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.security.TrustedHeaderAuthenticationFilter;
import com.fatfreecrm.support.TestDataSeeder.ContactRow;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
 * Parameter-handling, negative access-control and edge cases for {@code /api/v1/contacts} that
 * {@link ContactControllerIT} does not exercise.
 *
 * <pre>
 * users:    A (1), B (2, member of group G=100), admin C (3), Z (4), suspended S (5)
 * contacts: 10 Public   owned by A                 Alice Anderson  alice@corp.example
 *           11 Private  owned by A                 Bob Brown
 *           12 Shared   owned by Z (no permission) Carol Clark
 *           13 Private  owned by Z                 Dave Davis      (Account-type permission -> A)
 *           14 Private  owned by Z, perm -> G      Eve Evans
 *           15 Public   owned by Z, soft-deleted   Frank Foster
 * created_at descends with the id (10 newest ... 15 oldest).
 * </pre>
 */
class ContactControllerEdgeCasesIT extends AbstractIntegrationTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long C = 3;
    private static final long Z = 4;
    private static final long S = 5;
    private static final long G = 100;

    private static final LocalDateTime T0 = LocalDateTime.of(2024, 5, 1, 9, 30, 0);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seed() {
        seeder.insertUser(A, "alice", false);
        seeder.insertUser(B, "bob", false);
        seeder.insertUser(C, "carol", true);
        seeder.insertUser(Z, "zed", false);
        seeder.insertUser(S, "suspended", false, T0);
        seeder.insertGroup(G, "sales");
        seeder.addUserToGroup(B, G);

        seeder.insertContact(row(10, "Alice", "Anderson", A, ACCESS_PUBLIC, "alice@corp.example", null));
        seeder.insertContact(row(11, "Bob", "Brown", A, ACCESS_PRIVATE, null, null));
        seeder.insertContact(row(12, "Carol", "Clark", Z, ACCESS_SHARED, null, null));
        seeder.insertContact(row(13, "Dave", "Davis", Z, ACCESS_PRIVATE, null, null));
        seeder.insertPermission("Account", 13, A, null);
        seeder.insertContact(row(14, "Eve", "Evans", Z, ACCESS_PRIVATE, null, null));
        seeder.insertPermission("Contact", 14, null, G);
        seeder.insertContact(row(15, "Frank", "Foster", Z, ACCESS_PUBLIC, null, T0));
    }

    // ---- list: parameter aliases and blanks -------------------------------------------------

    @Test
    void camelCaseWinsOverSnakeCaseAlias() {
        JsonNode body = getJson("/api/v1/contacts?perPage=1&per_page=5&sortBy=first_name&sort_by=updated_at", C);

        assertThat(body.get("perPage").asInt()).isEqualTo(1);
        assertThat(ids(body.get("items"))).as("sorted by first_name, not updated_at").containsExactly(10L);
        assertThat(body.get("totalCount").asLong()).isEqualTo(5);
    }

    @Test
    void snakeCaseAliasAloneIsHonoured() {
        JsonNode body = getJson("/api/v1/contacts?per_page=2&sort_by=last_name%20ASC", C);

        assertThat(body.get("perPage").asInt()).isEqualTo(2);
        assertThat(ids(body.get("items"))).containsExactly(10L, 11L);
    }

    @Test
    void blankQueryDoesNotFilter() {
        List<Long> defaultOrder = List.of(10L, 11L, 12L, 13L, 14L);

        assertThat(ids(getJson("/api/v1/contacts?query=", C).get("items"))).containsExactlyElementsOf(defaultOrder);
        assertThat(ids(getJson("/api/v1/contacts?query=%20%20", C).get("items"))).containsExactlyElementsOf(defaultOrder);
        assertThat(getJson("/api/v1/contacts?query=%20%20", C).get("totalCount").asLong()).isEqualTo(5);
    }

    @Test
    void unknownQueryParametersAreIgnored() {
        JsonNode body = getJson("/api/v1/contacts?related=account_1&foo=bar&sortBy=first_name", A);

        assertThat(ids(body.get("items"))).containsExactly(10L, 11L);
    }

    @Test
    void queryCombinesWithPaginationAndSort() {
        // every seeded name ends in a letter of the alphabet; "a" hits Alice, Carol, Dave, Evans and alice@corp
        JsonNode first = getJson("/api/v1/contacts?query=a&perPage=2&page=1&sortBy=last_name", C);
        assertThat(ids(first.get("items"))).containsExactly(10L, 12L);
        assertThat(first.get("totalCount").asLong()).isEqualTo(4);

        JsonNode second = getJson("/api/v1/contacts?query=a&perPage=2&page=2&sortBy=last_name", C);
        assertThat(ids(second.get("items"))).containsExactly(13L, 14L);
        assertThat(second.get("totalCount").asLong()).isEqualTo(4);
    }

    @Test
    void invalidAliasValuesAre400Too() {
        assertProblem(get("/api/v1/contacts?per_page=0", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?per_page=abc", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?perPage=-1", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?page=-1", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?sortBy=id", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?sortBy=first_name%20asc", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?sortBy=created_at%20DESC;drop", C), HttpStatus.BAD_REQUEST,
                "/api/v1/contacts");
    }

    // ---- access control: negative paths -----------------------------------------------------

    @Test
    void sharedContactWithoutPermissionRowIsInvisibleToNonOwners() {
        assertThat(ids(getJson("/api/v1/contacts", A).get("items"))).doesNotContain(12L);
        assertThat(ids(getJson("/api/v1/contacts", B).get("items"))).doesNotContain(12L);
        assertProblem(get("/api/v1/contacts/12", A), HttpStatus.NOT_FOUND, "/api/v1/contacts/12");
        assertThat(getJson("/api/v1/contacts/autocomplete?term=Carol", A).get("results")).isEmpty();

        assertThat(get("/api/v1/contacts/12", Z).getStatusCode()).as("owner").isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/contacts/12", C).getStatusCode()).as("admin").isEqualTo(HttpStatus.OK);
    }

    @Test
    void permissionOnAnotherAssetTypeDoesNotGrantContactAccess() {
        assertThat(ids(getJson("/api/v1/contacts", A).get("items"))).doesNotContain(13L);
        assertProblem(get("/api/v1/contacts/13", A), HttpStatus.NOT_FOUND, "/api/v1/contacts/13");
        assertThat(getJson("/api/v1/contacts/autocomplete?term=Dave", A).get("results")).isEmpty();
    }

    @Test
    void groupPermissionDoesNotLeakToNonMembers() {
        assertThat(ids(getJson("/api/v1/contacts", B).get("items"))).contains(14L);
        assertThat(ids(getJson("/api/v1/contacts", A).get("items"))).doesNotContain(14L);
        assertProblem(get("/api/v1/contacts/14", A), HttpStatus.NOT_FOUND, "/api/v1/contacts/14");
    }

    @Test
    void ownerAndOwnerOfSharedRecordSeeTheirOwnRowsWithoutPermissionRows() {
        assertThat(ids(getJson("/api/v1/contacts?sortBy=first_name", Z).get("items")))
                .containsExactly(10L, 12L, 13L, 14L);
        assertThat(get("/api/v1/contacts/11", A).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void suspendedAndUnknownUsersAre401OnEveryContactEndpoint() {
        for (String user : List.of(String.valueOf(S), "9999", "abc")) {
            assertProblem(get("/api/v1/contacts", user), HttpStatus.UNAUTHORIZED, "/api/v1/contacts");
            assertProblem(get("/api/v1/contacts/10", user), HttpStatus.UNAUTHORIZED, "/api/v1/contacts/10");
            assertProblem(get("/api/v1/contacts/autocomplete?term=a", user), HttpStatus.UNAUTHORIZED,
                    "/api/v1/contacts/autocomplete");
        }
    }

    @Test
    void softDeletedOwnerStillCannotAuthenticateButTheirPublicContactsRemainVisible() {
        seeder.softDeleteUser(Z);

        assertProblem(get("/api/v1/contacts", String.valueOf(Z)), HttpStatus.UNAUTHORIZED, "/api/v1/contacts");
        assertThat(ids(getJson("/api/v1/contacts", C).get("items"))).contains(12L, 13L, 14L);
    }

    // ---- show: path edge cases --------------------------------------------------------------

    @Test
    void nonNumericAndNegativeIdsAre404ProblemJson() {
        assertProblem(get("/api/v1/contacts/abc", C), HttpStatus.NOT_FOUND, "/api/v1/contacts/abc");
        assertProblem(get("/api/v1/contacts/-1", C), HttpStatus.NOT_FOUND, "/api/v1/contacts/-1");
        assertProblem(get("/api/v1/contacts/10abc", C), HttpStatus.NOT_FOUND, "/api/v1/contacts/10abc");
        assertProblem(get("/api/v1/contacts/0", C), HttpStatus.NOT_FOUND, "/api/v1/contacts/0");
    }

    @Test
    void nonNumericIdWithoutHeaderIs401NotLeaking404() {
        assertProblem(get("/api/v1/contacts/abc", (String) null), HttpStatus.UNAUTHORIZED, "/api/v1/contacts/abc");
    }

    @Test
    void writeVerbsAreNotExposedOnTheReadOnlySlice() {
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
            ResponseEntity<String> list = exchange("/api/v1/contacts", method, String.valueOf(C));
            assertThat(list.getStatusCode()).as(method + " list").isIn(HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.FORBIDDEN);
            ResponseEntity<String> show = exchange("/api/v1/contacts/10", method, String.valueOf(C));
            assertThat(show.getStatusCode()).as(method + " show").isIn(HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.FORBIDDEN);
        }
        assertThat(get("/api/v1/contacts/10", C).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- autocomplete: edge cases -----------------------------------------------------------

    @Test
    void autocompleteTextIsFullNameEvenWhenMatchedByEmail() {
        JsonNode results = getJson("/api/v1/contacts/autocomplete?term=alice@corp", B).get("results");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("id").asLong()).isEqualTo(10);
        assertThat(results.get(0).get("text").asText()).isEqualTo("Alice Anderson");
    }

    @Test
    void autocompleteBlankTermListsEverythingVisibleAndNeverSoftDeleted() {
        assertThat(ids(getJson("/api/v1/contacts/autocomplete?term=", C).get("results")))
                .containsExactly(10L, 11L, 12L, 13L, 14L);
        assertThat(ids(getJson("/api/v1/contacts/autocomplete?term=%20", C).get("results")))
                .containsExactly(10L, 11L, 12L, 13L, 14L);
        assertThat(getJson("/api/v1/contacts/autocomplete?term=Frank", C).get("results")).isEmpty();
        assertThat(getJson("/api/v1/contacts/autocomplete?term=nobody", C).get("results")).isEmpty();
    }

    @Test
    void autocompleteTermWithSpaceUsesNamePermutations() {
        assertThat(ids(getJson("/api/v1/contacts/autocomplete?term=Bob%20Brown", A).get("results")))
                .containsExactly(11L);
        assertThat(ids(getJson("/api/v1/contacts/autocomplete?term=Brown%20Bob", A).get("results")))
                .containsExactly(11L);
        assertThat(getJson("/api/v1/contacts/autocomplete?term=Bob%20Anderson", A).get("results")).isEmpty();
    }

    @Test
    void autocompleteIgnoresPagingAndSortParameters() {
        for (int i = 0; i < 12; i++) {
            seeder.insertContact(row(100 + i, "Auto" + (char) ('a' + i), "Complete", Z, ACCESS_PUBLIC, null, null));
        }

        JsonNode results = getJson("/api/v1/contacts/autocomplete?term=complete&perPage=1&page=2&sortBy=id", C)
                .get("results");
        assertThat(results).hasSize(10);
        assertThat(results.get(0).get("text").asText()).isEqualTo("Autoa Complete");
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static ContactRow row(long id, String firstName, String lastName, Long userId, String access,
            String email, LocalDateTime deletedAt) {
        return new ContactRow(id, userId, null, null, null, firstName, lastName, access, null, null, null,
                email, null, null, null, null, null, null, null, null, null, false, null, null, null, null,
                null, null, null, null, deletedAt, T0.minusHours(id), T0.plusHours(id));
    }

    private ResponseEntity<String> get(String path, long userId) {
        return get(path, String.valueOf(userId));
    }

    private ResponseEntity<String> get(String path, String userHeader) {
        return exchange(path, HttpMethod.GET, userHeader);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, String userHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (userHeader != null) {
            headers.set(TrustedHeaderAuthenticationFilter.HEADER, userHeader);
        }
        return rest.exchange(URI.create(url(path)), method, new HttpEntity<>(headers), String.class);
    }

    private JsonNode getJson(String path, long userId) {
        ResponseEntity<String> response = get(path, userId);
        assertThat(response.getStatusCode()).as(path + " -> " + response.getBody()).isEqualTo(HttpStatus.OK);
        return json(response);
    }

    private JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (java.io.IOException e) {
            throw new AssertionError("Response is not JSON: " + response.getBody(), e);
        }
    }

    private static List<Long> ids(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(n -> ids.add(n.get("id").asLong()));
        return ids;
    }

    private void assertProblem(ResponseEntity<String> response, HttpStatus status, String instance) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        JsonNode body = json(response);
        assertThat(body.get("status").asInt()).isEqualTo(status.value());
        assertThat(body.get("title").asText()).isEqualTo(status.getReasonPhrase());
        assertThat(body.get("instance").asText()).isEqualTo(instance);
    }
}
