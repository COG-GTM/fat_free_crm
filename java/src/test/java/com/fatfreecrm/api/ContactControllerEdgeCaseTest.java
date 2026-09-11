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
 * Edge cases, Rails-parity details and access-control isolation for {@code /api/v1/contacts}
 * that {@link ContactControllerTest} does not pin down.
 *
 * <pre>
 * users:    A (1), B (2, member of group G=100), admin C (3), Z (4)
 * contacts: 10 Public   owned by A            Alice Anderson   email alice@corp.example
 *           11 Private  owned by A            Bob Brown
 *           12 Private  owned by Z, assigned B Carol Clark
 *           13 Shared   owned by Z, perm -> A  Dave Davis       (A only; B and Z's other users must not see it)
 *           14 Shared   owned by Z, perm -> G  Eve Evans
 *           16 Private  owned by Z             Grace Green
 *           17 Public   owned by Z             Mary Ann Smith   (three-word full name)
 *           18 Public   owned by Z             Jean Luc Picard  mobile 555_0199
 * created_at descends with the id; updated_at ascends with the id.
 * </pre>
 */
class ContactControllerEdgeCaseTest extends AbstractIntegrationTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long C = 3;
    private static final long Z = 4;
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
        seeder.insertGroup(G, "sales");
        seeder.addUserToGroup(B, G);

        seeder.insertContact(row(10, "Alice", "Anderson", A, null, ACCESS_PUBLIC, "alice@corp.example", null));
        seeder.insertContact(row(11, "Bob", "Brown", A, null, ACCESS_PRIVATE, null, null));
        seeder.insertContact(row(12, "Carol", "Clark", Z, B, ACCESS_PRIVATE, null, null));
        seeder.insertContact(row(13, "Dave", "Davis", Z, null, ACCESS_SHARED, null, null));
        seeder.insertPermission("Contact", 13, A, null);
        seeder.insertContact(row(14, "Eve", "Evans", Z, null, ACCESS_SHARED, null, null));
        seeder.insertPermission("Contact", 14, null, G);
        seeder.insertContact(row(16, "Grace", "Green", Z, null, ACCESS_PRIVATE, null, null));
        seeder.insertContact(row(17, "Mary Ann", "Smith", Z, null, ACCESS_PUBLIC, null, null));
        seeder.insertContact(row(18, "Jean Luc", "Picard", Z, null, ACCESS_PUBLIC, null, "555_0199"));
    }

    // ---- authorization: Shared records are only visible to their grantees ---------------------

    @Test
    void sharedContactIsOnlyVisibleToItsGranteeNotToOtherUsers() {
        assertThat(ids(getJson("/api/v1/contacts?sortBy=first_name", A).get("items")))
                .as("A is owner of 10/11 and grantee of 13; 17/18 are Public")
                .containsExactly(10L, 11L, 13L, 18L, 17L);

        assertThat(ids(getJson("/api/v1/contacts?sortBy=first_name", B).get("items")))
                .as("B is assignee of 12 and group grantee of 14, but not a grantee of 13")
                .containsExactly(10L, 12L, 14L, 18L, 17L);

        assertThat(get("/api/v1/contacts/13", A).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertProblem(get("/api/v1/contacts/13", B), HttpStatus.NOT_FOUND, "/api/v1/contacts/13");
        assertProblem(get("/api/v1/contacts/14", A), HttpStatus.NOT_FOUND, "/api/v1/contacts/14");
    }

    @Test
    void ownerSeesOwnSharedAndPrivateContactsWithoutPermissionRows() {
        assertThat(ids(getJson("/api/v1/contacts?sortBy=first_name", Z).get("items")))
                .containsExactly(10L, 12L, 13L, 14L, 16L, 18L, 17L);

        assertThat(get("/api/v1/contacts/13", Z).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/contacts/16", Z).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertProblem(get("/api/v1/contacts/11", Z), HttpStatus.NOT_FOUND, "/api/v1/contacts/11");
    }

    @Test
    void autocompleteAndSearchNeverLeakInvisibleContacts() {
        assertThat(getJson("/api/v1/contacts/autocomplete?term=Dave", B).get("results")).isEmpty();
        assertThat(getJson("/api/v1/contacts/autocomplete?term=Bob", Z).get("results")).isEmpty();
        assertThat(getJson("/api/v1/contacts?query=Davis", B).get("totalCount").asLong()).isZero();

        assertThat(ids(getJson("/api/v1/contacts/autocomplete?term=Dave", A).get("results"))).containsExactly(13L);
    }

    @Test
    void totalCountReflectsOnlyVisibleRowsAcrossPages() {
        JsonNode first = getJson("/api/v1/contacts?perPage=2&sortBy=first_name", B);
        assertThat(ids(first.get("items"))).containsExactly(10L, 12L);
        assertThat(first.get("totalCount").asLong()).isEqualTo(5);

        JsonNode last = getJson("/api/v1/contacts?page=3&perPage=2&sortBy=first_name", B);
        assertThat(ids(last.get("items"))).containsExactly(17L);
        assertThat(last.get("totalCount").asLong()).isEqualTo(5);
    }

    // ---- parameter aliases -------------------------------------------------------------------

    @Test
    void camelCaseParametersWinOverSnakeCaseAliasesWhenBothAreSent() {
        JsonNode body = getJson("/api/v1/contacts?perPage=3&per_page=1&sortBy=first_name&sort_by=updated_at", C);

        assertThat(body.get("perPage").asInt()).isEqualTo(3);
        assertThat(ids(body.get("items"))).as("first_name ASC, not updated_at DESC").containsExactly(10L, 11L, 12L);
    }

    @Test
    void snakeCaseAliasesAreValidatedLikeTheCamelCaseParameters() {
        assertProblem(get("/api/v1/contacts?per_page=0", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?per_page=abc", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?sort_by=id", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?page=-1", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?perPage=-5", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
    }

    @Test
    void validationProblemDetailNamesTheOffendingParameter() {
        ResponseEntity<String> response = get("/api/v1/contacts?per_page=999", C);

        assertProblem(response, HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertThat(json(response).get("detail").asText()).contains("200");

        ResponseEntity<String> typeMismatch = get("/api/v1/contacts?page=abc", C);
        assertProblem(typeMismatch, HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertThat(json(typeMismatch).get("detail").asText()).contains("'page'").contains("'abc'");
    }

    // ---- text search: Rails text_search parity ------------------------------------------------

    @Test
    void blankOrWhitespaceQueryDoesNotFilter() {
        long all = getJson("/api/v1/contacts", C).get("totalCount").asLong();

        assertThat(getJson("/api/v1/contacts?query=", C).get("totalCount").asLong()).isEqualTo(all);
        assertThat(getJson("/api/v1/contacts?query=%20%20", C).get("totalCount").asLong()).isEqualTo(all);
        assertThat(getJson("/api/v1/contacts/autocomplete?term=%20", A).get("results")).isNotEmpty();
    }

    @Test
    void surroundingWhitespaceInQueryIsIgnored() {
        assertThat(ids(getJson("/api/v1/contacts?query=%20%20Grace%20%20", C).get("items"))).containsExactly(16L);
        assertThat(ids(getJson("/api/v1/contacts?query=%20Clark%20%20Carol%20", C).get("items")))
                .containsExactly(12L);
    }

    @Test
    void threeWordQueryTriesEverySplitInBothOrders() {
        assertThat(ids(getJson("/api/v1/contacts?query=Mary%20Ann%20Smith", C).get("items"))).containsExactly(17L);
        assertThat(ids(getJson("/api/v1/contacts?query=Smith%20Mary%20Ann", C).get("items"))).containsExactly(17L);
        assertThat(ids(getJson("/api/v1/contacts?query=Picard%20Jean%20Luc", C).get("items"))).containsExactly(18L);
        assertThat(ids(getJson("/api/v1/contacts?query=jean%20luc%20pic", C).get("items"))).containsExactly(18L);

        assertThat(getJson("/api/v1/contacts?query=Ann%20Mary%20Smith", C).get("items"))
                .as("word order inside the first-name part is not permuted (Rails name_permutations)")
                .isEmpty();
    }

    @Test
    void multiWordQueryDoesNotMatchWhenBothWordsHitTheSameColumn() {
        assertThat(getJson("/api/v1/contacts?query=Alice%20Bob", C).get("items")).isEmpty();
        assertThat(getJson("/api/v1/contacts?query=Anderson%20Brown", C).get("items")).isEmpty();
    }

    @Test
    void multiWordQueryStillMatchesEmailAndPhoneOnTheWholeString() {
        seeder.insertContact(row(30, "Odd", "Email", Z, null, ACCESS_PUBLIC, "two words@corp.example", null));

        assertThat(ids(getJson("/api/v1/contacts?query=two%20words", C).get("items"))).containsExactly(30L);
    }

    @Test
    void sqlLikeWildcardsInQueryAreNotEscapedLikeRails() {
        assertThat(ids(getJson("/api/v1/contacts?query=Al_ce", C).get("items"))).containsExactly(10L);
        assertThat(ids(getJson("/api/v1/contacts?query=Gr%25n", C).get("items"))).containsExactly(16L);
        assertThat(ids(getJson("/api/v1/contacts?query=555_0199", C).get("items"))).containsExactly(18L);
    }

    @Test
    void querySpecialCharactersAreSearchedLiterally() {
        seeder.insertContact(row(31, "O'Brien", "D'Angelo", Z, null, ACCESS_PUBLIC, null, null));

        assertThat(ids(getJson("/api/v1/contacts?query=O'Brien", C).get("items"))).containsExactly(31L);
        assertThat(ids(getJson("/api/v1/contacts?query=D%27Angelo%20O%27Brien", C).get("items"))).containsExactly(31L);
        assertThat(getJson("/api/v1/contacts?query=%22%3B%20DROP%20TABLE%20contacts%3B%20--", C).get("items")).isEmpty();
        assertThat(getJson("/api/v1/contacts", C).get("totalCount").asLong()).isEqualTo(9);
    }

    // ---- sorting -----------------------------------------------------------------------------

    @Test
    void sortTiesAreBrokenByIdAscending() {
        seeder.insertContact(row(40, "Grace", "Zulu", Z, null, ACCESS_PUBLIC, null, null));
        seeder.insertContact(row(41, "Grace", "Alpha", Z, null, ACCESS_PUBLIC, null, null));

        assertThat(ids(getJson("/api/v1/contacts?query=Grace&sortBy=first_name", C).get("items")))
                .containsExactly(16L, 40L, 41L);

        JsonNode page1 = getJson("/api/v1/contacts?query=Grace&sortBy=first_name&perPage=2", C);
        JsonNode page2 = getJson("/api/v1/contacts?query=Grace&sortBy=first_name&perPage=2&page=2", C);
        assertThat(ids(page1.get("items"))).containsExactly(16L, 40L);
        assertThat(ids(page2.get("items"))).containsExactly(41L);
    }

    @Test
    void emptyPageAndPerPageParametersFallBackToDefaults() {
        JsonNode body = getJson("/api/v1/contacts?page=&perPage=&per_page=", C);

        assertThat(body.get("page").asInt()).isEqualTo(1);
        assertThat(body.get("perPage").asInt()).isEqualTo(20);
        assertThat(body.get("totalCount").asInt()).isEqualTo(8);
    }

    @Test
    void sortByIsCaseSensitiveLikeTheRailsWhitelist() {
        assertProblem(get("/api/v1/contacts?sortBy=first_name%20asc", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?sortBy=FIRST_NAME", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?sortBy=created_at%20ASC", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
    }

    // ---- show: path variable edge cases -------------------------------------------------------

    @Test
    void nonNumericOrNegativeIdIs404ProblemJson() {
        assertProblem(get("/api/v1/contacts/abc", C), HttpStatus.NOT_FOUND, "/api/v1/contacts/abc");
        assertProblem(get("/api/v1/contacts/-1", C), HttpStatus.NOT_FOUND, "/api/v1/contacts/-1");
        assertProblem(get("/api/v1/contacts/10.5", C), HttpStatus.NOT_FOUND, "/api/v1/contacts/10.5");
    }

    @Test
    void idBeyondLongRangeIs400ProblemJson() {
        String huge = "99999999999999999999";
        assertProblem(get("/api/v1/contacts/" + huge, C), HttpStatus.BAD_REQUEST, "/api/v1/contacts/" + huge);
    }

    @Test
    void zeroIdIs404LikeAnyUnknownContact() {
        assertProblem(get("/api/v1/contacts/0", C), HttpStatus.NOT_FOUND, "/api/v1/contacts/0");
    }

    @Test
    void notFoundProblemDoesNotRevealWhetherTheContactExists() {
        ResponseEntity<String> invisible = get("/api/v1/contacts/16", A);
        ResponseEntity<String> missing = get("/api/v1/contacts/999", A);

        assertProblem(invisible, HttpStatus.NOT_FOUND, "/api/v1/contacts/16");
        assertProblem(missing, HttpStatus.NOT_FOUND, "/api/v1/contacts/999");
        assertThat(json(invisible).get("detail").asText().replace("16", "<id>"))
                .isEqualTo(json(missing).get("detail").asText().replace("999", "<id>"));
    }

    // ---- autocomplete: Rails full_name parity -------------------------------------------------

    @Test
    void autocompleteTextIsFirstNameSpaceLastNameEvenWhenOneIsEmpty() {
        seeder.insertContact(row(50, "", "Solo", Z, null, ACCESS_PUBLIC, null, null));
        seeder.insertContact(row(51, "Cher", "", Z, null, ACCESS_PUBLIC, null, null));

        JsonNode results = getJson("/api/v1/contacts/autocomplete?term=solo", C).get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("id").asLong()).isEqualTo(50);
        assertThat(results.get(0).get("text").asText()).isEqualTo(" Solo");

        results = getJson("/api/v1/contacts/autocomplete?term=cher", C).get("results");
        assertThat(results.get(0).get("text").asText()).isEqualTo("Cher ");
    }

    @Test
    void autocompleteIgnoresListParameters() {
        JsonNode body = getJson("/api/v1/contacts/autocomplete?term=a&perPage=1&page=2&sortBy=bogus", C);

        assertThat(fieldNames(body)).containsExactly("results");
        assertThat(body.get("results").size()).isGreaterThan(1);
    }

    @Test
    void autocompleteRelatedParameterIsIgnoredNotRejected() {
        JsonNode results = getJson("/api/v1/contacts/autocomplete?term=Alice&related=10", C).get("results");

        assertThat(ids(results)).containsExactly(10L);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static ContactRow row(long id, String firstName, String lastName, Long userId, Long assignedTo,
            String access, String email, String mobile) {
        return new ContactRow(id, userId, null, assignedTo, null, firstName, lastName, access, null, null, null,
                email, null, null, mobile, null, null, null, null, null, null, false, null, null, null, null,
                null, null, null, null, null, T0.minusHours(id), T0.plusHours(id));
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
        array.forEach(n -> ids.add(n.isObject() ? n.get("id").asLong() : n.asLong()));
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
