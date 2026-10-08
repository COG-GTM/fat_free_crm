package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.User;
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
class UsersRailsSearchParityTest extends AbstractPostgresIntegrationTest {

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
        try (InputStream input = getClass().getResourceAsStream("/search/users_search_matrix.json")) {
            matrix = JSON.readTree(input);
        }
        jdbcTemplate.update("DELETE FROM preferences");
        jdbcTemplate.update("DELETE FROM users");
        String rows = JSON.writeValueAsString(matrix.path("corpus"));
        jdbcTemplate.execute(
            "INSERT INTO users (id, username, email, first_name, last_name, admin, suspended_at, encrypted_password, "
                + "password_salt, confirmed_at, created_at, updated_at) "
                + "SELECT id, username, email, first_name, last_name, admin, suspended_at, 'matrix', 'matrix', "
                + "TIMESTAMP '2025-01-01 00:00:00', TIMESTAMP '2025-01-01 00:00:00', "
                + "TIMESTAMP '2025-01-01 00:00:00' FROM json_populate_recordset(null::users, ?::json)",
            (PreparedStatement statement) -> {
                statement.setString(1, rows);
                statement.execute();
                return null;
            });
        User admin = userRepository.findById(9600L).orElseThrow();
        bearer = "Bearer " + jwtTokenService.issue(admin).accessToken();
    }

    @Test
    void everyAdminListCaseMatchesRailsIdsAndCounts() throws Exception {
        for (JsonNode testCase : matrix.path("cases")) {
            var request = get("/api/v1/admin/users")
                .param("per_page", "200")
                .header(HttpHeaders.AUTHORIZATION, bearer);
            for (Map.Entry<String, JsonNode> entry : testCase.path("params").properties()) {
                if (entry.getValue().isArray()) {
                    for (JsonNode value : entry.getValue()) {
                        request = request.param(entry.getKey(), value.asText());
                    }
                } else {
                    request = request.param(entry.getKey(), entry.getValue().asText());
                }
            }
            MvcResult result = mockMvc.perform(request).andReturn();
            String name = testCase.path("name").asText();
            assertThat(result.getResponse().getStatus()).as("status for %s", name)
                .isEqualTo(testCase.path("status").asInt());
            JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
            assertThat(body.path("totalCount").asLong()).as("total for %s", name)
                .isEqualTo(testCase.path("total").asLong());
            List<Long> actual = new ArrayList<>();
            body.path("items").forEach(item -> actual.add(item.path("id").asLong()));
            List<Long> expected = new ArrayList<>();
            testCase.path("ids").forEach(id -> expected.add(id.asLong()));
            assertThat(actual).as("ids for %s", name).isEqualTo(expected);
        }
    }
}
