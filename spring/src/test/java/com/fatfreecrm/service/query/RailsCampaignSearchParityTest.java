package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.RailsBase64;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs every case of {@code search/campaigns_search_matrix.json} against {@code /api/v1/campaigns}
 * on the same corpus the Rails task seeded, and asserts identical status, totals, ids and facets.
 */
@Transactional
class RailsCampaignSearchParityTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private JsonNode matrix;
    private String bearer;

    @BeforeEach
    void loadCorpus() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/search/campaigns_search_matrix.json")) {
            matrix = JSON.readTree(input);
        }
        jdbcTemplate.update("DELETE FROM preferences");
        for (String table : List.of(
            "taggings", "tags", "permissions", "opportunities", "leads", "campaigns", "users")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        JsonNode corpus = matrix.get("corpus");
        for (String table : List.of(
            "users", "campaigns", "leads", "opportunities", "permissions", "tags", "taggings")) {
            String rows = JSON.writeValueAsString(corpus.get(table));
            jdbcTemplate.execute(
                "INSERT INTO " + table + " SELECT * FROM json_populate_recordset(null::" + table + ", ?::json)",
                (PreparedStatement statement) -> {
                    statement.setString(1, rows);
                    statement.execute();
                    return null;
                });
        }
        User alice = userRepository.findById(9001L).orElseThrow();
        bearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
    }

    @Test
    void everyCaseMatchesRails() throws Exception {
        int checked = 0;
        for (JsonNode testCase : matrix.get("cases")) {
            List<String[]> params = new ArrayList<>();
            for (Map.Entry<String, JsonNode> entry : testCase.get("params").properties()) {
                if (entry.getValue().isArray()) {
                    for (JsonNode value : entry.getValue()) {
                        params.add(new String[] {entry.getKey(), value.asText()});
                    }
                } else {
                    params.add(new String[] {entry.getKey(), entry.getValue().asText()});
                }
            }
            JsonNode sessionFilter = testCase.get("session_filter");
            JsonNode postStatus = testCase.get("post_status");
            if (sessionFilter != null && !sessionFilter.isNull()
                && (postStatus == null || postStatus.asInt() == 200)) {
                params.add(new String[] {"status", sessionFilter.asText()});
            }
            JsonNode preferences = testCase.get("preferences");
            List<String> preferenceNames = new ArrayList<>();
            if (preferences != null && preferences.isObject()) {
                for (Map.Entry<String, JsonNode> preference : preferences.properties()) {
                    preferenceNames.add(preference.getKey());
                    jdbcTemplate.update(
                        "INSERT INTO preferences (user_id, name, value, created_at, updated_at) "
                            + "VALUES (9001, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                        preference.getKey(),
                        RailsBase64.encode64(preference.getValue().toString())
                    );
                }
            }
            var request = get("/api/v1/campaigns").header(HttpHeaders.AUTHORIZATION, bearer);
            for (String[] param : params) {
                request = request.param(param[0], param[1]);
            }
            MvcResult result = mockMvc.perform(request).andReturn();
            String name = testCase.get("name").asText();
            assertThat(result.getResponse().getStatus())
                .as("status for case %s", name)
                .isEqualTo(testCase.get("status").asInt());
            if (result.getResponse().getStatus() == 200) {
                JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
                assertThat(body.get("totalCount").asLong())
                    .as("total for case %s", name)
                    .isEqualTo(testCase.get("total").asLong());
                List<Long> ids = new ArrayList<>();
                body.get("items").forEach(item -> ids.add(item.get("id").asLong()));
                List<Long> expected = new ArrayList<>();
                testCase.get("ids").forEach(id -> expected.add(id.asLong()));
                if (testCase.get("ordered").asBoolean()) {
                    assertThat(ids).as("ids for case %s", name).isEqualTo(expected);
                } else {
                    assertThat(ids).as("ids for case %s", name).containsExactlyInAnyOrderElementsOf(expected);
                }
                if (name.equals("default")) {
                    JsonNode facets = body.get("facets").get("status");
                    for (Map.Entry<String, JsonNode> entry : matrix.get("facets").properties()) {
                        assertThat(facets.get(entry.getKey()).asLong())
                            .as("facet %s", entry.getKey())
                            .isEqualTo(entry.getValue().asLong());
                    }
                }
            }
            if (!preferenceNames.isEmpty()) {
                String placeholders = String.join(", ", java.util.Collections.nCopies(preferenceNames.size(), "?"));
                jdbcTemplate.update(
                    "DELETE FROM preferences WHERE user_id = 9001 AND name IN (" + placeholders + ")",
                    preferenceNames.toArray()
                );
            }
            checked++;
        }
        assertThat(checked).isEqualTo(45);
    }
}
