package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
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

/**
 * Account writes through the HTTP API must leave exactly the Solid Queue rows Rails'
 * {@code after_save_commit} callbacks would: {@code AccountWebsiteJob} when the website changes to a
 * present value, {@code WikidataJob} only for a Wikidata-only change, nothing otherwise — and nothing
 * at all for a rejected (unauthenticated) request.
 */
class WriteCallbacksQueueIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtTokenService jwtTokenService;

    private String aliceBearer;
    private long aliceId;

    @BeforeEach
    void seed() {
        clearData();
        User alice = new User();
        alice.setUsername("alice");
        alice.setEmail("alice@example.test");
        alice.setEncryptedPassword("enc");
        alice.setPasswordSalt("salt");
        alice.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        alice = userRepository.saveAndFlush(alice);
        aliceId = alice.getId();
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
    }

    @AfterEach
    void clearData() {
        jdbcTemplate.update("DELETE FROM solid_queue_ready_executions");
        jdbcTemplate.update("DELETE FROM solid_queue_jobs");
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void creatingAnAccountWithWebsiteAndWikidataOnlyEnqueuesWebsiteJob() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":{\"name\":\"Acme\",\"website\":\"https://acme.example.test\","
                    + "\"wikidata_id\":\"Q42\"}}"))
            .andExpect(status().isCreated());

        long accountId = jdbcTemplate.queryForObject("SELECT id FROM accounts WHERE name = 'Acme'", Long.class);
        assertThat(queuedJobs()).containsExactly(Map.of(
            "class_name", "AccountWebsiteJob",
            "queue_name", "default",
            "global_id", "gid://fat-free-crm/Account/" + accountId));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM solid_queue_ready_executions", Integer.class)).isEqualTo(1);
    }

    @Test
    void wikidataOnlyUpdateEnqueuesWikidataJobAndUnrelatedUpdatesEnqueueNothing() throws Exception {
        long accountId = account("Acme", "https://acme.example.test", null);

        mockMvc.perform(put("/api/v1/accounts/" + accountId)
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":{\"name\":\"Acme Renamed\"}}"))
            .andExpect(status().isNoContent());
        assertThat(queuedJobs()).isEmpty();

        mockMvc.perform(put("/api/v1/accounts/" + accountId)
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":{\"wikidata_id\":\"Q42\"}}"))
            .andExpect(status().isNoContent());
        assertThat(queuedJobs()).containsExactly(Map.of(
            "class_name", "WikidataJob",
            "queue_name", "default",
            "global_id", "gid://fat-free-crm/Account/" + accountId));
    }

    @Test
    void clearingWebsiteWhileSettingWikidataEnqueuesNothing() throws Exception {
        long accountId = account("Acme", "https://acme.example.test", null);

        mockMvc.perform(put("/api/v1/accounts/" + accountId)
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":{\"website\":\"\",\"wikidata_id\":\"Q42\"}}"))
            .andExpect(status().isNoContent());

        assertThat(queuedJobs()).isEmpty();
    }

    @Test
    void unauthenticatedWriteEnqueuesNothing() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":{\"name\":\"Acme\",\"website\":\"https://acme.example.test\"}}"))
            .andExpect(status().isUnauthorized());

        assertThat(queuedJobs()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class)).isZero();
    }

    private long account(String name, String website, String wikidataId) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, user_id, access, website, wikidata_id, created_at, updated_at)"
                + " VALUES (?, ?, 'Public', ?, ?, now(), now()) RETURNING id",
            Long.class, name, aliceId, website, wikidataId);
    }

    private List<Map<String, Object>> queuedJobs() {
        return jdbcTemplate.query(
            "SELECT class_name, queue_name, arguments::json -> 'arguments' -> 0 ->> '_aj_globalid' AS global_id"
                + " FROM solid_queue_jobs ORDER BY id",
            (row, index) -> Map.of(
                "class_name", row.getString("class_name"),
                "queue_name", row.getString("queue_name"),
                "global_id", row.getString("global_id")));
    }
}
