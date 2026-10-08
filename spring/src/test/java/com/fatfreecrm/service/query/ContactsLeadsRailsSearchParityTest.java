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

@Transactional
class ContactsLeadsRailsSearchParityTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private JsonNode contactsMatrix;
    private JsonNode leadsMatrix;
    private String bearer;

    @BeforeEach
    void loadMatrices() throws Exception {
        contactsMatrix = readMatrix("/search/contacts_search_matrix.json");
        leadsMatrix = readMatrix("/search/leads_search_matrix.json");
        clearCorpus();
        insertCorpus(contactsMatrix, List.of(
            "users", "accounts", "contacts", "account_contacts", "tags", "taggings"));
        User alice = userRepository.findById(9001L).orElseThrow();
        bearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
    }

    @Test
    void contactsAndLeadsMatchEveryRailsCase() throws Exception {
        assertMatrixMatches(contactsMatrix, "/api/v1/contacts", false);
        clearCorpus();
        insertCorpus(leadsMatrix, List.of("users", "campaigns", "leads", "tags", "taggings"));
        User alice = userRepository.findById(9001L).orElseThrow();
        bearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
        assertMatrixMatches(leadsMatrix, "/api/v1/leads", true);
    }

    private JsonNode readMatrix(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            return JSON.readTree(input);
        }
    }

    private void clearCorpus() {
        jdbcTemplate.update("DELETE FROM preferences");
        for (String table : List.of(
            "taggings", "tags", "contact_opportunities", "account_contacts", "leads", "contacts", "accounts",
            "campaigns", "users")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    private void insertCorpus(JsonNode matrix, List<String> tables) throws Exception {
        JsonNode corpus = matrix.get("corpus");
        for (String table : tables) {
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

    private void assertMatrixMatches(JsonNode matrix, String path, boolean checkFacets) throws Exception {
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
            if (sessionFilter != null && !sessionFilter.isNull()) {
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
            var request = get(path).header(HttpHeaders.AUTHORIZATION, bearer);
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
                if (checkFacets) {
                    JsonNode facets = body.get("facets").get("status");
                    for (Map.Entry<String, JsonNode> entry : testCase.get("facets").properties()) {
                        assertThat(facets.get(entry.getKey()).asLong())
                            .as("lead facet %s for case %s", entry.getKey(), name)
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
        }
    }
}
