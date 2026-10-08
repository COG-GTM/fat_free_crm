package com.fatfreecrm.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.customfields.CustomFieldRegistry;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

class MetadataControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final long GROUP_ID = 991200;

    @Autowired
    private CustomFieldRegistry customFieldRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private UserRepository userRepository;

    private String aliceBearer;

    @BeforeEach
    void seed() {
        clearFixtureRows();
        User alice = new User();
        alice.setUsername("metadata_alice");
        alice.setEmail("metadata_alice@example.test");
        alice.setEncryptedPassword("encrypted");
        alice.setPasswordSalt("salt");
        alice.setConfirmedAt(java.time.Instant.parse("2025-01-01T00:00:00Z"));
        userRepository.saveAndFlush(alice);
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
    }

    @AfterEach
    void cleanUp() {
        clearFixtureRows();
    }

    @Test
    void mapsOnlyCustomFieldDefinitionsInRegistryOrder() throws Exception {
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, name, label, \"position\", created_at, updated_at) "
                + "VALUES (?, 'Account', 'details', 'Details', 2, now(), now())",
            GROUP_ID);
        insertField(991201, "CustomField", 2, "cf_priority", "Priority", null, null, "select",
            "---\n- Red\n- Blue\n", false, true, 0, 20, null,
            "--- !ruby/hash:ActiveSupport::HashWithIndifferentAccess\nenabled: true\ndisplay: compact\n");
        insertField(991202, "CoreField", 0, "name", "Name", null, null, "text", null,
            false, false, null, null, null, null);
        insertField(991203, "CustomField", 1, "cf_name", "Name", "Help", "Name", "string", null,
            false, false, null, null, null, null);
        customFieldRegistry.invalidate();

        metadata(aliceBearer, "accounts")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entity").value("accounts"))
            .andExpect(jsonPath("$.fields.length()").value(2))
            .andExpect(jsonPath("$.fields[0].id").value(991203))
            .andExpect(jsonPath("$.fields[0].type").value("CustomField"))
            .andExpect(jsonPath("$.fields[0].field_group_id").value((int) GROUP_ID))
            .andExpect(jsonPath("$.fields[0].position").value(1))
            .andExpect(jsonPath("$.fields[0].name").value("cf_name"))
            .andExpect(jsonPath("$.fields[0].label").value("Name"))
            .andExpect(jsonPath("$.fields[0].hint").value("Help"))
            .andExpect(jsonPath("$.fields[0].placeholder").value("Name"))
            .andExpect(jsonPath("$.fields[0].as").value("string"))
            .andExpect(jsonPath("$.fields[0].collection").isEmpty())
            .andExpect(jsonPath("$.fields[0].disabled").value(false))
            .andExpect(jsonPath("$.fields[0].required").value(false))
            .andExpect(jsonPath("$.fields[0].settings").isEmpty())
            .andExpect(jsonPath("$.fields[1].id").value(991201))
            .andExpect(jsonPath("$.fields[1].name").value("cf_priority"))
            .andExpect(jsonPath("$.fields[1].collection[0]").value("Red"))
            .andExpect(jsonPath("$.fields[1].collection[1]").value("Blue"))
            .andExpect(jsonPath("$.fields[1].settings.enabled").value(true))
            .andExpect(jsonPath("$.fields[1].settings.display").value("compact"))
            .andExpect(jsonPath("$.fields[1].minlength").value(0))
            .andExpect(jsonPath("$.fields[1].maxlength").value(20));
    }

    @Test
    void acceptsTasksWithNoDefinitionsAndRejectsUnknownOrAnonymousRequests() throws Exception {
        metadata(aliceBearer, "tasks")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entity").value("tasks"))
            .andExpect(jsonPath("$.fields").isEmpty());
        mockMvc.perform(get("/api/v1/metadata/widgets").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mockMvc.perform(get("/api/v1/metadata/accounts")).andExpect(status().isUnauthorized());
    }

    private ResultActions metadata(String bearer, String entity) throws Exception {
        return mockMvc.perform(get("/api/v1/metadata/{entity}", entity).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private void clearFixtureRows() {
        jdbcTemplate.update("DELETE FROM fields WHERE field_group_id = ? OR id BETWEEN 991201 AND 991203", GROUP_ID);
        jdbcTemplate.update("DELETE FROM field_groups WHERE id = ?", GROUP_ID);
        customFieldRegistry.invalidate();
        jdbcTemplate.update("DELETE FROM users WHERE username = 'metadata_alice'");
    }

    private void insertField(
        long id,
        String type,
        int position,
        String name,
        String label,
        String hint,
        String placeholder,
        String as,
        String collection,
        boolean disabled,
        boolean required,
        Integer minlength,
        Integer maxlength,
        Long pairId,
        String settings
    ) {
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, hint, placeholder, \"as\", "
                + "collection, disabled, required, minlength, maxlength, pair_id, settings, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
            id, type, GROUP_ID, position, name, label, hint, placeholder, as, collection, disabled, required,
            minlength, maxlength, pairId, settings);
    }
}
