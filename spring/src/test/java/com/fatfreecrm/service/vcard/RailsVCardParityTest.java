package com.fatfreecrm.service.vcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class RailsVCardParityTest extends AbstractPostgresIntegrationTest {

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
    void loadCorpus() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/vcard/rails_vcard_matrix.json")) {
            matrix = JSON.readTree(input);
        }
        clearCorpus();
        JsonNode corpus = matrix.get("corpus");
        for (String table : List.of("users", "accounts", "contacts", "account_contacts", "leads", "addresses")) {
            String rows = JSON.writeValueAsString(corpus.get(table));
            jdbcTemplate.execute(
                "INSERT INTO " + table + " SELECT * FROM json_populate_recordset(null::" + table + ", ?::json)",
                (PreparedStatement statement) -> {
                    statement.setString(1, rows);
                    statement.execute();
                    return null;
                });
        }
        User alice = userRepository.findById(9901L).orElseThrow();
        bearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
    }

    @Test
    void everyVCardResponseMatchesRailsStatusHeadersAndExactBytes() throws Exception {
        for (JsonNode testCase : matrix.get("cases")) {
            MvcResult result = mockMvc.perform(get("/api/v1" + testCase.get("path").asText())
                    .header(HttpHeaders.AUTHORIZATION, bearer))
                .andReturn();
            String name = testCase.get("name").asText();
            assertThat(result.getResponse().getStatus())
                .as("status for %s", name)
                .isEqualTo(testCase.get("status").asInt());
            assertThat(result.getResponse().getContentType())
                .as("content type for %s", name)
                .isEqualTo(testCase.get("content_type").asText());
            assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .as("content disposition for %s", name)
                .isEqualTo(testCase.get("content_disposition").asText());
            assertThat(result.getResponse().getContentAsByteArray())
                .as("vCard bytes for %s", name)
                .isEqualTo(testCase.get("body").asText().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void clearCorpus() {
        for (String table : List.of(
            "versions", "preferences", "taggings", "tags", "contact_opportunities", "account_contacts", "addresses",
            "leads", "contacts", "opportunities", "campaigns", "accounts", "users")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }
}
