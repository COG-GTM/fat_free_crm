package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AB-272 {@code EmailsController#destroy}: CanCan owner-or-admin, PaperTrail destroy row with
 * {@code related: :mediator} and {@code ignore: [:state]}.
 */
class EmailsControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private User bob;
    private User admin;
    private long accountId;
    private long aliceEmail;
    private long bobEmail;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice", false);
        bob = user("bob", false);
        admin = user("admin", true);
        accountId = jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, user_id, access, created_at, updated_at)"
                + " VALUES ('Mediator account', ?, 'Public', now(), now()) RETURNING id",
            Long.class, alice.getId());
        aliceEmail = email(alice, "<alice-1@ffcrm>", "Alice subject");
        bobEmail = email(bob, "<bob-1@ffcrm>", "Bob subject");
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void destroyRequiresAuthentication() throws Exception {
        mockMvc.perform(delete("/api/v1/emails/" + aliceEmail)).andExpect(status().isUnauthorized());
        assertThat(count("emails")).isEqualTo(2);
    }

    @Test
    void ownerDestroyDeletesRowAndRecordsDestroyVersionRelatedToMediator() throws Exception {
        mockMvc.perform(delete("/api/v1/emails/" + aliceEmail).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
            .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForList("SELECT id FROM emails ORDER BY id", Long.class))
            .containsExactly(bobEmail);
        List<Map<String, Object>> versions = jdbcTemplate.queryForList("SELECT * FROM versions ORDER BY id");
        assertThat(versions).hasSize(1);
        Map<String, Object> version = versions.get(0);
        assertThat(version.get("item_type")).isEqualTo("Email");
        assertThat(version.get("item_id")).isEqualTo((int) aliceEmail);
        assertThat(version.get("event")).isEqualTo("destroy");
        assertThat(version.get("whodunnit")).isEqualTo(alice.getId().toString());
        assertThat(version.get("related_type")).isEqualTo("Account");
        assertThat(version.get("related_id")).isEqualTo((int) accountId);
        String object = (String) version.get("object");
        assertThat(object).startsWith("---\nid: " + aliceEmail + "\nimap_message_id: \"<alice-1@ffcrm>\"\n"
            + "user_id: " + alice.getId() + "\nmediator_type: Account\nmediator_id: " + accountId + "\n");
        assertThat(object).contains("subject: Alice subject\n");
        assertThat(object).contains("state: Expanded\n");
        String changes = (String) version.get("object_changes");
        assertThat(changes).contains("subject:\n- Alice subject\n-\n");
        assertThat(changes).doesNotContain("state:");
    }

    @Test
    void nonOwnerDestroyIsForbiddenAndChangesNothing() throws Exception {
        mockMvc.perform(delete("/api/v1/emails/" + bobEmail).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
            .andExpect(status().isForbidden());
        assertThat(count("emails")).isEqualTo(2);
        assertThat(count("versions")).isZero();
    }

    @Test
    void adminMayDestroyAnyEmail() throws Exception {
        mockMvc.perform(delete("/api/v1/emails/" + bobEmail).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
            .andExpect(status().isNoContent());
        assertThat(count("emails")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT whodunnit FROM versions", String.class))
            .isEqualTo(admin.getId().toString());
    }

    @Test
    void destroyOfMissingEmailIsNotFound() throws Exception {
        mockMvc.perform(delete("/api/v1/emails/424242").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
            .andExpect(status().isNotFound());
        assertThat(count("versions")).isZero();
    }

    private long email(User owner, String imapId, String subject) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO emails (imap_message_id, user_id, mediator_type, mediator_id, sent_from, sent_to,"
                + " subject, body, state, created_at, updated_at)"
                + " VALUES (?, ?, 'Account', ?, 'from@example.com', 'to@example.com', ?, 'body', 'Expanded',"
                + " now(), now()) RETURNING id",
            Long.class, imapId, owner.getId(), accountId, subject);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private User user(String username, boolean admin) {
        User user = new User();
        user.setUsername(username);
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(admin);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM emails");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
