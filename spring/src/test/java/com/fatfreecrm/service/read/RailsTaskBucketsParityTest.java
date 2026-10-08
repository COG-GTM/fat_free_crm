package com.fatfreecrm.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.SettingRepository;
import com.fatfreecrm.repository.TaskRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.security.authz.AccessPolicy;
import com.fatfreecrm.service.json.RailsJsonWriter;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.query.SearchableEntities;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs every case of {@code search/tasks_search_matrix.json} (Rails {@code /tasks.json} buckets on a frozen clock
 * per session time zone, {@code /tasks/:id.json}, {@code /tasks/auto_complete.json}) against the Spring task
 * reads on the corpus the Rails task seeded, comparing full JSON bodies.
 */
@Transactional
class RailsTaskBucketsParityTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern TIMESTAMP =
        Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(Z|[+-]\\d{2}:\\d{2})");

    /** Rails 404s rows outside {@code Task.tracked_by}; Spring answers 403 when the AB-268 policy also denies. */
    private static final Map<String, Integer> SPRING_STATUS = Map.of("show_out_of_scope", 403);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private SettingRepository settingRepository;

    @Autowired
    private AccessPolicy accessPolicy;

    @Autowired
    private RailsJsonWriter jsonWriter;

    @Autowired
    private RailsResources railsResources;

    @Autowired
    private SearchableEntities searchableEntities;

    private JsonNode matrix;

    @BeforeEach
    void loadCorpus() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/search/tasks_search_matrix.json")) {
            matrix = JSON.readTree(input);
        }
        jdbcTemplate.update("DELETE FROM settings WHERE name IN ('task_bucket', 'task_completed')");
        for (String table : List.of("versions", "comments", "tasks", "users")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        JsonNode corpus = matrix.get("corpus");
        for (String table : List.of("users", "tasks")) {
            String rows = JSON.writeValueAsString(corpus.get(table));
            jdbcTemplate.execute(
                "INSERT INTO " + table + " SELECT * FROM json_populate_recordset(null::" + table + ", ?::json)",
                (PreparedStatement statement) -> {
                    statement.setString(1, rows);
                    statement.execute();
                    return null;
                });
        }
    }

    @Test
    void everyCaseMatchesRails() throws Exception {
        Instant now = Instant.parse(matrix.get("now").asText());
        TaskReadService frozen = new TaskReadService(taskRepository, settingRepository, accessPolicy, jsonWriter,
            railsResources, searchableEntities, Clock.fixed(now, ZoneOffset.UTC), "UTC");
        Map<Long, String> bearers = new HashMap<>();
        int checked = 0;
        for (JsonNode testCase : matrix.get("cases")) {
            String name = testCase.get("name").asText();
            long userId = testCase.get("user_id").asLong();
            String path = testCase.get("path").asText();
            JsonNode params = testCase.get("params");
            JsonNode actual;
            int status;
            if (path.equals("/tasks.json")) {
                User user = userRepository.findById(userId).orElseThrow();
                AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getUsername(), false);
                String view = params.has("view") ? params.get("view").asText() : null;
                ZoneId zone = ZoneId.of(testCase.get("time_zone").asText());
                actual = JSON.readTree(JSON.writeValueAsString(frozen.buckets(principal, view, zone)));
                status = 200;
            } else {
                String bearer = bearers.computeIfAbsent(userId, id ->
                    "Bearer " + jwtTokenService.issue(userRepository.findById(id).orElseThrow()).accessToken());
                String springPath = path.equals("/tasks/auto_complete.json")
                    ? "/api/v1/tasks/autocomplete"
                    : "/api/v1" + path.replace(".json", "");
                var request = get(springPath).header(HttpHeaders.AUTHORIZATION, bearer);
                for (Map.Entry<String, JsonNode> entry : params.properties()) {
                    request = request.param(entry.getKey(), entry.getValue().asText());
                }
                MvcResult result = mockMvc.perform(request).andReturn();
                status = result.getResponse().getStatus();
                actual = status == 200 ? JSON.readTree(result.getResponse().getContentAsString()) : null;
            }
            assertThat(status).as("status for case %s", name)
                .isEqualTo(SPRING_STATUS.getOrDefault(name, testCase.get("status").asInt()));
            if (status == 200) {
                assertThat(instants(actual)).as("body for case %s", name)
                    .isEqualTo(instants(testCase.get("body")));
            }
            checked++;
        }
        assertThat(checked).isEqualTo(27);
    }

    @Test
    void railsRendersSessionZoneOffsetsForTheSameInstantsSpringRendersInUtc() {
        JsonNode body = null;
        for (JsonNode testCase : matrix.get("cases")) {
            if (testCase.get("name").asText().equals("alice_pending_utc_minus3")) {
                body = testCase.get("body");
            }
        }
        assertThat(body).isNotNull();
        JsonNode weeklySync = null;
        for (JsonNode task : body.get("overdue")) {
            if (task.get("id").asLong() == 9613) {
                weeklySync = task;
            }
        }
        assertThat(weeklySync).isNotNull();
        assertThat(weeklySync.get("due_at").asText()).isEqualTo("2026-02-20T06:00:00.000-03:00");
    }

    /**
     * Rails renders {@code TimeWithZone} in the session {@code Time.zone}; Spring always renders UTC ({@code Z}).
     * Timestamps are compared as instants so the zone cases check bucket membership, order and every other field.
     */
    private static JsonNode instants(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = JSON.createObjectNode();
            node.properties().forEach(entry -> copy.set(entry.getKey(), instants(entry.getValue())));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JSON.createArrayNode();
            node.forEach(item -> copy.add(instants(item)));
            return copy;
        }
        if (node.isTextual() && TIMESTAMP.matcher(node.asText()).matches()) {
            return JSON.getNodeFactory().textNode(OffsetDateTime.parse(node.asText()).toInstant().toString());
        }
        return node;
    }
}
