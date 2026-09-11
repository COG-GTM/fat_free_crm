package com.fatfreecrm.api;

import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PRIVATE;
import static com.fatfreecrm.security.AccessControlSpecifications.ACCESS_PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.security.TrustedHeaderAuthenticationFilter;
import com.fatfreecrm.support.TestDataSeeder.ContactRow;
import java.net.URI;
import java.time.LocalDate;
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
 * End-to-end tests for {@code /api/v1/contacts}.
 *
 * <pre>
 * users:    A (1), B (2, member of group G=100), admin C (3), Z (4)
 * contacts: 10 Public   owned by A            Alice Anderson  alice@corp.example
 *           11 Private  owned by A            Bob Brown       alt: bobby@home.example
 *           12 Private  owned by Z, assigned B Carol Clark     phone +1-555-0102
 *           13 Private  owned by Z, perm -> B  Dave Davis
 *           14 Private  owned by Z, perm -> G  Eve Evans
 *           15 Public   owned by Z, soft-deleted Frank Foster
 *           16 Private  owned by Z             Grace Green
 * created_at descends with the id (10 newest ... 16 oldest); updated_at ascends with the id.
 * </pre>
 */
class ContactControllerTest extends AbstractIntegrationTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long C = 3;
    private static final long Z = 4;
    private static final long G = 100;

    private static final LocalDateTime T0 = LocalDateTime.of(2024, 5, 1, 9, 30, 0);

    private static final List<String> OPENAPI_CONTACT_FIELDS = List.of(
            "id", "user_id", "lead_id", "assigned_to", "reports_to", "first_name", "last_name", "access", "title",
            "department", "source", "email", "alt_email", "phone", "mobile", "fax", "blog", "linkedin", "facebook",
            "twitter", "zoom", "teams", "signal", "instagram", "mastodon", "bluesky", "born_on", "do_not_call",
            "background_info", "subscribed_users", "deleted_at", "created_at", "updated_at");

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

        seeder.insertContact(row(10, "Alice", "Anderson", A, null, ACCESS_PUBLIC, "alice@corp.example", null, null, null));
        seeder.insertContact(row(11, "Bob", "Brown", A, null, ACCESS_PRIVATE, null, "bobby@home.example", null, null));
        seeder.insertContact(row(12, "Carol", "Clark", Z, B, ACCESS_PRIVATE, null, null, "+1-555-0102", null));
        seeder.insertContact(row(13, "Dave", "Davis", Z, null, ACCESS_PRIVATE, null, null, null, null));
        seeder.insertPermission("Contact", 13, B, null);
        seeder.insertContact(row(14, "Eve", "Evans", Z, null, ACCESS_PRIVATE, null, null, null, null));
        seeder.insertPermission("Contact", 14, null, G);
        seeder.insertContact(row(15, "Frank", "Foster", Z, null, ACCESS_PUBLIC, null, null, null, T0.plusDays(30)));
        seeder.insertContact(row(16, "Grace", "Green", Z, null, ACCESS_PRIVATE, null, null, null, null));
    }

    // ---- list: access control ---------------------------------------------------------------

    @Test
    void ownerSeesPublicAndOwnContacts() {
        JsonNode body = getJson("/api/v1/contacts?sortBy=first_name", A);

        assertThat(ids(body.get("items"))).containsExactly(10L, 11L);
        assertThat(body.get("totalCount").asLong()).isEqualTo(2);
        assertThat(body.get("page").asInt()).isEqualTo(1);
        assertThat(body.get("perPage").asInt()).isEqualTo(20);
    }

    @Test
    void assigneeAndGranteeSeesAssignedAndSharedContacts() {
        JsonNode body = getJson("/api/v1/contacts?sortBy=first_name", B);

        assertThat(ids(body.get("items"))).containsExactly(10L, 12L, 13L, 14L);
        assertThat(body.get("totalCount").asLong()).isEqualTo(4);
    }

    @Test
    void adminSeesEverythingExceptSoftDeleted() {
        JsonNode body = getJson("/api/v1/contacts?sortBy=first_name", C);

        assertThat(ids(body.get("items"))).containsExactly(10L, 11L, 12L, 13L, 14L, 16L);
        assertThat(body.get("totalCount").asLong()).isEqualTo(6);
    }

    // ---- list: pagination -------------------------------------------------------------------

    @Test
    void paginationReturnsTheRequestedSliceWithTotalCount() {
        JsonNode body = getJson("/api/v1/contacts?page=2&perPage=2&sortBy=first_name", C);

        assertThat(ids(body.get("items"))).containsExactly(12L, 13L);
        assertThat(body.get("page").asInt()).isEqualTo(2);
        assertThat(body.get("perPage").asInt()).isEqualTo(2);
        assertThat(body.get("totalCount").asLong()).isEqualTo(6);

        JsonNode last = getJson("/api/v1/contacts?page=3&per_page=2&sort_by=first_name", C);
        assertThat(ids(last.get("items"))).containsExactly(14L, 16L);

        JsonNode beyond = getJson("/api/v1/contacts?page=4&perPage=2", C);
        assertThat(beyond.get("items")).isEmpty();
        assertThat(beyond.get("totalCount").asLong()).isEqualTo(6);
    }

    @Test
    void perPageAbove200IsClampedAndOtherBadParamsAre400() {
        assertThat(getJson("/api/v1/contacts?perPage=500", C).get("perPage").asInt()).isEqualTo(200);
        assertThat(getJson("/api/v1/contacts?per_page=500", C).get("perPage").asInt()).isEqualTo(200);
        assertProblem(get("/api/v1/contacts?perPage=0", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?page=0", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?page=abc", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");

        assertThat(getJson("/api/v1/contacts?perPage=200", C).get("perPage").asInt()).isEqualTo(200);
    }

    // ---- list: text search ------------------------------------------------------------------

    @Test
    void queryMatchesFirstOrLastNameCaseInsensitively() {
        assertThat(ids(getJson("/api/v1/contacts?query=car", C).get("items"))).containsExactly(12L);
        assertThat(ids(getJson("/api/v1/contacts?query=GREEN", C).get("items"))).containsExactly(16L);
        // "a" also hits Bob Brown (11) through alt_email "bobby@home.example"
        assertThat(ids(getJson("/api/v1/contacts?query=a&sortBy=first_name", C).get("items")))
                .containsExactly(10L, 11L, 12L, 13L, 14L, 16L);
        assertThat(getJson("/api/v1/contacts?query=nobody", C).get("items")).isEmpty();
    }

    @Test
    void queryWithSpaceMatchesFirstLastAndLastFirst() {
        assertThat(ids(getJson("/api/v1/contacts?query=Carol%20Clark", C).get("items"))).containsExactly(12L);
        assertThat(ids(getJson("/api/v1/contacts?query=Clark%20Carol", C).get("items"))).containsExactly(12L);
        assertThat(ids(getJson("/api/v1/contacts?query=car%20cla", C).get("items"))).containsExactly(12L);
        assertThat(getJson("/api/v1/contacts?query=Carol%20Green", C).get("items")).isEmpty();
    }

    @Test
    void queryMatchesEmailAltEmailPhoneAndMobile() {
        assertThat(ids(getJson("/api/v1/contacts?query=alice@corp", C).get("items"))).containsExactly(10L);
        assertThat(ids(getJson("/api/v1/contacts?query=home.example", C).get("items"))).containsExactly(11L);
        assertThat(ids(getJson("/api/v1/contacts?query=555-0102", C).get("items"))).containsExactly(12L);
    }

    @Test
    void queryIsStillScopedByVisibility() {
        assertThat(getJson("/api/v1/contacts?query=Grace", A).get("items")).isEmpty();
        assertThat(getJson("/api/v1/contacts?query=Grace", A).get("totalCount").asLong()).isZero();
    }

    // ---- list: sorting ----------------------------------------------------------------------

    @Test
    void sortByOrdersResults() {
        assertThat(ids(getJson("/api/v1/contacts", C).get("items")))
                .as("default created_at DESC").containsExactly(10L, 11L, 12L, 13L, 14L, 16L);
        assertThat(ids(getJson("/api/v1/contacts?sortBy=created_at%20DESC", C).get("items")))
                .containsExactly(10L, 11L, 12L, 13L, 14L, 16L);
        assertThat(ids(getJson("/api/v1/contacts?sortBy=updated_at%20DESC", C).get("items")))
                .containsExactly(16L, 14L, 13L, 12L, 11L, 10L);
        assertThat(ids(getJson("/api/v1/contacts?sortBy=updated_at", C).get("items")))
                .containsExactly(16L, 14L, 13L, 12L, 11L, 10L);
        assertThat(ids(getJson("/api/v1/contacts?sortBy=first_name%20ASC", C).get("items")))
                .containsExactly(10L, 11L, 12L, 13L, 14L, 16L);
        assertThat(ids(getJson("/api/v1/contacts?sort_by=last_name", C).get("items")))
                .containsExactly(10L, 11L, 12L, 13L, 14L, 16L);
    }

    @Test
    void unknownSortByIsRejectedWith400ProblemJson() {
        ResponseEntity<String> response = get("/api/v1/contacts?sortBy=email%20DESC", C);

        assertProblem(response, HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertThat(response.getBody()).contains("sortBy");

        assertProblem(get("/api/v1/contacts?sort_by=first_name%20DESC", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
    }

    // ---- show -------------------------------------------------------------------------------

    @Test
    void showReturnsTheFullSnakeCaseShape() {
        seeder.insertContact(new ContactRow(20, Z, 5L, B, 10L, "Hank", "Hill", "Shared", "CTO", "Engineering",
                "Referral", "hank@corp.example", "hank@home.example", "+1-555-0200", "+1-555-0201", "+1-555-0202",
                "https://blog.example", "hank-hill", "hank.hill", "@hank", LocalDate.of(1970, 2, 3), true,
                "Met at a conference.", "---\n- 1\n- 2\n", "hank-zoom", "hank-teams", "hank-signal", "hank_ig",
                "@hank@mastodon.example", "hank.bsky.example", null, T0, T0.plusHours(1)));

        ResponseEntity<String> response = get("/api/v1/contacts/20", C);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();

        JsonNode body = json(response);
        assertThat(fieldNames(body)).containsExactlyElementsOf(OPENAPI_CONTACT_FIELDS);
        assertThat(body.get("id").asLong()).isEqualTo(20);
        assertThat(body.get("user_id").asLong()).isEqualTo(Z);
        assertThat(body.get("lead_id").asLong()).isEqualTo(5);
        assertThat(body.get("assigned_to").asLong()).isEqualTo(B);
        assertThat(body.get("reports_to").asLong()).isEqualTo(10);
        assertThat(body.get("first_name").asText()).isEqualTo("Hank");
        assertThat(body.get("last_name").asText()).isEqualTo("Hill");
        assertThat(body.get("access").asText()).isEqualTo("Shared");
        assertThat(body.get("title").asText()).isEqualTo("CTO");
        assertThat(body.get("department").asText()).isEqualTo("Engineering");
        assertThat(body.get("source").asText()).isEqualTo("Referral");
        assertThat(body.get("email").asText()).isEqualTo("hank@corp.example");
        assertThat(body.get("alt_email").asText()).isEqualTo("hank@home.example");
        assertThat(body.get("phone").asText()).isEqualTo("+1-555-0200");
        assertThat(body.get("mobile").asText()).isEqualTo("+1-555-0201");
        assertThat(body.get("fax").asText()).isEqualTo("+1-555-0202");
        assertThat(body.get("blog").asText()).isEqualTo("https://blog.example");
        assertThat(body.get("linkedin").asText()).isEqualTo("hank-hill");
        assertThat(body.get("facebook").asText()).isEqualTo("hank.hill");
        assertThat(body.get("twitter").asText()).isEqualTo("@hank");
        assertThat(body.get("zoom").asText()).isEqualTo("hank-zoom");
        assertThat(body.get("teams").asText()).isEqualTo("hank-teams");
        assertThat(body.get("signal").asText()).isEqualTo("hank-signal");
        assertThat(body.get("instagram").asText()).isEqualTo("hank_ig");
        assertThat(body.get("mastodon").asText()).isEqualTo("@hank@mastodon.example");
        assertThat(body.get("bluesky").asText()).isEqualTo("hank.bsky.example");
        assertThat(body.get("born_on").asText()).isEqualTo("1970-02-03");
        assertThat(body.get("do_not_call").asBoolean()).isTrue();
        assertThat(body.get("background_info").asText()).isEqualTo("Met at a conference.");
        assertThat(body.get("subscribed_users").isArray()).isTrue();
        assertThat(ids(body.get("subscribed_users"))).containsExactly(1L, 2L);
        assertThat(body.get("deleted_at").isNull()).isTrue();
        assertThat(body.get("created_at").asText()).isEqualTo("2024-05-01T09:30:00Z");
        assertThat(body.get("updated_at").asText()).isEqualTo("2024-05-01T10:30:00Z");
    }

    @Test
    void showEmitsNullsAndEmptySubscribedUsersForSparseRows() {
        JsonNode body = getJson("/api/v1/contacts/13", B);

        assertThat(fieldNames(body)).containsExactlyElementsOf(OPENAPI_CONTACT_FIELDS);
        assertThat(body.get("access").asText()).isEqualTo("Private");
        assertThat(body.get("assigned_to").isNull()).isTrue();
        assertThat(body.get("email").isNull()).isTrue();
        assertThat(body.get("born_on").isNull()).isTrue();
        assertThat(body.get("do_not_call").asBoolean()).isFalse();
        assertThat(body.get("subscribed_users").isArray()).isTrue();
        assertThat(body.get("subscribed_users")).isEmpty();
    }

    @Test
    void showIs404ProblemJsonForMissingSoftDeletedAndInvisibleContacts() {
        assertProblem(get("/api/v1/contacts/999", C), HttpStatus.NOT_FOUND, "/api/v1/contacts/999");
        assertProblem(get("/api/v1/contacts/15", C), HttpStatus.NOT_FOUND, "/api/v1/contacts/15");
        assertProblem(get("/api/v1/contacts/16", A), HttpStatus.NOT_FOUND, "/api/v1/contacts/16");
        assertProblem(get("/api/v1/contacts/11", B), HttpStatus.NOT_FOUND, "/api/v1/contacts/11");

        assertThat(get("/api/v1/contacts/16", C).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/contacts/14", B).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- autocomplete -----------------------------------------------------------------------

    @Test
    void autocompleteReturnsIdAndFullNameOfVisibleMatches() {
        JsonNode body = getJson("/api/v1/contacts/autocomplete?term=a", B);

        assertThat(fieldNames(body)).containsExactly("results");
        JsonNode results = body.get("results");
        assertThat(ids(results)).containsExactly(10L, 12L, 13L, 14L);
        assertThat(results.get(0).get("text").asText()).isEqualTo("Alice Anderson");
        assertThat(fieldNames(results.get(0))).containsExactly("id", "text");

        assertThat(ids(getJson("/api/v1/contacts/autocomplete?term=Clark%20Carol", B).get("results")))
                .containsExactly(12L);
        assertThat(getJson("/api/v1/contacts/autocomplete?term=Grace", B).get("results")).isEmpty();
        assertThat(ids(getJson("/api/v1/contacts/autocomplete", A).get("results"))).containsExactly(10L, 11L);
    }

    @Test
    void autocompleteIsLimitedToTenOrderedByName() {
        for (int i = 0; i < 15; i++) {
            seeder.insertContact(row(100 + i, "Zed" + (char) ('a' + i), "Auto", Z, null, ACCESS_PUBLIC,
                    null, null, null, null));
        }

        JsonNode results = getJson("/api/v1/contacts/autocomplete?term=auto", C).get("results");
        assertThat(results).hasSize(10);
        List<String> texts = new ArrayList<>();
        results.forEach(r -> texts.add(r.get("text").asText()));
        assertThat(texts).isSorted();
        assertThat(texts.get(0)).isEqualTo("Zeda Auto");
    }

    // ---- authentication ---------------------------------------------------------------------

    @Test
    void missingUserHeaderIs401ProblemJsonOnEveryEndpoint() {
        assertProblem(get("/api/v1/contacts", null), HttpStatus.UNAUTHORIZED, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts/10", null), HttpStatus.UNAUTHORIZED, "/api/v1/contacts/10");
        assertProblem(get("/api/v1/contacts/autocomplete?term=a", null), HttpStatus.UNAUTHORIZED,
                "/api/v1/contacts/autocomplete");
    }

    // ---- helpers ----------------------------------------------------------------------------

    /**
     * {@code created_at = T0 - id hours} (smaller id is newer), {@code updated_at = T0 + id hours}
     * (larger id is newer) so the two sort orders are distinguishable.
     */
    private static ContactRow row(long id, String firstName, String lastName, Long userId, Long assignedTo,
            String access, String email, String altEmail, String phone, LocalDateTime deletedAt) {
        return new ContactRow(id, userId, null, assignedTo, null, firstName, lastName, access, null, null, null,
                email, altEmail, phone, null, null, null, null, null, null, null, false, null, null, null, null,
                null, null, null, null, deletedAt, T0.minusHours(id), T0.plusHours(id));
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
