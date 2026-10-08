package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rails {@code Field} declares {@code serialize :collection, type: Array} and
 * {@code serialize :settings, type: HashWithIndifferentAccess}, so a row whose serialized columns are
 * NULL (or the empty YAML document Rails writes for empty values) renders as {@code []} / {@code {}}
 * in {@code GET /admin/fields/:id.json}. The admin read API must match that rather than emit null.
 */
class AdminFieldSerializationDefaultsIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant AT = Instant.parse("2024-01-02T03:04:05Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private String adminBearer;

    @BeforeEach
    void seed() {
        clearData();
        User admin = new User();
        admin.setUsername("admin");
        admin.setEmail("admin@example.test");
        admin.setFirstName("Admin");
        admin.setEncryptedPassword("encrypted");
        admin.setPasswordSalt("salt");
        admin.setAdmin(true);
        admin.setConfirmedAt(AT);
        adminBearer = "Bearer " + jwtTokenService.issue(userRepository.saveAndFlush(admin)).accessToken();
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void nullSerializedColumnsRenderAsEmptyArrayAndEmptyHash() throws Exception {
        insertField(1, null, null);

        JsonNode field = show(1);

        assertThat(field.path("collection").isArray()).as("collection %s", field.path("collection")).isTrue();
        assertThat(field.path("collection")).isEmpty();
        assertThat(field.path("settings").isObject()).as("settings %s", field.path("settings")).isTrue();
        assertThat(field.path("settings")).isEmpty();
    }

    @Test
    void emptyYamlDocumentsRenderAsEmptyArrayAndEmptyHash() throws Exception {
        insertField(2, "--- []\n", "--- {}\n");
        insertField(3, "---\n", "--- !ruby/hash:ActiveSupport::HashWithIndifferentAccess {}\n");

        for (int id = 2; id <= 3; id++) {
            JsonNode field = show(id);
            assertThat(field.path("collection").isArray()).as("collection of field %d", id).isTrue();
            assertThat(field.path("collection")).isEmpty();
            assertThat(field.path("settings").isObject()).as("settings of field %d", id).isTrue();
            assertThat(field.path("settings")).isEmpty();
        }
    }

    @Test
    void collectionPreservesOrderAndYamlScalarTypes() throws Exception {
        insertField(4, "---\n- Zeta\n- alpha\n- '42'\n- 42\n- true\n- ~\n- ''\n", "---\nmultiple: false\nmax: 3\n");

        JsonNode field = show(4);

        assertThat(field.path("collection"))
            .as("YAML.load keeps quoted scalars as strings and types unquoted ones, as Field#as_json does")
            .isEqualTo(JSON.readTree("[\"Zeta\",\"alpha\",\"42\",42,true,null,\"\"]"));
        assertThat(field.path("settings")).isEqualTo(JSON.readTree("{\"multiple\":false,\"max\":3}"));
    }

    private JsonNode show(int id) throws Exception {
        return JSON.readTree(mockMvc.perform(get("/api/v1/admin/fields/" + id)
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void insertField(int id, String collection, String settings) {
        jdbcTemplate.update(
            "INSERT INTO fields (id, position, name, label, \"as\", collection, settings, "
                + "created_at, updated_at, minlength) VALUES (?, ?, ?, ?, 'string', ?, ?, ?, ?, 0)",
            id, id, "ab270_field_" + id, "Field " + id, collection, settings,
            Timestamp.from(AT), Timestamp.from(AT));
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM fields");
        jdbcTemplate.update("DELETE FROM preferences");
        jdbcTemplate.update("DELETE FROM users");
    }
}
