package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * AB-273 audit: {@code CommentWriteService.create} must write the Comment {@code create} version before
 * the {@code update} version produced by the commentable save in {@code subscribe_user_to_entity}, mirroring
 * Rails where that {@code after_create} callback runs after PaperTrail's own {@code after_create} hook.
 */
class TaskCommentVersionOrderIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private String aliceBearer;
    private long taskId;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice");
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
        taskId = jdbcTemplate.queryForObject(
            // A due_today task saved yesterday: Rails' before_save set_due_date moves due_at to today's
            // midnight on commentable.save, which is the non-ignored change that yields the Task version
            // (subscribed_users itself is in PaperTrail's ignore list).
            "INSERT INTO tasks (user_id, name, bucket, due_at, created_at, updated_at)"
                + " VALUES (?, 'Version order task', 'due_today',"
                + " date_trunc('day', now() AT TIME ZONE 'UTC') - interval '1 day', now(), now()) RETURNING id",
            Long.class, alice.getId());
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void commentCreateVersionPrecedesTheCommentableSubscriptionUpdateVersion() throws Exception {
        mockMvc.perform(post("/api/v1/comments")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":{\"commentable_type\":\"Task\",\"commentable_id\":" + taskId
                    + ",\"comment\":\"ordered\"}}"))
            .andExpect(status().isCreated());

        List<Map<String, Object>> versions = jdbcTemplate.queryForList(
            "SELECT item_type, item_id, event, whodunnit, object_changes FROM versions ORDER BY id");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("item_type")).isEqualTo("Comment");
        assertThat(versions.get(0).get("event")).isEqualTo("create");
        assertThat(versions.get(0).get("whodunnit")).isEqualTo(String.valueOf(alice.getId()));
        assertThat(versions.get(1).get("item_type")).isEqualTo("Task");
        assertThat(((Number) versions.get(1).get("item_id")).longValue()).isEqualTo(taskId);
        assertThat(versions.get(1).get("event")).isEqualTo("update");
        assertThat(String.valueOf(versions.get(1).get("object_changes")))
            .contains("due_at").doesNotContain("subscribed_users");

        String subscribed = jdbcTemplate.queryForObject(
            "SELECT subscribed_users FROM tasks WHERE id = ?", String.class, taskId);
        assertThat(subscribed).isEqualTo("---\n- " + alice.getId() + "\n");
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(false);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM users");
    }
}
