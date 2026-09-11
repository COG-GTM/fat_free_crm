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
class ContactControllerIT extends AbstractIntegrationTest {

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

    // ---- list: Rails text_search parity ----------------------------------------------------

    @Test
    void queryPassesSqlWildcardsThroughUnescapedLikeArelMatches() {
        // Contact.text_search uses Arel#matches without escaping (unlike Account's Ransack path)
        assertThat(ids(getJson("/api/v1/contacts?query=_lice", C).get("items"))).containsExactly(10L);
        assertThat(ids(getJson("/api/v1/contacts?query=%25&sortBy=first_name", C).get("items")))
                .containsExactly(10L, 11L, 12L, 13L, 14L, 16L);
        assertThat(ids(getJson("/api/v1/contacts?query=d_v", C).get("items"))).containsExactly(13L);
    }

    @Test
    void queryWithSingleQuoteAndSpecialCharactersDoesNotBreak() {
        // spec/models/entities/contact_spec.rb "should not break with a single quote / on special characters"
        seeder.insertContact(row(30, "Shamus", "O'Connell", Z, null, ACCESS_PUBLIC, null, null, null, null));

        assertThat(ids(getJson("/api/v1/contacts?query=O'Connell", C).get("items"))).containsExactly(30L);
        assertThat(ids(getJson("/api/v1/contacts?query=Shamus%20O'Connell", C).get("items"))).containsExactly(30L);
        assertThat(getJson("/api/v1/contacts?query=%40%24%25%23%5E%40%21", C).get("items")).isEmpty();
    }

    @Test
    void queryWithThreeWordsTriesEveryFirstLastSplitInBothOrders() {
        seeder.insertContact(row(31, "Mary Ann", "Smith", Z, null, ACCESS_PUBLIC, null, null, null, null));

        assertThat(ids(getJson("/api/v1/contacts?query=Mary%20Ann%20Smith", C).get("items"))).containsExactly(31L);
        assertThat(ids(getJson("/api/v1/contacts?query=Smith%20Mary%20Ann", C).get("items"))).containsExactly(31L);
        assertThat(ids(getJson("/api/v1/contacts?query=mary%20smith", C).get("items"))).containsExactly(31L);
        // no contiguous split gives first=%Ann Mary% or last=%Ann Mary%
        assertThat(getJson("/api/v1/contacts?query=Ann%20Mary%20Smith", C).get("items")).isEmpty();
    }

    @Test
    void queryWithSpacesRequiresBothHalvesToMatchTheSameRow() {
        // Carol (12) and Clark (12) exist, but "Carol Anderson" pairs Carol with Alice's surname
        assertThat(getJson("/api/v1/contacts?query=Carol%20Anderson", C).get("items")).isEmpty();
        assertThat(getJson("/api/v1/contacts?query=Anderson%20Carol", C).get("items")).isEmpty();
        // a phone number containing a space still hits the whole-query email/phone alternatives
        seeder.insertContact(row(32, "Ivy", "Irwin", Z, null, ACCESS_PUBLIC, null, null, "+1 123 456 789", null));
        assertThat(ids(getJson("/api/v1/contacts?query=123%20456", C).get("items"))).containsExactly(32L);
    }

    @Test
    void blankAndRepeatedWhitespaceQueriesBehaveLikeRails() {
        assertThat(ids(getJson("/api/v1/contacts?query=&sortBy=first_name", C).get("items")))
                .containsExactly(10L, 11L, 12L, 13L, 14L, 16L);
        assertThat(ids(getJson("/api/v1/contacts?query=%20%20&sortBy=first_name", C).get("items")))
                .containsExactly(10L, 11L, 12L, 13L, 14L, 16L);
        // Ruby String#split(" ") collapses runs of whitespace
        assertThat(ids(getJson("/api/v1/contacts?query=Carol%20%20%20Clark", C).get("items"))).containsExactly(12L);
    }

    @Test
    void queryMatchesMobileColumn() {
        seeder.insertContact(new ContactRow(33, Z, null, null, null, "Jack", "Jones", ACCESS_PUBLIC, null, null, null,
                null, null, null, "+44 7700 900123", null, null, null, null, null, null, false, null, null, null, null,
                null, null, null, null, null, T0, T0));

        assertThat(ids(getJson("/api/v1/contacts?query=7700%20900", C).get("items"))).containsExactly(33L);
    }

    // ---- list: parameter precedence and envelope --------------------------------------------

    @Test
    void camelCaseWinsOverSnakeCaseAliasWhenBothAreSent() {
        JsonNode body = getJson("/api/v1/contacts?perPage=2&per_page=5&sortBy=first_name&sort_by=updated_at", C);

        assertThat(body.get("perPage").asInt()).isEqualTo(2);
        assertThat(ids(body.get("items"))).containsExactly(10L, 11L);
    }

    @Test
    void listItemsUseTheSameSnakeCaseShapeAsShow() {
        JsonNode items = getJson("/api/v1/contacts?perPage=1", C).get("items");

        assertThat(items).hasSize(1);
        assertThat(fieldNames(items.get(0))).containsExactlyElementsOf(OPENAPI_CONTACT_FIELDS);
        assertThat(fieldNames(getJson("/api/v1/contacts", C))).containsExactly("items", "page", "perPage", "totalCount");
    }

    @Test
    void pageFarBeyondTheEndIsEmptyButKeepsTotalCount() {
        JsonNode body = getJson("/api/v1/contacts?page=100000&perPage=200", A);

        assertThat(body.get("items")).isEmpty();
        assertThat(body.get("page").asInt()).isEqualTo(100000);
        assertThat(body.get("perPage").asInt()).isEqualTo(200);
        assertThat(body.get("totalCount").asLong()).isEqualTo(2);
    }

    @Test
    void negativePagingAndNonNumericPerPageAre400ProblemJson() {
        assertProblem(get("/api/v1/contacts?page=-1", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?perPage=-5", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?per_page=0", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?perPage=ten", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
    }

    @Test
    void sortByIsCaseSensitiveAndRejectsWhitespaceVariants() {
        assertProblem(get("/api/v1/contacts?sortBy=first_name%20asc", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?sortBy=FIRST_NAME", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?sortBy=id", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
        assertProblem(get("/api/v1/contacts?sortBy=created_at%20ASC", C), HttpStatus.BAD_REQUEST, "/api/v1/contacts");
    }

    @Test
    void sortByTiesAreBrokenByIdAscending() {
        for (long id = 40; id < 44; id++) {
            seeder.insertContact(new ContactRow(id, Z, null, null, null, "Same", "Name", ACCESS_PUBLIC, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, false, null, null, null, null,
                    null, null, null, null, null, T0.plusYears(1), T0.plusYears(1)));
        }

        assertThat(ids(getJson("/api/v1/contacts?query=Same&sortBy=first_name", C).get("items")))
                .containsExactly(40L, 41L, 42L, 43L);
        assertThat(ids(getJson("/api/v1/contacts?query=Same", C).get("items"))).containsExactly(40L, 41L, 42L, 43L);
        assertThat(ids(getJson("/api/v1/contacts?query=Same&sortBy=updated_at", C).get("items")))
                .containsExactly(40L, 41L, 42L, 43L);
    }

    // ---- authorization: Shared access and cross-asset permissions ---------------------------

    @Test
    void sharedContactIsVisibleOnlyToItsUserGrantee() {
        seeder.insertContact(row(50, "Shared", "ToB", Z, null, ACCESS_SHARED, null, null, null, null));
        seeder.insertPermission("Contact", 50, B, null);

        assertThat(getJson("/api/v1/contacts?query=ToB", A).get("items")).isEmpty();
        assertProblem(get("/api/v1/contacts/50", A), HttpStatus.NOT_FOUND, "/api/v1/contacts/50");
        assertThat(getJson("/api/v1/contacts/autocomplete?term=ToB", A).get("results")).isEmpty();

        assertThat(ids(getJson("/api/v1/contacts?query=ToB", B).get("items"))).containsExactly(50L);
        assertThat(getJson("/api/v1/contacts/50", B).get("access").asText()).isEqualTo("Shared");
        assertThat(ids(getJson("/api/v1/contacts/autocomplete?term=ToB", B).get("results"))).containsExactly(50L);
        assertThat(getJson("/api/v1/contacts/50", Z).get("id").asLong()).isEqualTo(50L);
        assertThat(getJson("/api/v1/contacts/50", C).get("id").asLong()).isEqualTo(50L);
    }

    @Test
    void sharedContactIsVisibleOnlyToMembersOfItsGranteeGroup() {
        seeder.insertContact(row(51, "Shared", "ToG", Z, null, ACCESS_SHARED, null, null, null, null));
        seeder.insertPermission("Contact", 51, null, G);

        assertProblem(get("/api/v1/contacts/51", A), HttpStatus.NOT_FOUND, "/api/v1/contacts/51");
        assertThat(getJson("/api/v1/contacts?query=ToG", A).get("totalCount").asLong()).isZero();
        assertThat(getJson("/api/v1/contacts/autocomplete?term=ToG", A).get("results")).isEmpty();

        assertThat(getJson("/api/v1/contacts/51", B).get("id").asLong()).isEqualTo(51L);
        assertThat(ids(getJson("/api/v1/contacts/autocomplete?term=ToG", B).get("results"))).containsExactly(51L);
    }

    @Test
    void permissionsOnOtherAssetTypesDoNotLeakContacts() {
        // an Account grant with the same asset_id must not make Private contact 16 visible to A
        seeder.insertPermission("Account", 16, A, null);
        seeder.insertPermission("Opportunity", 16, null, G);

        assertProblem(get("/api/v1/contacts/16", A), HttpStatus.NOT_FOUND, "/api/v1/contacts/16");
        assertProblem(get("/api/v1/contacts/16", B), HttpStatus.NOT_FOUND, "/api/v1/contacts/16");
        assertThat(getJson("/api/v1/contacts?query=Grace", A).get("items")).isEmpty();
        assertThat(getJson("/api/v1/contacts/autocomplete?term=Grace", B).get("results")).isEmpty();
    }

    @Test
    void assigneeSeesPrivateContactButOtherNonOwnersDoNot() {
        assertThat(getJson("/api/v1/contacts/12", B).get("assigned_to").asLong()).isEqualTo(B);
        assertThat(getJson("/api/v1/contacts/12", Z).get("user_id").asLong()).isEqualTo(Z);
        assertProblem(get("/api/v1/contacts/12", A), HttpStatus.NOT_FOUND, "/api/v1/contacts/12");
        assertThat(getJson("/api/v1/contacts?query=Carol", A).get("items")).isEmpty();
    }

    @Test
    void totalCountNeverCountsInvisibleRows() {
        assertThat(getJson("/api/v1/contacts?perPage=1", A).get("totalCount").asLong()).isEqualTo(2);
        assertThat(getJson("/api/v1/contacts?perPage=1", B).get("totalCount").asLong()).isEqualTo(4);
        assertThat(getJson("/api/v1/contacts?perPage=1", Z).get("totalCount").asLong()).isEqualTo(5);
        assertThat(getJson("/api/v1/contacts?perPage=1", C).get("totalCount").asLong()).isEqualTo(6);
    }

    // ---- show: id edge cases ----------------------------------------------------------------

    @Test
    void nonNumericNegativeAndZeroIdsAre404ProblemJson() {
        for (String id : new String[] {"abc", "-1", "0", "10abc"}) {
            ResponseEntity<String> response = get("/api/v1/contacts/" + id, C);
            assertThat(response.getStatusCode()).as(id).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getHeaders().getContentType()).isNotNull();
            assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .as(id).isTrue();
            assertThat(json(response).get("status").asInt()).isEqualTo(404);
        }
    }

    @Test
    void showDoesNotDistinguishInvisibleFromMissing() {
        ResponseEntity<String> invisible = get("/api/v1/contacts/16", A);
        ResponseEntity<String> missing = get("/api/v1/contacts/999", A);

        assertThat(invisible.getStatusCode()).isEqualTo(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(invisible).get("title").asText()).isEqualTo(json(missing).get("title").asText());
        assertThat(json(invisible).get("detail").asText().replace("16", "999"))
                .isEqualTo(json(missing).get("detail").asText());
    }

    // ---- autocomplete: parity ---------------------------------------------------------------

    @Test
    void autocompleteBlankTermMatchesEverythingVisible() {
        // Rails: params[:term] || '' -> text_search('') matches every row
        assertThat(ids(getJson("/api/v1/contacts/autocomplete?term=", A).get("results"))).containsExactly(10L, 11L);
        assertThat(ids(getJson("/api/v1/contacts/autocomplete?term=%20", A).get("results"))).containsExactly(10L, 11L);
        assertThat(ids(getJson("/api/v1/contacts/autocomplete", C).get("results")))
                .containsExactly(10L, 11L, 12L, 13L, 14L, 16L);
    }

    @Test
    void autocompleteIgnoresTheRailsRelatedParameter() {
        // `related` exclusion is deferred; it must neither filter nor fail
        assertThat(ids(getJson("/api/v1/contacts/autocomplete?term=a&related=10", B).get("results")))
                .containsExactly(10L, 12L, 13L, 14L);
        assertThat(ids(getJson("/api/v1/contacts/autocomplete?term=a&related=accounts/1", B).get("results")))
                .containsExactly(10L, 12L, 13L, 14L);
    }

    @Test
    void autocompleteNeverReturnsSoftDeletedRowsEvenForAdmins() {
        assertThat(getJson("/api/v1/contacts/autocomplete?term=Frank", C).get("results")).isEmpty();
        assertThat(getJson("/api/v1/contacts/autocomplete?term=Foster", C).get("results")).isEmpty();
    }

    @Test
    void autocompleteTextIsFirstSpaceLastEvenWhenLastNameIsEmpty() {
        seeder.insertContact(row(60, "Cher", "", Z, null, ACCESS_PUBLIC, null, null, null, null));

        JsonNode results = getJson("/api/v1/contacts/autocomplete?term=Cher", A).get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("id").asLong()).isEqualTo(60L);
        assertThat(results.get(0).get("text").asText()).isEqualTo("Cher ");
    }

    @Test
    void autocompleteMatchesEmailPhoneAndWildcardsLikeList() {
        assertThat(ids(getJson("/api/v1/contacts/autocomplete?term=bobby%40home", A).get("results")))
                .containsExactly(11L);
        assertThat(ids(getJson("/api/v1/contacts/autocomplete?term=555-0102", B).get("results"))).containsExactly(12L);
        assertThat(ids(getJson("/api/v1/contacts/autocomplete?term=_lice", A).get("results"))).containsExactly(10L);
    }

    // ---- read-only surface ------------------------------------------------------------------

    @Test
    void writeMethodsAreNotExposed() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TrustedHeaderAuthenticationFilter.HEADER, String.valueOf(C));
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
            String path = method == HttpMethod.POST ? "/api/v1/contacts" : "/api/v1/contacts/10";
            ResponseEntity<String> response = rest.exchange(URI.create(url(path)), method,
                    new HttpEntity<>("{\"first_name\":\"X\"}", headers), String.class);
            assertThat(response.getStatusCode()).as(method + " " + path).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }
        assertThat(get("/api/v1/contacts/10", C).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getJson("/api/v1/contacts/10", C).get("first_name").asText()).isEqualTo("Alice");
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
