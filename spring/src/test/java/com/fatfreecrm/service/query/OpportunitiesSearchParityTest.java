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
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class OpportunitiesSearchParityTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> CORPUS_TABLES = List.of(
        "users", "accounts", "contacts", "campaigns", "opportunities", "account_opportunities",
        "contact_opportunities", "tags", "taggings");
    private static final Set<String> KNOWN_RAILS_DEFECT_CASES =
        Set.of("preference_weighted_sort", "query_tag_positive");

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
        try (InputStream input = getClass().getResourceAsStream("/search/opportunities_search_matrix.json")) {
            matrix = JSON.readTree(input);
        }
        clearCorpus();
        jdbcTemplate.update("DELETE FROM settings WHERE name = 'opportunity_stage'");
        for (String table : CORPUS_TABLES) {
            String rows = JSON.writeValueAsString(matrix.path("corpus").path(table));
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

    @AfterEach
    void cleanCorpus() {
        clearCorpus();
        jdbcTemplate.update("DELETE FROM settings WHERE name = 'opportunity_stage'");
    }

    @Test
    void everyCaseAndShowMatchesRails() throws Exception {
        int checked = 0;
        for (JsonNode testCase : matrix.path("cases")) {
            List<String[]> params = parameters(testCase.path("params"));
            JsonNode sessionFilter = testCase.get("session_filter");
            if (sessionFilter != null && !sessionFilter.isNull()) {
                params.add(new String[] {"stage", sessionFilter.asText()});
            }

            List<String> preferenceNames = insertPreferences(testCase.path("preferences"));
            JsonNode settings = testCase.path("settings");
            if (settings.isObject()) {
                jdbcTemplate.update(
                    "INSERT INTO settings (name, value) VALUES ('opportunity_stage', ?)",
                    settings.path("opportunity_stage").asText());
            }

            var request = get("/api/v1/opportunities").header(HttpHeaders.AUTHORIZATION, bearer);
            for (String[] param : params) {
                request = request.param(param[0], param[1]);
            }
            MvcResult result = mockMvc.perform(request).andReturn();
            String name = testCase.path("name").asText();
            int springStatus = result.getResponse().getStatus();
            if ("preference_weighted_sort".equals(name)) {
                assertThat(testCase.path("status").asInt()).as("Rails status for case %s", name).isEqualTo(500);
                assertThat(springStatus).as("Spring status for case %s", name).isEqualTo(200);
            } else {
                assertThat(springStatus)
                    .as("status for case %s", name)
                    .isEqualTo(testCase.path("status").asInt());
            }
            if (springStatus == 200) {
                JsonNode body = JSON.readTree(result.getResponse().getContentAsString());

                List<Long> actualIds = new ArrayList<>();
                body.path("items").forEach(item -> actualIds.add(item.path("id").asLong()));
                List<Long> expectedIds = new ArrayList<>();
                testCase.path("ids").forEach(id -> expectedIds.add(id.asLong()));
                if (!"preference_weighted_sort".equals(name)) {
                    assertThat(body.path("totalCount").asLong())
                        .as("total for case %s", name)
                        .isEqualTo(testCase.path("total").asLong());
                }
                if ("preference_weighted_sort".equals(name)) {
                    assertThat(actualIds).as("weighted-sort order")
                        .containsExactly(9674L, 9672L, 9671L, 9673L);
                } else if ("query_tag_positive".equals(name)) {
                    List<Long> opportunityIds = new ArrayList<>();
                    matrix.path("corpus").path("opportunities")
                        .forEach(opportunity -> opportunityIds.add(opportunity.path("id").asLong()));
                    assertThat(expectedIds).as("Rails tag-query IDs retain the joined tagging ID defect")
                        .noneMatch(opportunityIds::contains);
                    assertThat(actualIds).as("Spring tag-query returns the matching opportunity")
                        .containsExactly(9661L);
                } else {
                    if (testCase.path("ordered").asBoolean()) {
                        assertThat(actualIds).as("ids for case %s", name).isEqualTo(expectedIds);
                    } else {
                        assertThat(actualIds).as("ids for case %s", name)
                            .containsExactlyInAnyOrderElementsOf(expectedIds);
                    }
                }

                if (!"preference_weighted_sort".equals(name)) {
                    JsonNode expectedFacets = testCase.path("facets");
                    JsonNode actualFacets = body.path("facets").path("stage");
                    assertThat(fieldNames(actualFacets))
                        .as("facet key order for case %s", name)
                        .isEqualTo(fieldNames(expectedFacets));
                    assertThat(actualFacets).as("facets for case %s", name).isEqualTo(expectedFacets);
                }
            }

            deletePreferences(preferenceNames);
            if (settings.isObject()) {
                jdbcTemplate.update("DELETE FROM settings WHERE name = 'opportunity_stage'");
            }
            checked++;
        }
        assertThat(KNOWN_RAILS_DEFECT_CASES)
            .containsExactlyInAnyOrder("preference_weighted_sort", "query_tag_positive");
        assertThat(checked).isEqualTo(36);

        for (JsonNode show : matrix.path("shows")) {
            MvcResult result = mockMvc.perform(get("/api/v1/opportunities/{id}", show.path("id").asLong())
                    .header(HttpHeaders.AUTHORIZATION, bearer))
                .andReturn();
            assertThat(result.getResponse().getStatus())
                .as("show status for id %s", show.path("id").asLong())
                .isEqualTo(show.path("status").asInt());
            JsonNode actual = JSON.readTree(result.getResponse().getContentAsString());
            assertThat(actual)
                .as("show body for id %s", show.path("id").asLong())
                .isEqualTo(show.path("body"));
        }
    }

    private List<String[]> parameters(JsonNode parameters) {
        List<String[]> result = new ArrayList<>();
        parameters.properties().forEach(entry -> {
            if (entry.getValue().isArray()) {
                entry.getValue().forEach(value -> result.add(new String[] {entry.getKey(), value.asText()}));
            } else {
                result.add(new String[] {entry.getKey(), entry.getValue().asText()});
            }
        });
        return result;
    }

    private List<String> insertPreferences(JsonNode preferences) {
        List<String> names = new ArrayList<>();
        if (preferences.isObject()) {
            preferences.properties().forEach(preference -> {
                names.add(preference.getKey());
                jdbcTemplate.update(
                    "INSERT INTO preferences (user_id, name, value, created_at, updated_at) "
                        + "VALUES (9001, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    preference.getKey(),
                    RailsBase64.encode64(preference.getValue().toString())
                );
            });
        }
        return names;
    }

    private void deletePreferences(List<String> names) {
        if (!names.isEmpty()) {
            String placeholders = String.join(", ", java.util.Collections.nCopies(names.size(), "?"));
            jdbcTemplate.update(
                "DELETE FROM preferences WHERE user_id = 9001 AND name IN (" + placeholders + ")",
                names.toArray()
            );
        }
    }

    private List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void clearCorpus() {
        jdbcTemplate.update("DELETE FROM settings WHERE name = 'opportunity_stage'");
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM preferences");
        jdbcTemplate.update("DELETE FROM taggings");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM account_opportunities");
        jdbcTemplate.update("DELETE FROM contact_opportunities");
        jdbcTemplate.update("DELETE FROM account_contacts");
        jdbcTemplate.update("DELETE FROM opportunities");
        jdbcTemplate.update("DELETE FROM contacts");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM campaigns");
        jdbcTemplate.update("DELETE FROM users");
    }
}
