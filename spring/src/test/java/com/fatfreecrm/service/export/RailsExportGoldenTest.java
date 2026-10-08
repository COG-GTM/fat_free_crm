package com.fatfreecrm.service.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.customfields.CustomFieldRegistry;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Replays the Rails exports recorded by {@code rake ffcrm:migration:export_goldens}. */
class RailsExportGoldenTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VOLATILE_TIME = "\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d UTC";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private CustomFieldRegistry customFieldRegistry;

    private JsonNode manifest;

    @BeforeEach
    void loadCorpus() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/exports/rails_export_goldens.json")) {
            manifest = JSON.readTree(input);
        }
        clearCorpus();
        for (JsonNode column : manifest.get("custom_columns")) {
            jdbcTemplate.execute("ALTER TABLE " + column.get("table").asText() + " ADD COLUMN IF NOT EXISTS "
                + column.get("column").asText() + " " + column.get("type").asText());
        }
        JsonNode corpus = manifest.get("corpus");
        corpus.fieldNames().forEachRemaining(table -> {
            String rows = corpus.get(table).toString();
            jdbcTemplate.execute(
                "INSERT INTO " + table + " SELECT * FROM json_populate_recordset(null::" + table + ", ?::json)",
                (PreparedStatement statement) -> {
                    statement.setString(1, rows);
                    statement.execute();
                    return null;
                });
        });
        for (JsonNode version : corpus.get("versions")) {
            jdbcTemplate.update("UPDATE versions SET created_at = date_trunc('second', now() AT TIME ZONE 'UTC') "
                + "- make_interval(secs => ?) WHERE id = ?",
                version.get("created_offset_seconds").asDouble(), version.get("id").asLong());
        }
        customFieldRegistry.invalidate();
    }

    @AfterEach
    void dropCorpus() {
        clearCorpus();
        for (JsonNode column : manifest.get("custom_columns")) {
            jdbcTemplate.execute("ALTER TABLE " + column.get("table").asText() + " DROP COLUMN IF EXISTS "
                + column.get("column").asText());
        }
        customFieldRegistry.invalidate();
    }

    @Test
    void everyExportMatchesRailsForAcceptHeaderFormatParameterAndSuffix() throws Exception {
        List<String> failures = new ArrayList<>();
        int compared = 0;
        for (JsonNode testCase : manifest.get("cases")) {
            String name = testCase.get("name").asText();
            boolean xls = name.endsWith("_xls");
            // MockMvc does not form-decode "+" in query strings the way Tomcat does.
            String spring = "/api/v1" + testCase.get("spring_path").asText().replace("+", "%20");
            Map<String, Function<String, MockHttpServletRequestBuilder>> styles = Map.of(
                "accept", path -> get(URI.create(path))
                    .header(HttpHeaders.ACCEPT, xls ? "application/vnd.ms-excel" : "text/csv"),
                "format", path -> get(URI.create(
                    path + (path.contains("?") ? "&" : "?") + "format=" + (xls ? "xls" : "csv"))),
                "suffix", path -> get(URI.create(suffixed(path, xls ? ".xls" : ".csv"))));
            byte[] golden = golden(testCase.get("body_file").asText());
            for (Map.Entry<String, Function<String, MockHttpServletRequestBuilder>> style : styles.entrySet()) {
                MvcResult result = mockMvc.perform(style.getValue().apply(spring)
                        .header(HttpHeaders.AUTHORIZATION, bearer(testCase.get("user").asLong())))
                    .andReturn();
                String label = name + " via " + style.getKey();
                compared++;
                if (result.getResponse().getStatus() != testCase.get("status").asInt()) {
                    failures.add(label + ": status " + result.getResponse().getStatus());
                    continue;
                }
                if (!MediaType.parseMediaType(testCase.get("content_type").asText())
                        .equals(MediaType.parseMediaType(result.getResponse().getContentType()))) {
                    failures.add(label + ": content type " + result.getResponse().getContentType());
                }
                String disposition = testCase.get("content_disposition").isNull()
                    ? null : testCase.get("content_disposition").asText();
                if (!java.util.Objects.equals(disposition,
                        result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))) {
                    failures.add(label + ": disposition " + result.getResponse().getHeader("Content-Disposition"));
                }
                String expected = normalize(name, new String(golden, StandardCharsets.UTF_8));
                String actual = normalize(name, result.getResponse().getContentAsString(StandardCharsets.UTF_8));
                if (!expected.equals(actual)) {
                    failures.add(label + ": body differs\n--- rails\n" + expected + "\n--- spring\n" + actual);
                }
            }
        }
        assertThat(compared).isEqualTo(manifest.get("cases").size() * 3);
        assertThat(failures).isEmpty();
    }

    @Test
    void jsonListsAndUnportedFormatsStayOffTheExportHandlers() throws Exception {
        String bearer = bearer(9701L);
        MvcResult json = mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer))
            .andReturn();
        assertThat(json.getResponse().getContentType()).startsWith("application/json");
        MvcResult unknownFormat = mockMvc.perform(get("/api/v1/accounts?format=atom")
                .header(HttpHeaders.AUTHORIZATION, bearer))
            .andReturn();
        assertThat(unknownFormat.getResponse().getContentType()).startsWith("application/json");
        for (String path : List.of("/api/v1/accounts.atom", "/api/v1/accounts.rss", "/api/v1/accounts.xml")) {
            assertThat(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer)).andReturn()
                .getResponse().getStatus()).as(path).isEqualTo(404);
        }
        for (String accept : List.of("application/atom+xml", "application/rss+xml", "application/xml")) {
            assertThat(mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.ACCEPT, accept)
                    .header(HttpHeaders.AUTHORIZATION, bearer)).andReturn().getResponse().getStatus())
                .as(accept).isEqualTo(406);
        }
    }

    private static String suffixed(String path, String suffix) {
        int query = path.indexOf('?');
        return query < 0 ? path + suffix : path.substring(0, query) + suffix + path.substring(query);
    }

    private static String normalize(String name, String body) {
        return name.startsWith("home_") ? body.replaceAll(VOLATILE_TIME, "<created_at>") : body;
    }

    private byte[] golden(String file) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/exports/" + file)) {
            return input.readAllBytes();
        }
    }

    private String bearer(long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private void clearCorpus() {
        List<String> tables = new ArrayList<>();
        manifest.get("corpus").fieldNames().forEachRemaining(tables::add);
        java.util.Collections.reverse(tables);
        tables.forEach(table -> jdbcTemplate.update("DELETE FROM " + table));
    }
}
