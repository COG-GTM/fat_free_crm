package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code /api/v1/admin} groups, tags, research tools and settings writes pinned to the Rails
 * {@code Admin::*Controller} + model behaviour (including the intentionally mirrored 500s).
 */
class AdminWriteControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String HWIA = "--- !ruby/hash:ActiveSupport::HashWithIndifferentAccess";
    private static final String UNEXPECTED = "An unexpected error occurred.";

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
        alice = user("alice", false);
        admin = user("root_admin", true);
        aliceBearer = bearer(alice);
        adminBearer = bearer(admin);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void catalogWritesRequireAuthenticationAndAdminRole() throws Exception {
        long groupId = insertGroup("Sales");
        long tagId = insertTag("hot");
        long toolId = jdbcTemplate.queryForObject("INSERT INTO research_tools (name, enabled, created_at, "
            + "updated_at) VALUES ('x', true, now(), now()) RETURNING id", Long.class);
        List<MockHttpServletRequestBuilder> routes = List.of(
            json(post("/api/v1/admin/groups")).content("{\"group\":{\"name\":\"Ops\"}}"),
            json(put("/api/v1/admin/groups/{id}", groupId)).content("{\"group\":{\"name\":\"Ops\"}}"),
            delete("/api/v1/admin/groups/{id}", groupId),
            json(post("/api/v1/admin/tags")).content("{\"tag\":{\"name\":\"warm\"}}"),
            json(put("/api/v1/admin/tags/{id}", tagId)).content("{\"tag\":{\"name\":\"warm\"}}"),
            delete("/api/v1/admin/tags/{id}", tagId),
            json(post("/api/v1/admin/research_tools")).content("{\"research_tool\":{\"name\":\"y\"}}"),
            json(put("/api/v1/admin/research_tools/{id}", toolId)).content("{\"research_tool\":{\"name\":\"y\"}}"),
            delete("/api/v1/admin/research_tools/{id}", toolId),
            json(put("/api/v1/admin/settings")).content("{\"settings\":{\"host\":\"evil.example\"}}"));
        for (MockHttpServletRequestBuilder route : routes) {
            mockMvc.perform(route).andExpect(status().isUnauthorized());
            mockMvc.perform(route.header(HttpHeaders.AUTHORIZATION, aliceBearer)).andExpect(status().isForbidden());
        }
        assertThat(jdbcTemplate.queryForList("SELECT name FROM groups", String.class)).containsExactly("Sales");
        assertThat(jdbcTemplate.queryForList("SELECT name FROM tags", String.class)).containsExactly("hot");
        assertThat(jdbcTemplate.queryForList("SELECT name FROM research_tools", String.class)).containsExactly("x");
        assertThat(count("settings")).isZero();
    }

    @Test
    void groupCreateCommitsRowAndMembershipsThenAnswers500LikeRails() throws Exception {
        mockMvc.perform(adminJson(post("/api/v1/admin/groups"))
                .content("{\"group\":{\"name\":\"Ops\",\"user_ids\":[\"" + alice.getId() + "\"," + admin.getId()
                    + "]}}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value(UNEXPECTED));

        long id = jdbcTemplate.queryForObject("SELECT id FROM groups WHERE name = 'Ops'", Long.class);
        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM groups_users WHERE group_id = ? ORDER BY user_id",
            Long.class, id)).containsExactly(alice.getId(), admin.getId());
    }

    @Test
    void groupCreateValidatesPresenceAndCaseSensitiveUniquenessAndRequiresRoot() throws Exception {
        insertGroup("Sales");

        mockMvc.perform(adminJson(post("/api/v1/admin/groups")).content("{\"group\":{\"name\":\"  \"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name", contains("can't be blank")));
        mockMvc.perform(adminJson(post("/api/v1/admin/groups")).content("{\"group\":{\"name\":\"Sales\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name", contains("has already been taken")));
        mockMvc.perform(adminJson(post("/api/v1/admin/groups")).content("{\"group\":{\"name\":\"sales\"}}"))
            .andExpect(status().isInternalServerError());
        for (String body : List.of("{}", "{\"group\":{}}", "")) {
            mockMvc.perform(adminJson(post("/api/v1/admin/groups")).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("param is missing or the value is empty or invalid: group"));
        }
        mockMvc.perform(adminJson(post("/api/v1/admin/groups"))
            .content("{\"group\":{\"name\":\"Ghost\",\"user_ids\":[999999]}}"))
            .andExpect(status().isNotFound());

        assertThat(jdbcTemplate.queryForList("SELECT name FROM groups ORDER BY id", String.class))
            .containsExactly("Sales", "sales");
    }

    @Test
    void groupUpdateIsPartialWritesMembershipsBeforeValidationAndDestroyRemovesMemberships() throws Exception {
        long id = insertGroup("Sales");
        Timestamp before = jdbcTemplate.queryForObject("SELECT updated_at FROM groups WHERE id = ?", Timestamp.class,
            id);

        mockMvc.perform(adminJson(put("/api/v1/admin/groups/{id}", id))
                .content("{\"group\":{\"user_ids\":[" + alice.getId() + "]}}"))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM groups WHERE id = ?", String.class, id))
            .isEqualTo("Sales");
        assertThat(jdbcTemplate.queryForObject("SELECT updated_at FROM groups WHERE id = ?", Timestamp.class, id))
            .isEqualTo(before);

        mockMvc.perform(adminJson(put("/api/v1/admin/groups/{id}", id))
                .content("{\"group\":{\"name\":\"\",\"user_ids\":[" + admin.getId() + "]}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name", contains("can't be blank")));
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM groups WHERE id = ?", String.class, id))
            .isEqualTo("Sales");
        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM groups_users WHERE group_id = ?", Long.class, id))
            .containsExactly(admin.getId());

        mockMvc.perform(adminJson(put("/api/v1/admin/groups/{id}", id)).content("{\"group\":{\"name\":\"Renamed\"}}"))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM groups WHERE id = ?", String.class, id))
            .isEqualTo("Renamed");
        assertThat(jdbcTemplate.queryForObject("SELECT updated_at FROM groups WHERE id = ?", Timestamp.class, id))
            .isNotEqualTo(before);
        mockMvc.perform(adminJson(put("/api/v1/admin/groups/999999")).content("{\"group\":{\"name\":\"X\"}}"))
            .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/admin/groups/{id}", id).header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNoContent());
        assertThat(count("groups")).isZero();
        assertThat(count("groups_users")).isZero();
        mockMvc.perform(delete("/api/v1/admin/groups/{id}", id).header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void tagWritesMirrorActsAsTaggableOnValidationsAndCascadeTaggings() throws Exception {
        mockMvc.perform(adminJson(post("/api/v1/admin/tags")).content("{\"tag\":{\"name\":\"hot\"}}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value(UNEXPECTED));
        long id = jdbcTemplate.queryForObject("SELECT id FROM tags WHERE name = 'hot'", Long.class);

        mockMvc.perform(adminJson(post("/api/v1/admin/tags")).content("{\"tag\":{\"name\":\"hot\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name", contains("has already been taken")));
        mockMvc.perform(adminJson(post("/api/v1/admin/tags")).content("{\"tag\":{\"name\":\"Hot\"}}"))
            .andExpect(status().isInternalServerError());
        mockMvc.perform(adminJson(post("/api/v1/admin/tags")).content("{\"tag\":{\"name\":\"\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name", contains("can't be blank")));
        mockMvc.perform(adminJson(post("/api/v1/admin/tags")).content("{\"tag\":{\"name\":\"" + "x".repeat(256)
            + "\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name", contains("is too long (maximum is 255 characters)")));
        mockMvc.perform(adminJson(post("/api/v1/admin/tags")).content("{\"tag\":{}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("param is missing or the value is empty or invalid: tag"));
        assertThat(jdbcTemplate.queryForList("SELECT name FROM tags ORDER BY id", String.class))
            .containsExactly("hot", "Hot");

        mockMvc.perform(adminJson(put("/api/v1/admin/tags/{id}", id))
            .content("{\"tag\":{\"name\":\"warm\",\"taggings_count\":\"3\"}}"))
            .andExpect(status().isNoContent());
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT name, taggings_count FROM tags WHERE id = ?", id);
        assertThat(row.get("name")).isEqualTo("warm");
        assertThat(((Number) row.get("taggings_count")).intValue()).isEqualTo(3);
        mockMvc.perform(adminJson(put("/api/v1/admin/tags/{id}", id)).content("{\"tag\":{\"name\":\"Hot\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name", contains("has already been taken")));

        jdbcTemplate.update("INSERT INTO taggings (tag_id, taggable_id, taggable_type, context, created_at) "
            + "VALUES (?, 1, 'Account', 'tags', now())", id);
        mockMvc.perform(delete("/api/v1/admin/tags/{id}", id).header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForList("SELECT name FROM tags", String.class)).containsExactly("Hot");
        assertThat(count("taggings")).isZero();
        mockMvc.perform(delete("/api/v1/admin/tags/{id}", id).header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void researchToolWritesHaveNoValidationsAndCreateReturnsRowJson() throws Exception {
        mockMvc.perform(adminJson(post("/api/v1/admin/research_tools"))
                .content("{\"research_tool\":{\"name\":\"Crunchbase\",\"url_template\":\"https://cb.example/{q}\","
                    + "\"enabled\":\"1\"}}"))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, "/admin/research_tools"))
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.name").value("Crunchbase"))
            .andExpect(jsonPath("$.url_template").value("https://cb.example/{q}"))
            .andExpect(jsonPath("$.enabled").value(true));
        long id = jdbcTemplate.queryForObject("SELECT id FROM research_tools WHERE name = 'Crunchbase'", Long.class);

        mockMvc.perform(adminJson(post("/api/v1/admin/research_tools"))
            .content("{\"research_tool\":{\"url_template\":\"u\"}}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name", nullValue()))
            .andExpect(jsonPath("$.enabled").value(false));
        for (String body : List.of("{}", "{\"research_tool\":{}}")) {
            mockMvc.perform(adminJson(post("/api/v1/admin/research_tools")).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                    .value("param is missing or the value is empty or invalid: research_tool"));
        }

        mockMvc.perform(adminJson(put("/api/v1/admin/research_tools/{id}", id))
            .content("{\"research_tool\":{\"enabled\":\"0\"}}"))
            .andExpect(status().isNoContent());
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT name, enabled FROM research_tools WHERE id = ?", id);
        assertThat(row.get("name")).isEqualTo("Crunchbase");
        assertThat(row.get("enabled")).isEqualTo(false);
        mockMvc.perform(adminJson(put("/api/v1/admin/research_tools/999999"))
            .content("{\"research_tool\":{\"name\":\"x\"}}"))
            .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/admin/research_tools/{id}", id).header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNoContent());
        assertThat(count("research_tools")).isEqualTo(1);
        mockMvc.perform(delete("/api/v1/admin/research_tools/{id}", id).header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void settingsUpdateWritesPsychYamlInPermitOrderWithRailsCoercions() throws Exception {
        mockMvc.perform(adminJson(put("/api/v1/admin/settings"))
                .content("{\"settings\":{\"host\":\"crm.example\",\"per_user_locale\":true,"
                    + "\"require_first_names\":\"1\","
                    + "\"user_signup\":\"needs_approval\",\"lead_status\":[\"new\",\"\",\"contacted\"],"
                    + "\"task_bucket\":\"a\\nb\",\"smtp\":{\"address\":null,\"port\":25,\"enable_starttls_auto\":\"1\","
                    + "\"bogus\":\"x\"},\"email_dropbox\":{\"ssl\":\"1\",\"address_aliases\":[\"x@y.z\"]},"
                    + "\"email_comment_replies\":{},\"ai_prompts\":{\"about_my_business\":\"line one\\nline two\"},"
                    + "\"opportunity_default_stage\":1.5,\"locale\":\"\",\"unknown\":\"z\"}}"))
            .andExpect(status().isFound())
            .andExpect(header().string(HttpHeaders.LOCATION, "/admin/settings"));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT name, value FROM settings ORDER BY id");
        assertThat(rows).extracting(row -> row.get("name")).containsExactly(
            "host", "locale", "per_user_locale", "user_signup", "require_first_names", "opportunity_default_stage",
            "lead_status", "smtp", "email_dropbox", "email_comment_replies", "ai_prompts");
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        rows.forEach(row -> values.put((String) row.get("name"), row.get("value")));
        assertThat(values.get("host")).isEqualTo("--- crm.example\n");
        assertThat(values.get("locale")).isEqualTo("--- ''\n");
        assertThat(values.get("per_user_locale")).isEqualTo("--- false\n"); // only the literal "1" is true
        assertThat(values.get("require_first_names")).isEqualTo("--- true\n");
        assertThat(values.get("user_signup")).isEqualTo("--- :needs_approval\n");
        assertThat(values.get("opportunity_default_stage")).isEqualTo("--- 1.5\n");
        assertThat(values.get("lead_status")).isEqualTo("---\n- new\n- ''\n- contacted\n");
        assertThat(values.get("smtp")).isEqualTo(HWIA + "\naddress:\nenable_starttls_auto: '1'\nport: 25\n");
        assertThat(values.get("email_dropbox")).isEqualTo(HWIA + "\nssl: true\naddress_aliases:\n- x@y.z\n");
        assertThat(values.get("email_comment_replies")).isEqualTo(HWIA + " {}\n");
        assertThat(values.get("ai_prompts")).isEqualTo(HWIA + "\nabout_my_business: |-\n  line one\n  line two\n");
    }

    @Test
    void settingsUpdateRewritesOnlyChangedRowsAndRequiresRoot() throws Exception {
        jdbcTemplate.update("INSERT INTO settings (name, value, created_at, updated_at) VALUES "
            + "('host', E'--- old.example\\n', '2026-01-01', '2026-01-01'), "
            + "('locale', E'--- en\\n', '2026-01-01', '2026-01-01')");

        mockMvc.perform(adminJson(put("/api/v1/admin/settings"))
                .content("{\"settings\":{\"host\":\"new.example\",\"locale\":\"en\",\"compound_address\":\"0\"}}"))
            .andExpect(status().isFound());

        assertThat(count("settings")).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT value FROM settings WHERE name = 'host'", String.class))
            .isEqualTo("--- new.example\n");
        assertThat(jdbcTemplate.queryForObject("SELECT updated_at FROM settings WHERE name = 'host'", Timestamp.class))
            .isAfter(Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")));
        assertThat(jdbcTemplate.queryForObject("SELECT updated_at FROM settings WHERE name = 'locale'",
            Timestamp.class))
            .isEqualTo(Timestamp.valueOf("2026-01-01 00:00:00"));
        assertThat(jdbcTemplate.queryForObject("SELECT value FROM settings WHERE name = 'compound_address'",
            String.class))
            .isEqualTo("--- false\n");

        for (String body : List.of("{}", "{\"settings\":{}}", "")) {
            mockMvc.perform(adminJson(put("/api/v1/admin/settings")).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("param is missing or the value is empty or invalid: settings"));
        }
        assertThat(count("settings")).isEqualTo(3);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder) {
        return builder.contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder adminJson(MockHttpServletRequestBuilder builder) {
        return json(builder).header(HttpHeaders.AUTHORIZATION, adminBearer);
    }

    private long insertGroup(String name) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO groups (name, created_at, updated_at) VALUES (?, now(), now()) RETURNING id", Long.class,
                name);
    }

    private long insertTag(String name) {
        return jdbcTemplate.queryForObject("INSERT INTO tags (name, taggings_count) VALUES (?, 0) RETURNING id",
            Long.class, name);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private User user(String username, boolean isAdmin) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setFirstName(username);
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
        for (String table : List.of("versions", "taggings", "groups_users", "settings", "research_tools", "tags",
            "groups", "users")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }
}
