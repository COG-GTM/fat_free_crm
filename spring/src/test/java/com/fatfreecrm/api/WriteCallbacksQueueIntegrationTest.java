package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * AB-273 callbacks observed from the HTTP write API down to the Solid Queue tables: the Rails
 * {@code after_save_commit} enrichment jobs on Account, the {@code EntityObserver} assignment mail,
 * and {@code Comment#notify_subscribers}. Pins which rows a web request leaves behind and, just as
 * importantly, which it does not (rejected writes, Integer whodunnit from web sessions).
 */
class WriteCallbacksQueueIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtTokenService jwtTokenService;
    @Autowired
    private ObjectMapper objectMapper;

    private User alice;
    private User bob;
    private String aliceBearer;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice", true);
        bob = user("bob", true);
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
    }

    @AfterEach
    void clearData() {
        jdbcTemplate.update("DELETE FROM solid_queue_ready_executions");
        jdbcTemplate.update("DELETE FROM solid_queue_jobs");
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void creatingAccountWithWebsiteEnqueuesAccountWebsiteJobForTheNewRow() throws Exception {
        long accountId = postJson("/api/v1/accounts",
            "{\"account\":{\"name\":\"Acme\",\"website\":\"https://example.test\"}}", 201).path("id").asLong();

        List<Map<String, Object>> jobs = queuedJobs();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).get("class_name")).isEqualTo("AccountWebsiteJob");
        assertThat(jobs.get(0).get("queue_name")).isEqualTo("default");
        JsonNode arguments = objectMapper.readTree((String) jobs.get(0).get("arguments"));
        assertThat(arguments.path("job_class").asText()).isEqualTo("AccountWebsiteJob");
        assertThat(arguments.path("arguments").get(0).path("_aj_globalid").asText())
            .isEqualTo("gid://fat-free-crm/Account/" + accountId);
        assertThat(readyExecutions()).isEqualTo(1);
    }

    @Test
    void creatingAccountWithoutWebsiteOrWikidataEnqueuesNothing() throws Exception {
        postJson("/api/v1/accounts", "{\"account\":{\"name\":\"Acme\"}}", 201);
        assertThat(queuedJobs()).isEmpty();
    }

    @Test
    void rejectedAccountCreateEnqueuesNothing() throws Exception {
        postJson("/api/v1/accounts", "{\"account\":{\"name\":\"\",\"website\":\"https://example.test\"}}", 422);
        assertThat(queuedJobs()).isEmpty();
        assertThat(readyExecutions()).isZero();
    }

    @Test
    void wikidataOnlyUpdateEnqueuesWikidataJob() throws Exception {
        long accountId = insertAccount("https://example.test", null);
        putJson("/api/v1/accounts/" + accountId, "{\"account\":{\"wikidata_id\":\"Q42\"}}");

        List<Map<String, Object>> jobs = queuedJobs();
        assertThat(jobs).extracting("class_name").containsExactly("WikidataJob");
        JsonNode arguments = objectMapper.readTree((String) jobs.get(0).get("arguments"));
        assertThat(arguments.path("arguments").get(0).path("_aj_globalid").asText())
            .isEqualTo("gid://fat-free-crm/Account/" + accountId);
    }

    @Test
    void websiteAndWikidataUpdateEnqueuesOnlyAccountWebsiteJob() throws Exception {
        long accountId = insertAccount("https://old.example.test", "Q1");
        putJson("/api/v1/accounts/" + accountId,
            "{\"account\":{\"website\":\"https://new.example.test\",\"wikidata_id\":\"Q42\"}}");

        assertThat(queuedJobs()).extracting("class_name").containsExactly("AccountWebsiteJob");
    }

    @Test
    void unchangedWebsiteAndWikidataEnqueueNothing() throws Exception {
        long accountId = insertAccount("https://example.test", "Q42");
        putJson("/api/v1/accounts/" + accountId,
            "{\"account\":{\"name\":\"Renamed\",\"website\":\"https://example.test\",\"wikidata_id\":\"Q42\"}}");
        putJson("/api/v1/accounts/" + accountId, "{\"account\":{\"website\":\"\"}}");

        assertThat(queuedJobs()).isEmpty();
    }

    @Test
    void webAssignmentDoesNotEnqueueAssignmentMailBecauseWebWhodunnitIsAnInteger() throws Exception {
        // Rails web requests store PaperTrail whodunnit as an Integer, so EntityObserver never
        // resolves a current user and UserMailer.assigned_entity_notification is never scheduled.
        postJson("/api/v1/accounts",
            "{\"account\":{\"name\":\"Assigned\",\"assigned_to\":" + bob.getId() + "}}", 201);
        long accountId = insertAccount(null, null);
        putJson("/api/v1/accounts/" + accountId, "{\"account\":{\"assigned_to\":" + bob.getId() + "}}");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT assigned_to FROM accounts WHERE id = ?", Long.class, accountId)).isEqualTo(bob.getId());
        assertThat(queuedJobs()).extracting("class_name").doesNotContain("ActionMailer::MailDeliveryJob");
    }

    @Test
    void commentOnSubscribedEntityEnqueuesOneSubscriptionMailPerEmailableSubscriber() throws Exception {
        User suspended = user("carol", true);
        suspended.setSuspendedAt(Instant.parse("2025-06-01T00:00:00Z"));
        userRepository.saveAndFlush(suspended);
        User optedOut = user("dave", true);
        optedOut.setSubscribeToCommentReplies(false);
        userRepository.saveAndFlush(optedOut);
        long accountId = insertAccount(null, null);
        jdbcTemplate.update("UPDATE accounts SET subscribed_users = ? WHERE id = ?",
            "---\n- " + alice.getId() + "\n- " + bob.getId() + "\n- " + suspended.getId()
                + "\n- " + optedOut.getId() + "\n- 999999\n", accountId);

        long commentId = postJson("/api/v1/comments",
            "{\"comment\":{\"commentable_type\":\"Account\",\"commentable_id\":" + accountId
                + ",\"comment\":\"Hello subscribers\"}}", 201).path("id").asLong();

        List<Map<String, Object>> jobs = queuedJobs();
        assertThat(jobs).extracting("class_name").containsExactly("ActionMailer::MailDeliveryJob");
        JsonNode arguments = objectMapper.readTree((String) jobs.get(0).get("arguments"));
        JsonNode expectedArguments = objectMapper.readTree("""
            [
              "SubscriptionMailer",
              "comment_notification",
              "deliver_now",
              {
                "_aj_ruby2_keywords":["args"],
                "args":[
                  {"_aj_globalid":"gid://fat-free-crm/User/%d"},
                  {"_aj_globalid":"gid://fat-free-crm/Comment/%d"}
                ]
              }
            ]
            """.formatted(bob.getId(), commentId));
        assertThat(arguments.path("arguments")).isEqualTo(expectedArguments);
        assertThat(readyExecutions()).isEqualTo(1);
    }

    @Test
    void commentByTheOnlySubscriberEnqueuesNothing() throws Exception {
        long accountId = insertAccount(null, null);
        jdbcTemplate.update("UPDATE accounts SET subscribed_users = ? WHERE id = ?",
            "---\n- " + alice.getId() + "\n", accountId);

        postJson("/api/v1/comments",
            "{\"comment\":{\"commentable_type\":\"Account\",\"commentable_id\":" + accountId
                + ",\"comment\":\"Talking to myself\"}}", 201);

        assertThat(queuedJobs()).isEmpty();
    }

    private JsonNode postJson(String path, String body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().is(expectedStatus))
            .andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isEmpty() ? objectMapper.nullNode() : objectMapper.readTree(content);
    }

    private void putJson(String path, String body) throws Exception {
        mockMvc.perform(put(path)
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().is2xxSuccessful());
    }

    private long insertAccount(String website, String wikidataId) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, website, wikidata_id, user_id, access, created_at, updated_at)"
                + " VALUES ('Acme', ?, ?, ?, 'Public', now(), now()) RETURNING id",
            Long.class, website, wikidataId, alice.getId());
    }

    private List<Map<String, Object>> queuedJobs() {
        return jdbcTemplate.queryForList(
            "SELECT class_name, queue_name, arguments::text AS arguments FROM solid_queue_jobs ORDER BY id");
    }

    private int readyExecutions() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM solid_queue_ready_executions", Integer.class);
    }

    private User user(String username, boolean receiveAssignedNotifications) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setEncryptedPassword("enc");
        user.setPasswordSalt("salt");
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        user.setSubscribeToCommentReplies(true);
        user.setReceiveAssignedNotifications(receiveAssignedNotifications);
        return userRepository.saveAndFlush(user);
    }
}
