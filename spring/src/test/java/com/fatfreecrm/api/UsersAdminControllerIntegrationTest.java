package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class UsersAdminControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> USER_KEYS = Set.of(
        "id", "username", "email", "first_name", "last_name", "title", "company", "alt_email", "phone", "mobile",
        "google", "deleted_at", "created_at", "updated_at", "admin", "suspended_at",
        "subscribe_to_comment_replies", "receive_assigned_notifications", "zoom", "teams", "signal", "instagram",
        "facebook", "mastodon", "bluesky", "twitter", "linkedin", "blog"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private User admin;
    private String aliceBearer;
    private String adminBearer;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice", "  ", null, false);
        admin = user("root_admin", "Admin", "Root", true);
        aliceBearer = bearer(alice);
        adminBearer = bearer(admin);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void userShowAndProfileUseRailsNameArraysAndPreserveSelfEndpoint() throws Exception {
        User bob = user("bob", "Bob", "Smith", false);
        int versionsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM versions", Integer.class);

        mockMvc.perform(get("/api/v1/users/{id}", alice.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("alice"));
        mockMvc.perform(get("/api/v1/users/{id}", bob.getId())
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users/{id}", bob.getId())
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("Bob"));
        mockMvc.perform(get("/api/v1/users/999999").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/users/{id}", alice.getId())).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("alice"));
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("Admin"));
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(alice.getId()));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM versions", Integer.class))
            .isEqualTo(versionsBefore);
    }

    @Test
    void autocompleteUsesAccessibleUsersNameOrderLimitAndRailsJavascriptEscaping() throws Exception {
        alice.setFirstName(null);
        userRepository.saveAndFlush(alice);
        User special = user("special", "Sam O'Neil", "A</B `$", false);
        for (int index = 0; index < 11; index++) {
            user("user_" + index, String.format("Name%02d", index), "User", false);
        }

        MvcResult result = mockMvc.perform(get("/api/v1/users/autocomplete").param("term", "")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("results")).hasSize(10);
        assertThat(body.path("results").get(0).path("text").asText()).isEqualTo("Admin Root (@root_admin)");

        User alpha = user("alpha_candidate", "Aaron", "Alpha", false);
        alpha.setEmail("common-alpha@example.test");
        userRepository.saveAndFlush(alpha);
        User nullName = user("null_candidate", null, "Zulu", false);
        nullName.setEmail("common-zulu@example.test");
        userRepository.saveAndFlush(nullName);
        JsonNode nullsLast = JSON.readTree(mockMvc.perform(get("/api/v1/users/autocomplete").param("term", "common")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(nullsLast.path("results")).hasSize(2);
        assertThat(nullsLast.path("results").get(0).path("id").asLong()).isEqualTo(alpha.getId());
        assertThat(nullsLast.path("results").get(1).path("id").asLong()).isEqualTo(nullName.getId());

        JsonNode aliceResults = JSON.readTree(mockMvc.perform(get("/api/v1/users/autocomplete").param("term", "")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(aliceResults.path("results")).hasSize(1);
        assertThat(aliceResults.path("results").get(0).path("id").asLong()).isEqualTo(alice.getId());

        JsonNode escaped = JSON.readTree(mockMvc.perform(get("/api/v1/users/autocomplete").param("term", "sam!?")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(escaped.path("results")).hasSize(1);
        assertThat(escaped.path("results").get(0).path("id").asLong()).isEqualTo(special.getId());
        assertThat(escaped.path("results").get(0).path("text").asText())
            .contains("Sam O\\'Neil", "<\\/", "\\`", "\\$");
    }

    @Test
    void userTextSearchUsesPostgresUpperForSearchAndAutocompletePatterns() throws Exception {
        User strasse = user("straße", "Straße", "Search", false);

        JsonNode adminList = JSON.readTree(mockMvc.perform(get("/api/v1/admin/users").param("query", "ß")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(adminList.path("items").findValuesAsText("id")).contains(strasse.getId().toString());

        JsonNode autocomplete = JSON.readTree(mockMvc.perform(get("/api/v1/users/autocomplete").param("term", "ß")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(autocomplete.path("results").findValuesAsText("id")).contains(strasse.getId().toString());
    }

    @Test
    void adminUsersListIsPaginatedAndOmitsDeviseSecrets() throws Exception {
        user("charlie", "Charlie", "Other", false);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/users")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.perPage").value(20))
            .andExpect(jsonPath("$.totalCount").value(3))
            .andReturn();
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        JsonNode row = body.path("items").get(0);
        Set<String> keys = new HashSet<>();
        row.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrderElementsOf(USER_KEYS);
        assertThat(row.toString()).doesNotContainIgnoringCase("password", "salt", "token");

        MvcResult pageTwo = mockMvc.perform(get("/api/v1/admin/users").param("per_page", "1").param("page", "2")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2))
            .andExpect(jsonPath("$.perPage").value(1)).andReturn();
        assertThat(JSON.readTree(pageTwo.getResponse().getContentAsString()).path("items")).hasSize(1);
        mockMvc.perform(get("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyAdminRouteRejectsNonAdminsAndUnknownRecordsAreNotFound() throws Exception {
        for (String path : Set.of(
            "/api/v1/admin/users",
            "/api/v1/admin/users/" + alice.getId(),
            "/api/v1/admin/groups/999999",
            "/api/v1/admin/fields/999999",
            "/api/v1/admin/tags",
            "/api/v1/admin/research_tools"
        )) {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/v1/admin/users/{id}", alice.getId()).header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("alice"));
        mockMvc.perform(get("/api/v1/admin/users/999999").header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/admin/groups/999999").header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/admin/fields/999999").header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void adminSerializesGroupFieldTagAndResearchToolRows() throws Exception {
        Instant at = Instant.parse("2024-01-02T03:04:05Z");
        jdbcTemplate.update(
            "INSERT INTO groups (id, name, created_at, updated_at) VALUES (1, 'Capture Group', ?, ?)",
            Timestamp.from(at), Timestamp.from(at));
        jdbcTemplate.update(
            "INSERT INTO fields (id, position, name, label, \"as\", collection, settings, "
                + "created_at, updated_at, minlength) "
                + "VALUES (1, 1, 'ab270_capture_field', 'AB270 Capture Field', 'select', "
                + "'---\n- Red\n- Blue\n', "
                + "'--- !ruby/hash:ActiveSupport::HashWithIndifferentAccess\nenabled: true\ndisplay: compact\n', "
                + "?, ?, 0)",
            Timestamp.from(at), Timestamp.from(at)
        );
        jdbcTemplate.update("INSERT INTO tags (id, name, taggings_count) VALUES (1, 'capture', 2)");
        jdbcTemplate.update(
            "INSERT INTO research_tools (id, name, url_template, enabled, created_at, updated_at) "
                + "VALUES (1, 'AB270 Capture Tool', 'https://example.test/search?q={query}', TRUE, ?, ?)",
            Timestamp.from(at), Timestamp.from(at)
        );

        assertThat(JSON.readTree(mockMvc.perform(get("/api/v1/admin/groups/1")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()))
            .isEqualTo(JSON.readTree("{\"id\":1,\"name\":\"Capture Group\","
                + "\"created_at\":\"2024-01-02T03:04:05.000Z\",\"updated_at\":\"2024-01-02T03:04:05.000Z\"}"));

        assertThat(JSON.readTree(mockMvc.perform(get("/api/v1/admin/fields/1")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()))
            .isEqualTo(JSON.readTree("{\"id\":1,\"field_group_id\":null,\"position\":1,"
                + "\"name\":\"ab270_capture_field\",\"label\":\"AB270 Capture Field\",\"hint\":null,"
                + "\"placeholder\":null,\"as\":\"select\",\"collection\":[\"Red\",\"Blue\"],\"disabled\":null,"
                + "\"required\":null,\"maxlength\":null,\"created_at\":\"2024-01-02T03:04:05.000Z\","
                + "\"updated_at\":\"2024-01-02T03:04:05.000Z\",\"pair_id\":null,"
                + "\"settings\":{\"enabled\":true,\"display\":\"compact\"},\"minlength\":0,\"pattern\":null,"
                + "\"autofocus\":null,\"autocomplete\":null,\"list\":null,\"multiple\":null,\"title\":null}"));

        assertThat(JSON.readTree(mockMvc.perform(get("/api/v1/admin/tags")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()))
            .isEqualTo(JSON.readTree("[{\"id\":1,\"name\":\"capture\",\"taggings_count\":2}]"));

        assertThat(JSON.readTree(mockMvc.perform(get("/api/v1/admin/research_tools")
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()))
            .isEqualTo(JSON.readTree("[{\"id\":1,\"name\":\"AB270 Capture Tool\","
                + "\"url_template\":\"https://example.test/search?q={query}\",\"enabled\":true,"
                + "\"created_at\":\"2024-01-02T03:04:05.000Z\",\"updated_at\":\"2024-01-02T03:04:05.000Z\"}]"));
    }

    private User user(String username, String firstName, String lastName, boolean isAdmin) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(isAdmin);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM preferences");
        jdbcTemplate.update("DELETE FROM permissions");
        jdbcTemplate.update("DELETE FROM groups_users");
        jdbcTemplate.update("DELETE FROM taggings");
        jdbcTemplate.update("DELETE FROM fields");
        jdbcTemplate.update("DELETE FROM field_groups");
        jdbcTemplate.update("DELETE FROM research_tools");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM groups");
        jdbcTemplate.update("DELETE FROM users");
    }
}
