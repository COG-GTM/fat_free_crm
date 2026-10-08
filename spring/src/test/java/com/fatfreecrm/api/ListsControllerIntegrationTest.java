package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.json.JsonCompareMode;

/**
 * AB-272 {@code ListsController}: Rails {@code ListsController} parity — forced owner unless
 * {@code is_global == "1"}, case-insensitive name upsert per owner, presence validation, destroy
 * without ownership check (mirrored Rails gap) and no PaperTrail rows.
 */
class ListsControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private User bob;
    private String aliceBearer;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice");
        bob = user("bob");
        aliceBearer = bearer(alice);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void writeRoutesRequireAuthentication() throws Exception {
        long id = list("Bob list", bob, "/tasks");
        mockMvc.perform(post("/api/v1/lists").contentType(MediaType.APPLICATION_JSON)
            .content(body("x", "/x", null))).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/lists/" + id)).andExpect(status().isUnauthorized());
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void createForcesCurrentUserAsOwnerWhenNotGlobal() throws Exception {
        String response = mockMvc.perform(authed(post("/api/v1/lists"))
                .content("{\"list\":{\"name\":\"My accounts\",\"url\":\"/accounts?q=1\",\"user_id\":"
                    + bob.getId() + "}}"))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/api/v1/lists/")))
            .andExpect(jsonPath("$.name").value("My accounts"))
            .andExpect(jsonPath("$.url").value("/accounts?q=1"))
            .andExpect(jsonPath("$.user_id").value(alice.getId().intValue()))
            .andReturn().getResponse().getContentAsString();
        long id = JSON.readTree(response).path("id").asLong();
        Map<String, Object> row = row(id);
        assertThat(row.get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM versions", Long.class)).isZero();
    }

    @Test
    void globalCreateKeepsRequestedOwnerOrNull() throws Exception {
        long global = create(body("Everyone", "/leads", "1"));
        assertThat(row(global).get("user_id")).isNull();

        long bobs = create("{\"list\":{\"name\":\"Bob via alice\",\"url\":\"/leads\",\"user_id\":"
            + bob.getId() + "},\"is_global\":\"1\"}");
        assertThat(row(bobs).get("user_id")).isEqualTo(bob.getId().intValue());

        long notGlobal = create(body("Flag off", "/leads", "0"));
        assertThat(row(notGlobal).get("user_id")).isEqualTo(alice.getId().intValue());
    }

    @Test
    void createUpsertsByCaseInsensitiveNameAndOwner() throws Exception {
        long existing = list("Hot Leads", alice, "/leads?old=1");
        long bobsSameName = list("hot leads", bob, "/leads?bob=1");

        long updated = create(body("HOT LEADS", "/leads?new=1", null));
        assertThat(updated).isEqualTo(existing);
        assertThat(count()).isEqualTo(2);
        Map<String, Object> row = row(existing);
        assertThat(row.get("name")).isEqualTo("HOT LEADS");
        assertThat(row.get("url")).isEqualTo("/leads?new=1");
        assertThat(row(bobsSameName).get("url")).isEqualTo("/leads?bob=1");

        long fresh = create(body("Hot Leads", "/leads?global=1", "1"));
        assertThat(fresh).isNotEqualTo(existing).isNotEqualTo(bobsSameName);
        assertThat(row(fresh).get("user_id")).isNull();
        assertThat(create(body("hot LEADS", "/leads?global=2", "1"))).isEqualTo(fresh);
        assertThat(count()).isEqualTo(3);
    }

    @Test
    void blankNameAndUrlReturnRailsErrorsInDeclarationOrder() throws Exception {
        mockMvc.perform(authed(post("/api/v1/lists")).content(body("  ", "", null)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json(
                "{\"errors\":{\"name\":[\"can't be blank\"],\"url\":[\"can't be blank\"]}}",
                JsonCompareMode.STRICT));
        mockMvc.perform(authed(post("/api/v1/lists")).content("{\"list\":{\"name\":\"Only name\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"url\":[\"can't be blank\"]}}", JsonCompareMode.STRICT));
        mockMvc.perform(authed(post("/api/v1/lists")).content("{\"list\":null}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name[0]").value("can't be blank"));
        assertThat(count()).isZero();
    }

    @Test
    void destroyDeletesAnyUsersListMirroringRailsAndMissingIsNotFound() throws Exception {
        long bobs = list("Bob list", bob, "/tasks");
        mockMvc.perform(authed(delete("/api/v1/lists/" + bobs))).andExpect(status().isNoContent());
        assertThat(count()).isZero();
        mockMvc.perform(authed(delete("/api/v1/lists/" + bobs))).andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM versions", Long.class)).isZero();
    }

    private long create(String body) throws Exception {
        String response = mockMvc.perform(authed(post("/api/v1/lists")).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).path("id").asLong();
    }

    private static String body(String name, String url, String isGlobal) {
        return "{\"list\":{\"name\":\"" + name + "\",\"url\":\"" + url + "\"}"
            + (isGlobal == null ? "" : ",\"is_global\":\"" + isGlobal + "\"") + "}";
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, aliceBearer).contentType(MediaType.APPLICATION_JSON);
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM lists WHERE id = ?", id);
    }

    private long count() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM lists", Long.class);
    }

    private long list(String name, User owner, String url) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO lists (name, url, user_id, created_at, updated_at) VALUES (?, ?, ?, now(), now())"
                + " RETURNING id", Long.class, name, url, owner == null ? null : owner.getId());
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(false);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM lists");
        jdbcTemplate.update("DELETE FROM users");
    }
}
