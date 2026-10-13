package com.fatfreecrm.service.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.TaskRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link RailsRowAttributes} feeds PaperTrail {@code object}/{@code object_changes} YAML straight from the row,
 * so it must reproduce ActiveRecord {@code attributes}: column order, no {@code custom_fields}, Rails types.
 */
class RailsRowAttributesIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Pattern ROOT_KEY = Pattern.compile("(?m)^([A-Za-z_][A-Za-z0-9_]*):");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RailsRowAttributes rowAttributes;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private String aliceBearer;
    private long taskId;

    @BeforeEach
    void seed() {
        clearData();
        alice = new User();
        alice.setUsername("row-attrs-alice");
        alice.setEmail("row-attrs-alice@example.test");
        alice.setEncryptedPassword("encrypted");
        alice.setPasswordSalt("salt");
        alice.setAdmin(false);
        alice.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        alice = userRepository.saveAndFlush(alice);
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
        taskId = jdbcTemplate.queryForObject(
            "INSERT INTO tasks (user_id, name, bucket, category, priority, due_at, subscribed_users, created_at, updated_at) "
                + "VALUES (?, 'Call Acme', 'due_asap', 'call', NULL, '2025-05-06 07:08:09', "
                + "'---\n- 1\n- 2\n', '2025-01-02 03:04:05.123456', '2025-01-02 03:04:05.123456') RETURNING id",
            Long.class, alice.getId().intValue());
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void readsColumnsInTableOrderWithoutCustomFieldsUsingRailsTypes() {
        Map<String, Object> attributes = rowAttributes.read("tasks", taskId);

        assertThat(new ArrayList<>(attributes.keySet())).isEqualTo(columns("tasks"));
        assertThat(attributes).doesNotContainKey("custom_fields");
        assertThat(attributes.get("id")).isEqualTo(taskId);
        assertThat(attributes.get("name")).isEqualTo("Call Acme");
        assertThat(attributes.get("priority")).isNull();
        assertThat(attributes.get("completed_at")).isNull();
        assertThat(attributes.get("due_at")).isEqualTo(Instant.parse("2025-05-06T07:08:09Z"));
        assertThat(attributes.get("created_at")).isEqualTo(Instant.parse("2025-01-02T03:04:05.123456Z"));
        assertThat(attributes.get("subscribed_users")).isEqualTo(List.of(1L, 2L));
    }

    @Test
    void emptySubscribedUsersBecomesNilLikeRailsSerialize() {
        jdbcTemplate.update("UPDATE tasks SET subscribed_users = '--- []\n' WHERE id = ?", taskId);
        assertThat(rowAttributes.read("tasks", taskId).get("subscribed_users")).isNull();

        jdbcTemplate.update("UPDATE tasks SET subscribed_users = NULL WHERE id = ?", taskId);
        assertThat(rowAttributes.read("tasks", taskId).get("subscribed_users")).isNull();
    }

    @Test
    void readsByEntityAndResolvesDefaultsForTheMappedTable() {
        Task task = taskRepository.findById(taskId).orElseThrow();

        assertThat(rowAttributes.read(task)).isEqualTo(rowAttributes.read("tasks", taskId));
        assertThat(rowAttributes.defaults(task)).isEqualTo(rowAttributes.defaults("tasks"));
        assertThat(rowAttributes.defaults("users")).containsKey("admin");
    }

    @Test
    void rejectsUnsupportedTablesMissingRowsAndUnmappedEntities() {
        assertThatThrownBy(() -> rowAttributes.read("settings", 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported Rails table settings");
        assertThatThrownBy(() -> rowAttributes.read("tasks; DROP TABLE tasks", 1L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rowAttributes.read("tasks", taskId + 1_000_000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No persisted row in tasks");
        assertThatThrownBy(() -> rowAttributes.read("not-an-entity"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No Rails table for java.lang.String");
        assertThatThrownBy(() -> rowAttributes.defaults(new Object()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void taskUpdateVersionObjectListsKeysInRailsColumnOrder() throws Exception {
        mockMvc.perform(put("/api/v1/tasks/" + taskId)
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"task\":{\"name\":\"Call Acme again\"}}"))
            .andExpect(status().isNoContent());

        String object = jdbcTemplate.queryForObject(
            "SELECT object FROM versions WHERE item_type = 'Task' AND item_id = ? AND event = 'update' "
                + "ORDER BY id DESC LIMIT 1", String.class, (int) taskId);

        assertThat(rootKeys(object)).isEqualTo(columns("tasks"));
        assertThat(object).contains("name: Call Acme\n").doesNotContain("custom_fields");
    }

    @Test
    void commentOnTaskRecordsCommentCreateBeforeTaskSubscriptionUpdate() throws Exception {
        mockMvc.perform(post("/api/v1/comments")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":{\"commentable_type\":\"Task\",\"commentable_id\":" + taskId
                    + ",\"comment\":\"Following up\"}}"))
            .andExpect(status().isCreated());

        List<Map<String, Object>> versions = jdbcTemplate.queryForList(
            "SELECT item_type, event, object_changes FROM versions WHERE (item_type = 'Comment') "
                + "OR (item_type = 'Task' AND item_id = ?) ORDER BY id", (int) taskId);

        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("item_type")).isEqualTo("Comment");
        assertThat(versions.get(0).get("event")).isEqualTo("create");
        assertThat(versions.get(1).get("item_type")).isEqualTo("Task");
        assertThat(versions.get(1).get("event")).isEqualTo("update");
        // Rails object_changes also lists subscribed_users ([1, 2] -> [1, 2, alice]); the Spring
        // port currently snapshots the live list before/after so that diff is missing (suspected
        // defect, reported separately). Only the persisted row is pinned here.
        String subscribed = jdbcTemplate.queryForObject(
            "SELECT subscribed_users FROM tasks WHERE id = ?", String.class, (int) taskId);
        assertThat(subscribed).isEqualTo("---\n- 1\n- 2\n- " + alice.getId() + "\n");
    }

    private List<String> columns(String table) {
        List<String> columns = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = ? "
                + "AND column_name <> 'custom_fields' ORDER BY ordinal_position", String.class, table);
        return columns;
    }

    private static List<String> rootKeys(String yaml) {
        List<String> keys = new ArrayList<>();
        Matcher matcher = ROOT_KEY.matcher(yaml);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM users");
    }
}
