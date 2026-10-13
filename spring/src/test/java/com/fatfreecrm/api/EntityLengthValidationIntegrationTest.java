package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * AB-272 {@code validates_lengths_from_database} parity (Account / Contact / Lead declare it;
 * Campaign / Opportunity do not). Rails validates every string column against its schema limit,
 * counting characters, with the pluralised {@code errors.messages.too_long.other} template —
 * {@code "is too long (maximum is N characters)"} — see
 * {@code spec/models/entities/contact_spec.rb} ("validate ... max_length").
 *
 * <p>Limits below are the Rails schema limits ({@code db/schema.rb} / Flyway baseline). Columns
 * where the Spring limit differs from the schema ({@code email}/{@code alt_email}: 254 in Rails,
 * {@code background_info}: unlimited in Rails) are deliberately not pinned here; they are reported
 * on the PR instead.
 */
class EntityLengthValidationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Map<String, Integer> ACCOUNT_LIMITS = limits(
        "name", 64, "website", 64, "toll_free_phone", 32, "phone", 32, "fax", 32);

    private static final Map<String, Integer> CONTACT_LIMITS = limits(
        "first_name", 64, "last_name", 64, "title", 64, "department", 64, "source", 32,
        "phone", 32, "mobile", 32, "fax", 32,
        "blog", 128, "linkedin", 128, "facebook", 128, "twitter", 128, "zoom", 128, "teams", 128,
        "signal", 128, "instagram", 128, "mastodon", 128, "bluesky", 128);

    private static final Map<String, Integer> LEAD_LIMITS = limits(
        "first_name", 64, "last_name", 64, "title", 64, "company", 64, "referred_by", 64,
        "source", 32, "phone", 32, "mobile", 32,
        "blog", 128, "linkedin", 128, "facebook", 128, "twitter", 128, "zoom", 128, "teams", 128,
        "signal", 128, "instagram", 128, "mastodon", 128, "bluesky", 128);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private String aliceBearer;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice");
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    // ------------------------------------------------------------------ create: over the limit

    @Test
    void accountCreateRejectsEveryOverLimitColumnWithRailsTooLongMessage() throws Exception {
        assertOverLimitRejectedOnCreate("accounts", "account", ACCOUNT_LIMITS);
    }

    @Test
    void contactCreateRejectsEveryOverLimitColumnWithRailsTooLongMessage() throws Exception {
        assertOverLimitRejectedOnCreate("contacts", "contact", CONTACT_LIMITS);
    }

    @Test
    void leadCreateRejectsEveryOverLimitColumnWithRailsTooLongMessage() throws Exception {
        assertOverLimitRejectedOnCreate("leads", "lead", LEAD_LIMITS);
    }

    private void assertOverLimitRejectedOnCreate(String table, String wrapper,
        Map<String, Integer> limits) throws Exception {
        for (Map.Entry<String, Integer> column : limits.entrySet()) {
            String attribute = column.getKey();
            int max = column.getValue();
            ObjectNode body = validBody(wrapper);
            ((ObjectNode) body.get(wrapper)).put(attribute, "x".repeat(max + 1));
            mockMvc.perform(authed(post("/api/v1/" + table)).content(body.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().json("{\"errors\":{\"" + attribute + "\":[\"" + tooLong(max)
                    + "\"]}}", JsonCompareMode.STRICT));
        }
        assertThat(count(table)).as("nothing persists on a too_long 422").isZero();
        assertThat(count("versions")).isZero();
    }

    // ------------------------------------------------------------------ create: at the limit

    @Test
    void accountCreateAcceptsValuesExactlyAtTheLimit() throws Exception {
        assertAtLimitAccepted("accounts", "account", ACCOUNT_LIMITS);
    }

    @Test
    void contactCreateAcceptsValuesExactlyAtTheLimit() throws Exception {
        assertAtLimitAccepted("contacts", "contact", CONTACT_LIMITS);
    }

    @Test
    void leadCreateAcceptsValuesExactlyAtTheLimit() throws Exception {
        assertAtLimitAccepted("leads", "lead", LEAD_LIMITS);
    }

    private void assertAtLimitAccepted(String table, String wrapper, Map<String, Integer> limits)
        throws Exception {
        ObjectNode body = validBody(wrapper);
        limits.forEach((attribute, max) -> ((ObjectNode) body.get(wrapper)).put(attribute, "y".repeat(max)));
        String response = mockMvc.perform(authed(post("/api/v1/" + table)).content(body.toString()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        long id = JSON.readTree(response).path("id").asLong();
        Map<String, Object> row = row(table, id);
        limits.forEach((attribute, max) ->
            assertThat(row.get(attribute)).as(table + "." + attribute).isEqualTo("y".repeat(max)));
    }

    // ------------------------------------------------------------------ characters, not bytes

    @Test
    void lengthIsCountedInCharactersLikeRailsStringColumns() throws Exception {
        ObjectNode ok = validBody("contact");
        ((ObjectNode) ok.get("contact")).put("first_name", "é".repeat(64));
        String response = mockMvc.perform(authed(post("/api/v1/contacts")).content(ok.toString()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        long id = JSON.readTree(response).path("id").asLong();
        assertThat(row("contacts", id).get("first_name")).isEqualTo("é".repeat(64));

        ObjectNode tooLong = validBody("contact");
        ((ObjectNode) tooLong.get("contact")).put("first_name", "é".repeat(65));
        mockMvc.perform(authed(post("/api/v1/contacts")).content(tooLong.toString()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"first_name\":[\"" + tooLong(64) + "\"]}}",
                JsonCompareMode.STRICT));
        assertThat(count("contacts")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ several columns at once

    @Test
    void everyOverLimitColumnIsReportedInOneResponse() throws Exception {
        ObjectNode body = validBody("lead");
        ((ObjectNode) body.get("lead")).put("first_name", "x".repeat(65));
        ((ObjectNode) body.get("lead")).put("source", "x".repeat(33));
        ((ObjectNode) body.get("lead")).put("blog", "x".repeat(129));
        mockMvc.perform(authed(post("/api/v1/leads")).content(body.toString()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{"
                + "\"first_name\":[\"" + tooLong(64) + "\"],"
                + "\"source\":[\"" + tooLong(32) + "\"],"
                + "\"blog\":[\"" + tooLong(128) + "\"]}}", JsonCompareMode.STRICT));
        assertThat(count("leads")).isZero();
    }

    @Test
    void tooLongIsReportedAlongsideOtherValidationFailuresOnTheSameRecord() throws Exception {
        ObjectNode body = validBody("lead");
        ((ObjectNode) body.get("lead")).put("company", "x".repeat(65));
        ((ObjectNode) body.get("lead")).put("status", "bogus");
        mockMvc.perform(authed(post("/api/v1/leads")).content(body.toString()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{"
                + "\"status\":[\"is not included in the list\"],"
                + "\"company\":[\"" + tooLong(64) + "\"]}}", JsonCompareMode.STRICT));
        assertThat(count("leads")).isZero();
    }

    // ------------------------------------------------------------------ update

    @Test
    void updateRejectsOverLimitValuesAndLeavesRowAndVersionsUntouched() throws Exception {
        long accountId = jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, user_id, assigned_to, access, created_at, updated_at)"
                + " VALUES ('Acme', ?, ?, 'Public', now(), now()) RETURNING id",
            Long.class, alice.getId(), alice.getId());
        long contactId = jdbcTemplate.queryForObject(
            "INSERT INTO contacts (first_name, last_name, title, user_id, assigned_to, access,"
                + " created_at, updated_at) VALUES ('Ann', 'Lee', 'CTO', ?, ?, 'Public', now(), now())"
                + " RETURNING id", Long.class, alice.getId(), alice.getId());
        long leadId = jdbcTemplate.queryForObject(
            "INSERT INTO leads (first_name, last_name, company, user_id, assigned_to, access, status,"
                + " created_at, updated_at) VALUES ('Bo', 'Ray', 'Initech', ?, ?, 'Public', 'new',"
                + " now(), now()) RETURNING id", Long.class, alice.getId(), alice.getId());

        mockMvc.perform(authed(put("/api/v1/accounts/" + accountId))
                .content("{\"account\":{\"website\":\"" + "w".repeat(65) + "\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"website\":[\"" + tooLong(64) + "\"]}}",
                JsonCompareMode.STRICT));
        mockMvc.perform(authed(put("/api/v1/contacts/" + contactId))
                .content("{\"contact\":{\"title\":\"" + "t".repeat(65) + "\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"title\":[\"" + tooLong(64) + "\"]}}",
                JsonCompareMode.STRICT));
        mockMvc.perform(authed(put("/api/v1/leads/" + leadId))
                .content("{\"lead\":{\"company\":\"" + "c".repeat(65) + "\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"company\":[\"" + tooLong(64) + "\"]}}",
                JsonCompareMode.STRICT));

        assertThat(row("accounts", accountId).get("website")).isNull();
        assertThat(row("contacts", contactId).get("title")).isEqualTo("CTO");
        assertThat(row("leads", leadId).get("company")).isEqualTo("Initech");
        assertThat(count("versions")).as("no update version on a failed save").isZero();
    }

    // ------------------------------------------------------------------ families without the validator

    /**
     * Campaign and Opportunity never declare {@code validates_lengths_from_database}: Rails lets
     * the 65-char name through to PostgreSQL, which raises
     * {@code ActiveRecord::ValueTooLong} → 500, and the INSERT is rolled back.
     */
    @Test
    void campaignAndOpportunityNamesAreNotLengthValidatedSoOverflowIsA500() throws Exception {
        mockMvc.perform(authed(post("/api/v1/campaigns"))
                .content("{\"campaign\":{\"name\":\"" + "c".repeat(65) + "\"}}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.errors").doesNotExist());
        mockMvc.perform(authed(post("/api/v1/opportunities"))
                .content("{\"opportunity\":{\"name\":\"" + "o".repeat(65) + "\"},\"account\":{}}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.errors").doesNotExist());
        assertThat(count("campaigns")).isZero();
        assertThat(count("opportunities")).isZero();
        assertThat(count("versions")).isZero();
    }

    @Test
    void campaignAndOpportunityNamesAtTheColumnLimitAreAccepted() throws Exception {
        mockMvc.perform(authed(post("/api/v1/campaigns"))
                .content("{\"campaign\":{\"name\":\"" + "c".repeat(64) + "\"}}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name", Matchers.hasLength(64)));
        mockMvc.perform(authed(post("/api/v1/opportunities"))
                .content("{\"opportunity\":{\"name\":\"" + "o".repeat(64) + "\"},\"account\":{}}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name", Matchers.hasLength(64)));
    }

    // ------------------------------------------------------------------ helpers

    private static String tooLong(int max) {
        return "is too long (maximum is " + max + " characters)";
    }

    private static ObjectNode validBody(String wrapper) {
        ObjectNode body = JSON.createObjectNode();
        ObjectNode entity = body.putObject(wrapper);
        switch (wrapper) {
            case "account" -> entity.put("name", "Acme");
            case "contact", "lead" -> {
                entity.put("first_name", "Ann");
                entity.put("last_name", "Lee");
            }
            default -> throw new IllegalArgumentException(wrapper);
        }
        return body;
    }

    private static Map<String, Integer> limits(Object... pairs) {
        Map<String, Integer> limits = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            limits.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return limits;
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, aliceBearer)
            .contentType(MediaType.APPLICATION_JSON);
    }

    private Map<String, Object> row(String table, long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM " + table + " WHERE id = ?", id);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setEncryptedPassword("enc");
        user.setPasswordSalt("salt");
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private void clearData() {
        for (String table : List.of("versions", "permissions", "contacts", "leads", "opportunities",
            "campaigns", "accounts", "users")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }
}
