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
 * {@code /api/v1/contacts} through HTTP: the Rails {@code access = 'Shared'} semantics
 * ({@code Contact.my(user)} in lib/fat_free_crm/permissions.rb — a Shared record is visible to its
 * owner, its assignee and the users/groups holding a {@code permissions} row, and to nobody else),
 * permission rows that must <em>not</em> grant access, and request-parameter edge cases the main
 * controller test leaves open.
 *
 * <pre>
 * users:    A (1), B (2, in group G=100), admin C (3), Z (4), D (5, in group H=200)
 * contacts: 30 Shared  owned by Z, perm -> user B     Ivy Irwin
 *           31 Shared  owned by Z, perm -> group G    Jack Jones
 *           32 Shared  owned by Z, no permission rows Kate King
 *           33 Private owned by Z, perm -> user B but asset_type 'Account'   Liam Lowe
 *           34 Public  owned by Z                     Mia Moore
 *           35 Shared  owned by Z, assigned to D      Noah Nash
 * created_at descends with the id; updated_at ascends with the id.
 * </pre>
 */
class ContactControllerAccessAndEdgeCasesTest extends AbstractIntegrationTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long C = 3;
    private static final long Z = 4;
    private static final long D = 5;
    private static final long G = 100;
    private static final long H = 200;

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
        seeder.insertUser(D, "dan", false);
        seeder.insertGroup(G, "sales");
        seeder.insertGroup(H, "support");
        seeder.addUserToGroup(B, G);
        seeder.addUserToGroup(D, H);

        seeder.insertContact(row(30, "Ivy", "Irwin", Z, null, ACCESS_SHARED));
        seeder.insertPermission("Contact", 30, B, null);
        seeder.insertContact(row(31, "Jack", "Jones", Z, null, ACCESS_SHARED));
        seeder.insertPermission("Contact", 31, null, G);
        seeder.insertContact(row(32, "Kate", "King", Z, null, ACCESS_SHARED));
        seeder.insertContact(row(33, "Liam", "Lowe", Z, null, ACCESS_PRIVATE));
        seeder.insertPermission("Account", 33, B, null);
        seeder.insertContact(row(34, "Mia", "Moore", Z, null, ACCESS_PUBLIC));
        seeder.insertContact(row(35, "Noah", "Nash", Z, D, ACCESS_SHARED));
    }

    // ---- Shared access level ----------------------------------------------------------------

    @Test
    void sharedContactsAreVisibleOnlyToGranteesAssigneesAndTheOwner() {
        assertThat(listIds(B, "?sortBy=first_name")).as("user grant + group grant + public").containsExactly(30L, 31L, 34L);
        assertThat(listIds(D, "?sortBy=first_name")).as("assignee of 35, member of unrelated group H").containsExactly(34L, 35L);
        assertThat(listIds(A, "?sortBy=first_name")).as("no grants at all").containsExactly(34L);
        assertThat(listIds(Z, "?sortBy=first_name")).as("owner needs no permission rows").containsExactly(30L, 31L, 32L, 33L, 34L, 35L);
        assertThat(listIds(C, "?sortBy=first_name")).as("admin").containsExactly(30L, 31L, 32L, 33L, 34L, 35L);
    }

    @Test
    void sharedContactWithoutAnyPermissionRowIs404ForEveryoneButOwnerAndAdmin() {
        assertProblem(get("/api/v1/contacts/32", A), HttpStatus.NOT_FOUND, "/api/v1/contacts/32");
        assertProblem(get("/api/v1/contacts/32", B), HttpStatus.NOT_FOUND, "/api/v1/contacts/32");
        assertProblem(get("/api/v1/contacts/32", D), HttpStatus.NOT_FOUND, "/api/v1/contacts/32");
        assertThat(get("/api/v1/contacts/32", Z).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/contacts/32", C).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void groupGrantOnlyReachesMembersOfThatGroup() {
        assertThat(get("/api/v1/contacts/31", B).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertProblem(get("/api/v1/contacts/31", D), HttpStatus.NOT_FOUND, "/api/v1/contacts/31");
        assertProblem(get("/api/v1/contacts/31", A), HttpStatus.NOT_FOUND, "/api/v1/contacts/31");
    }

    @Test
    void userGrantOnlyReachesThatUser() {
        assertThat(get("/api/v1/contacts/30", B).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertProblem(get("/api/v1/contacts/30", A), HttpStatus.NOT_FOUND, "/api/v1/contacts/30");
        assertProblem(get("/api/v1/contacts/30", D), HttpStatus.NOT_FOUND, "/api/v1/contacts/30");
    }

    @Test
    void permissionRowForAnotherAssetTypeWithTheSameIdDoesNotLeakTheContact() {
        assertProblem(get("/api/v1/contacts/33", B), HttpStatus.NOT_FOUND, "/api/v1/contacts/33");
        assertThat(listIds(B, "")).doesNotContain(33L);
        assertThat(autocompleteIds(B, "Liam")).isEmpty();
    }

    @Test
    void autocompleteAppliesTheSameVisibilityAsListAndShow() {
        assertThat(autocompleteIds(B, "")).containsExactly(30L, 31L, 34L);
        assertThat(autocompleteIds(D, "")).containsExactly(34L, 35L);
        assertThat(autocompleteIds(A, "")).containsExactly(34L);
        assertThat(autocompleteIds(A, "Kate")).isEmpty();
        assertThat(autocompleteIds(Z, "")).containsExactly(30L, 31L, 32L, 33L, 34L, 35L);
    }

    @Test
    void unknownUserHeaderIs401OnEveryEndpoint() {
        assertProblem(get("/api/v1/contacts", 9999L), HttpStatus.UNAUTHORIZED, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts/34", 9999L), HttpStatus.UNAUTHORIZED, "/api/v1/contacts/34");
        assertProblem(get("/api/v1/contacts/autocomplete", 9999L), HttpStatus.UNAUTHORIZED,
                "/api/v1/contacts/autocomplete");
    }

    // ---- parameter aliases and edge cases ---------------------------------------------------

    @Test
    void camelCaseParameterWinsOverItsSnakeCaseAlias() {
        JsonNode body = getJson("/api/v1/contacts?perPage=2&per_page=1&sortBy=first_name&sort_by=updated_at", C);

        assertThat(body.get("perPage").asInt()).isEqualTo(2);
        assertThat(ids(body.get("items"))).as("first_name ASC, not updated_at DESC").containsExactly(30L, 31L);
    }

    @Test
    void snakeCaseAliasesAloneAreHonoured() {
        JsonNode body = getJson("/api/v1/contacts?per_page=3&sort_by=updated_at%20DESC", C);

        assertThat(body.get("perPage").asInt()).isEqualTo(3);
        assertThat(ids(body.get("items"))).containsExactly(35L, 34L, 33L);
    }

    @Test
    void emptyPerPageFallsBackToTheDefaultOf20() {
        JsonNode body = getJson("/api/v1/contacts?perPage=", C);

        assertThat(body.get("perPage").asInt()).isEqualTo(20);
        assertThat(body.get("page").asInt()).isEqualTo(1);
    }

    @Test
    void nonNumericPerPageIs400ProblemJson() {
        ResponseEntity<String> response = get("/api/v1/contacts?perPage=abc", C);

        assertProblem(response, HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertThat(response.getBody()).contains("perPage");
    }

    @Test
    void blankQueryAndBlankTermMeanNoFiltering() {
        assertThat(ids(getJson("/api/v1/contacts?query=&sortBy=first_name", Z).get("items")))
                .containsExactly(30L, 31L, 32L, 33L, 34L, 35L);
        assertThat(ids(getJson("/api/v1/contacts?query=%20%20&sortBy=first_name", Z).get("items")))
                .containsExactly(30L, 31L, 32L, 33L, 34L, 35L);
        assertThat(autocompleteIds(Z, "%20%20")).containsExactly(30L, 31L, 32L, 33L, 34L, 35L);
    }

    @Test
    void queryMatchingNothingStillReturnsTheEnvelopeWithZeroTotal() {
        JsonNode body = getJson("/api/v1/contacts?query=nobody-here&page=3&perPage=5", C);

        assertThat(fieldNames(body)).containsExactly("items", "page", "perPage", "totalCount");
        assertThat(body.get("items").isArray()).isTrue();
        assertThat(body.get("items")).isEmpty();
        assertThat(body.get("page").asInt()).isEqualTo(3);
        assertThat(body.get("perPage").asInt()).isEqualTo(5);
        assertThat(body.get("totalCount").asLong()).isZero();
    }

    @Test
    void autocompleteIgnoresTheRailsRelatedParameter() {
        assertThat(autocompleteIds(C, "Mia&related=contacts/34")).containsExactly(34L);
    }

    @Test
    void nonNumericOrNegativeIdsAre404ProblemJsonNot500() {
        assertProblem(get("/api/v1/contacts/abc", C), HttpStatus.NOT_FOUND, "/api/v1/contacts/abc");
        assertProblem(get("/api/v1/contacts/-1", C), HttpStatus.NOT_FOUND, "/api/v1/contacts/-1");
    }

    @Test
    void idBeyondLongRangeIs400ProblemJson() {
        assertProblem(get("/api/v1/contacts/99999999999999999999", C), HttpStatus.BAD_REQUEST,
                "/api/v1/contacts/99999999999999999999");
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static ContactRow row(long id, String firstName, String lastName, Long userId, Long assignedTo,
            String access) {
        return new ContactRow(id, userId, null, assignedTo, null, firstName, lastName, access, null, null, null,
                null, null, null, null, null, null, null, null, null, null, false, null, null, null, null, null,
                null, null, null, null, T0.minusHours(id), T0.plusHours(id));
    }

    private List<Long> listIds(long userId, String queryString) {
        return ids(getJson("/api/v1/contacts" + queryString, userId).get("items"));
    }

    private List<Long> autocompleteIds(long userId, String term) {
        return ids(getJson("/api/v1/contacts/autocomplete?term=" + term, userId).get("results"));
    }

    private ResponseEntity<String> get(String path, Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(TrustedHeaderAuthenticationFilter.HEADER, String.valueOf(userId));
        }
        return rest.exchange(URI.create(url(path)), HttpMethod.GET, new HttpEntity<>(headers), String.class);
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

    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
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
