package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code /api/v1/admin/field_groups} and {@code /api/v1/admin/fields} pinned to Rails
 * {@code FieldGroup}/{@code CustomField} (runtime {@code cf_*} DDL, transition safety, acts_as_list).
 */
class AdminFieldsWriteControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String HWIA = "--- !ruby/hash:ActiveSupport::HashWithIndifferentAccess";
    private static final List<String> TABLES = List.of("accounts", "campaigns", "contacts", "leads", "opportunities");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private String aliceBearer;
    private String adminBearer;
    private long defaultGroup;
    private Set<String> baselineColumns;

    @BeforeEach
    void seed() {
        clearData();
        baselineColumns = customColumns();
        aliceBearer = bearer(user("alice", false));
        adminBearer = bearer(user("root_admin", true));
        defaultGroup = fieldGroup("custom_fields", "Custom Fields", "Campaign", 1);
    }

    @AfterEach
    void cleanUp() {
        for (String column : customColumns()) {
            if (!baselineColumns.contains(column)) {
                String[] parts = column.split("\\.");
                jdbcTemplate.execute("ALTER TABLE " + parts[0] + " DROP COLUMN \"" + parts[1] + "\"");
            }
        }
        clearData();
    }

    @Test
    void fieldRoutesRequireAuthenticationAndAdminRoleAndRunNoDdl() throws Exception {
        long fieldId = field(defaultGroup, "cf_note", "Note", "string", 1, "CustomField");
        String fieldBody = "{\"field\":{\"as\":\"string\",\"label\":\"Sneaky\",\"field_group_id\":" + defaultGroup
            + "}}";
        List<MockHttpServletRequestBuilder> routes = List.of(
            json(post("/api/v1/admin/field_groups"))
                .content("{\"field_group\":{\"label\":\"X\",\"klass_name\":\"Campaign\"}}"),
            json(put("/api/v1/admin/field_groups/{id}", defaultGroup)).content("{\"field_group\":{\"label\":\"X\"}}"),
            delete("/api/v1/admin/field_groups/{id}", defaultGroup),
            json(post("/api/v1/admin/field_groups/sort"))
                .content("{\"asset\":\"campaign\",\"campaign_field_groups\":[1]}"),
            json(post("/api/v1/admin/fields")).content(fieldBody),
            json(put("/api/v1/admin/fields/{id}", fieldId)).content(fieldBody),
            delete("/api/v1/admin/fields/{id}", fieldId),
            json(post("/api/v1/admin/fields/sort")).content("{\"field_group_id\":1,\"fields_field_group_1\":[1]}"));
        for (MockHttpServletRequestBuilder route : routes) {
            mockMvc.perform(route).andExpect(status().isUnauthorized());
            mockMvc.perform(route.header(HttpHeaders.AUTHORIZATION, aliceBearer)).andExpect(status().isForbidden());
        }
        assertThat(customColumns()).isEqualTo(baselineColumns);
        assertThat(count("fields")).isEqualTo(1);
        assertThat(count("field_groups")).isEqualTo(1);
    }

    @Test
    void fieldGroupCreateDerivesNameFromLabelAndCommitsBeforeRails500() throws Exception {
        mockMvc.perform(adminJson(post("/api/v1/admin/field_groups"))
                .content("{\"field_group\":{\"label\":\"Extra Info!\",\"klass_name\":\"Campaign\","
                    + "\"position\":\"2\"}}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM field_groups WHERE label = 'Extra Info!'");
        assertThat(row.get("name")).isEqualTo("extra_info_");
        assertThat(row.get("klass_name")).isEqualTo("Campaign");
        assertThat(((Number) row.get("position")).intValue()).isEqualTo(2);

        mockMvc.perform(adminJson(post("/api/v1/admin/field_groups"))
                .content("{\"field_group\":{\"name\":\"explicit\",\"label\":\"Explicit Label\","
                    + "\"klass_name\":\"Campaign\"}}"))
            .andExpect(status().isInternalServerError());
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM field_groups WHERE label = 'Explicit Label'",
            String.class)).isEqualTo("explicit");

        mockMvc.perform(adminJson(post("/api/v1/admin/field_groups")).content("{\"field_group\":{\"label\":\" \"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.label", contains("can't be blank")));
        for (String body : List.of("{}", "{\"field_group\":{}}")) {
            mockMvc.perform(adminJson(post("/api/v1/admin/field_groups")).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                    .value("param is missing or the value is empty or invalid: field_group"));
        }
        assertThat(count("field_groups")).isEqualTo(3);
    }

    @Test
    void fieldGroupUpdateRederivesBlankNameAndValidatesLabel() throws Exception {
        long id = fieldGroup("extra", "Extra", "Campaign", 2);

        mockMvc.perform(adminJson(put("/api/v1/admin/field_groups/{id}", id))
                .content("{\"field_group\":{\"label\":\"More Extra\",\"name\":\"\",\"hint\":\"h\"}}"))
            .andExpect(status().isNoContent());
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM field_groups WHERE id = ?", id);
        assertThat(row.get("name")).isEqualTo("more_extra");
        assertThat(row.get("label")).isEqualTo("More Extra");
        assertThat(row.get("hint")).isEqualTo("h");

        mockMvc.perform(adminJson(put("/api/v1/admin/field_groups/{id}", id))
            .content("{\"field_group\":{\"label\":\"\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.label", contains("can't be blank")));
        assertThat(jdbcTemplate.queryForObject("SELECT label FROM field_groups WHERE id = ?", String.class, id))
            .isEqualTo("More Extra");
        mockMvc.perform(adminJson(put("/api/v1/admin/field_groups/999999"))
            .content("{\"field_group\":{\"label\":\"X\"}}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void fieldGroupDestroyMovesFieldsToTheKlassDefaultGroupOrFailsWithoutOne() throws Exception {
        long extra = fieldGroup("extra", "Extra", "Campaign", 2);
        long f1 = field(extra, "cf_a", "A", "string", 1, "CustomField");
        long f2 = field(extra, "cf_b", "B", "text", 2, "CustomField");

        mockMvc.perform(delete("/api/v1/admin/field_groups/{id}", extra).header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForList("SELECT field_group_id FROM fields WHERE id IN (?, ?)", Long.class, f1,
            f2))
            .containsExactly(defaultGroup, defaultGroup);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM field_groups WHERE id = ?", Integer.class, extra))
            .isZero();

        long orphan = fieldGroup("contact_extra", "Contact Extra", "Contact", 1);
        mockMvc.perform(delete("/api/v1/admin/field_groups/{id}", orphan).header(HttpHeaders.AUTHORIZATION,
            adminBearer))
            .andExpect(status().isInternalServerError());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM field_groups WHERE id = ?", Integer.class, orphan))
            .isEqualTo(1);

        // not_default_field_group returns false without throw(:abort): an (empty) default group is destroyable.
        // Destroying a default group that still owns fields is deliberately not pinned here (see PR notes).
        long emptyDefault = fieldGroup("custom_fields", "Custom Fields", "Lead", 1);
        mockMvc.perform(delete("/api/v1/admin/field_groups/{id}", emptyDefault).header(HttpHeaders.AUTHORIZATION,
            adminBearer))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM field_groups WHERE id = ?", Integer.class,
            emptyDefault)).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT field_group_id FROM fields WHERE id IN (?, ?)", Long.class,
            f1, f2)).containsExactly(defaultGroup, defaultGroup);
        mockMvc.perform(delete("/api/v1/admin/field_groups/999999").header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void fieldGroupSortUpdatesPositionsThenRails500sAndRequiresTheIdList() throws Exception {
        long extra = fieldGroup("extra", "Extra", "Campaign", 2);

        mockMvc.perform(adminJson(post("/api/v1/admin/field_groups/sort"))
                .content("{\"asset\":\"campaign\",\"campaign_field_groups\":[\"" + extra + "\",\"" + defaultGroup
                    + "\"]}"))
            .andExpect(status().isInternalServerError());
        assertThat(jdbcTemplate.queryForObject("SELECT position FROM field_groups WHERE id = ?", Integer.class, extra))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT position FROM field_groups WHERE id = ?", Integer.class,
            defaultGroup)).isEqualTo(2);

        mockMvc.perform(adminJson(post("/api/v1/admin/field_groups/sort")).content("{\"asset\":\"campaign\"}"))
            .andExpect(status().isInternalServerError());
        assertThat(jdbcTemplate.queryForObject("SELECT position FROM field_groups WHERE id = ?", Integer.class, extra))
            .isEqualTo(1);
    }

    @Test
    void fieldCreateAddsTypedColumnSerializesCollectionAndAppendsToListThenRails500() throws Exception {
        field(defaultGroup, "cf_existing", "Existing", "string", 5, "CustomField");

        mockMvc.perform(adminJson(post("/api/v1/admin/fields"))
                .content("{\"field\":{\"as\":\"select\",\"label\":\"Contract Tier\",\"field_group_id\":\""
                    + defaultGroup
                    + "\",\"collection_string\":\"Gold | Silver|Bronze|\",\"required\":\"1\","
                    + "\"settings\":{\"placeholder_text\":\"Pick one\"}}}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM fields WHERE label = 'Contract Tier'");
        assertThat(row.get("type")).isEqualTo("CustomField");
        assertThat(row.get("name")).isEqualTo("cf_contract_tier");
        assertThat(row.get("as")).isEqualTo("select");
        assertThat(row.get("required")).isEqualTo(true);
        assertThat(((Number) row.get("position")).intValue()).isEqualTo(6);
        assertThat(row.get("collection")).isEqualTo("---\n- Gold\n- Silver\n- Bronze\n");
        assertThat(row.get("settings")).isEqualTo(HWIA + "\nplaceholder_text: Pick one\n");
        assertThat(row.get("created_at")).isNotNull();
        assertThat(columnType("campaigns", "cf_contract_tier")).isEqualTo("character varying");

        mockMvc.perform(adminJson(post("/api/v1/admin/fields"))
                .content("{\"field\":{\"as\":\"decimal\",\"label\":\"Contract Tier\",\"field_group_id\":"
                    + defaultGroup + "}}"))
            .andExpect(status().isInternalServerError());
        assertThat(jdbcTemplate.queryForList("SELECT name FROM fields WHERE label = 'Contract Tier' ORDER BY id",
            String.class)).containsExactly("cf_contract_tier", "cf_contract_tier_2");
        Map<String, Object> decimal = jdbcTemplate.queryForMap("SELECT data_type, numeric_precision, numeric_scale "
            + "FROM information_schema.columns WHERE table_name = 'campaigns' AND column_name = 'cf_contract_tier_2'");
        assertThat(decimal.get("data_type")).isEqualTo("numeric");
        assertThat(((Number) decimal.get("numeric_precision")).intValue()).isEqualTo(15);
        assertThat(((Number) decimal.get("numeric_scale")).intValue()).isEqualTo(2);
    }

    @Test
    void fieldCreateFailuresRunNoDdlAndPersistNothing() throws Exception {
        mockMvc.perform(adminJson(post("/api/v1/admin/fields"))
                .content("{\"field\":{\"as\":\"string\",\"label\":\"\",\"minlength\":\"abc\",\"maxlength\":\"0\","
                    + "\"field_group_id\":" + defaultGroup + "}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.label", contains("^Please enter a field label.")))
            .andExpect(jsonPath("$.errors.maxlength", contains("^Max size can only be whole number.")))
            .andExpect(jsonPath("$.errors.minlength", contains("^Min size can only be whole number.",
                "^Min size cannot be greater than max size.")));
        mockMvc.perform(adminJson(post("/api/v1/admin/fields"))
                .content("{\"field\":{\"as\":\"string\",\"label\":\"" + "L".repeat(65) + "\",\"minlength\":\"5\","
                    + "\"maxlength\":\"2\",\"field_group_id\":" + defaultGroup + "}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.label",
                contains("^The field name must be less than 64 characters in length.")))
            .andExpect(jsonPath("$.errors.minlength", contains("^Min size cannot be greater than max size.")))
            .andExpect(jsonPath("$.errors.maxlength").doesNotExist());
        // Field.lookup_class(nil/unknown) → NoMethodError in Rails
        for (String body : List.of("{\"field\":{\"label\":\"No type\",\"field_group_id\":" + defaultGroup + "}}",
            "{\"field\":{\"as\":\"bogus\",\"label\":\"Bad type\",\"field_group_id\":" + defaultGroup + "}}",
            "{\"field\":{\"as\":\"string\",\"label\":\"No group\"}}")) {
            mockMvc.perform(adminJson(post("/api/v1/admin/fields")).content(body))
                .andExpect(status().isInternalServerError());
        }
        for (String body : List.of("{}", "{\"field\":{}}", "")) {
            mockMvc.perform(adminJson(post("/api/v1/admin/fields")).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("param is missing or the value is empty or invalid: field"));
        }

        assertThat(count("fields")).isZero();
        assertThat(customColumns()).isEqualTo(baselineColumns);
    }

    @Test
    void fieldUpdateAltersColumnTypeOnlyForSafeTransitionsAndRollsBackOnValidationFailure() throws Exception {
        jdbcTemplate.execute("ALTER TABLE campaigns ADD \"cf_note\" character varying");
        long id = field(defaultGroup, "cf_note", "Note", "string", 1, "CustomField");

        mockMvc.perform(adminJson(put("/api/v1/admin/fields/{id}", id))
                .content("{\"field\":{\"as\":\"email\",\"label\":\"Note\"}}"))
            .andExpect(status().isNoContent());
        assertThat(columnType("campaigns", "cf_note")).isEqualTo("character varying");

        mockMvc.perform(adminJson(put("/api/v1/admin/fields/{id}", id))
                .content("{\"field\":{\"as\":\"text\",\"label\":\"Contract Note\",\"hint\":\"long form\"}}"))
            .andExpect(status().isNoContent());
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM fields WHERE id = ?", id);
        assertThat(row.get("as")).isEqualTo("text");
        assertThat(row.get("label")).isEqualTo("Contract Note");
        assertThat(row.get("hint")).isEqualTo("long form");
        assertThat(columnType("campaigns", "cf_note")).isEqualTo("text");

        mockMvc.perform(adminJson(put("/api/v1/admin/fields/{id}", id))
                .content("{\"field\":{\"as\":\"boolean\",\"label\":\"Contract Note\"}}"))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT \"as\" FROM fields WHERE id = ?", String.class, id))
            .isEqualTo("boolean");
        assertThat(columnType("campaigns", "cf_note")).isEqualTo("text"); // unsafe: no DDL

        mockMvc.perform(adminJson(put("/api/v1/admin/fields/{id}", id))
                .content("{\"field\":{\"as\":\"bogus\",\"label\":\"Changed\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.as", contains("^Invalid field type.")));
        mockMvc.perform(adminJson(put("/api/v1/admin/fields/{id}", id))
                .content("{\"field\":{\"as\":\"\",\"label\":\"Changed\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.as", contains("^Please specify a field type.")));
        // field_params["as"].match?(/pair/) on nil → NoMethodError
        mockMvc.perform(adminJson(put("/api/v1/admin/fields/{id}", id)).content("{\"field\":{\"label\":\"Changed\"}}"))
            .andExpect(status().isInternalServerError());
        mockMvc.perform(adminJson(put("/api/v1/admin/fields/{id}", id)).content("{\"field\":{}}"))
            .andExpect(status().isBadRequest());
        row = jdbcTemplate.queryForMap("SELECT * FROM fields WHERE id = ?", id);
        assertThat(row.get("as")).isEqualTo("boolean");
        assertThat(row.get("label")).isEqualTo("Contract Note");
        mockMvc.perform(adminJson(put("/api/v1/admin/fields/999999")).content("{\"field\":{\"as\":\"text\"}}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void fieldDestroyKeepsTheColumnClosesThePositionGapAndRefusesCoreFields() throws Exception {
        jdbcTemplate.execute("ALTER TABLE campaigns ADD \"cf_a\" character varying");
        long f1 = field(defaultGroup, "cf_a", "A", "string", 1, "CustomField");
        long f2 = field(defaultGroup, "cf_b", "B", "string", 2, "CustomField");
        long core = field(defaultGroup, "name", "Name", "string", 3, "CoreField");

        mockMvc.perform(delete("/api/v1/admin/fields/{id}", f1).header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fields WHERE id = ?", Integer.class, f1)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT position FROM fields WHERE id = ?", Integer.class, f2))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT position FROM fields WHERE id = ?", Integer.class, core))
            .isEqualTo(2);
        assertThat(columnType("campaigns", "cf_a")).isEqualTo("character varying");

        mockMvc.perform(delete("/api/v1/admin/fields/{id}", core).header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isInternalServerError());
        assertThat(count("fields")).isEqualTo(2);
        mockMvc.perform(delete("/api/v1/admin/fields/{id}", f1).header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void fieldsSortRepositionsAndRegroupsThenRails500s() throws Exception {
        long extra = fieldGroup("extra", "Extra", "Campaign", 2);
        long f1 = field(defaultGroup, "cf_a", "A", "string", 1, "CustomField");
        long f2 = field(defaultGroup, "cf_b", "B", "string", 2, "CustomField");

        mockMvc.perform(adminJson(post("/api/v1/admin/fields/sort"))
                .content("{\"field_group_id\":\"" + extra + "\",\"fields_field_group_" + extra + "\":[\"" + f2
                    + "\",\"" + f1 + "\"]}"))
            .andExpect(status().isInternalServerError());
        assertThat(jdbcTemplate.queryForMap("SELECT position, field_group_id FROM fields WHERE id = ?", f2))
            .containsEntry("position", 1).containsEntry("field_group_id", (int) extra);
        assertThat(jdbcTemplate.queryForMap("SELECT position, field_group_id FROM fields WHERE id = ?", f1))
            .containsEntry("position", 2).containsEntry("field_group_id", (int) extra);

        mockMvc.perform(adminJson(post("/api/v1/admin/fields/sort")).content("{\"field_group_id\":" + defaultGroup
            + "}"))
            .andExpect(status().isInternalServerError());
        assertThat(jdbcTemplate.queryForObject("SELECT field_group_id FROM fields WHERE id = ?", Long.class, f1))
            .isEqualTo(extra);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder) {
        return builder.contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder adminJson(MockHttpServletRequestBuilder builder) {
        return json(builder).header(HttpHeaders.AUTHORIZATION, adminBearer);
    }

    private long fieldGroup(String name, String label, String klass, int position) {
        return jdbcTemplate.queryForObject("INSERT INTO field_groups (name, label, klass_name, position, created_at, "
            + "updated_at) VALUES (?, ?, ?, ?, now(), now()) RETURNING id", Long.class, name, label, klass, position);
    }

    private long field(long groupId, String name, String label, String as, int position, String type) {
        return jdbcTemplate.queryForObject("INSERT INTO fields (type, field_group_id, position, name, label, \"as\", "
            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now()) RETURNING id", Long.class,
            type, groupId, position, name, label, as);
    }

    private String columnType(String table, String column) {
        return jdbcTemplate.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = ? AND column_name = ?", String.class, table, column);
    }

    private Set<String> customColumns() {
        Set<String> columns = new HashSet<>();
        for (String table : TABLES) {
            for (String column : jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns WHERE "
                + "table_schema = current_schema() AND table_name = ? AND column_name LIKE 'cf\\_%'", String.class,
                    table)) {
                columns.add(table + "." + column);
            }
        }
        return columns;
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private User user(String username, boolean isAdmin) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setFirstName(username);
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(isAdmin);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private void clearData() {
        for (String table : List.of("versions", "fields", "field_groups", "users")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }
}
