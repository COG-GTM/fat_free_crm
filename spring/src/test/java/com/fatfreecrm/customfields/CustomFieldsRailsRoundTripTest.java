package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.service.query.RansackParser;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

@Transactional
class CustomFieldsRailsRoundTripTest extends AbstractPostgresIntegrationTest {

    private static final String MATRIX_PATH = "/customfields/rails_custom_fields_matrix.json";
    private static final String JAVA_COLUMNS_PATH = "/customfields/java_written_columns.json";
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomFieldRegistry registry;

    @Autowired
    private CustomFieldReadService readService;

    @Autowired
    private CustomFieldWriteService writeService;

    @Autowired
    private CustomFieldTypeValidator validator;

    @Autowired
    private CustomFieldPredicates predicates;

    @Autowired
    private RansackParser ransackParser;

    @Autowired
    private CheckBoxesYamlCodec yamlCodec;

    @PersistenceContext
    private EntityManager entityManager;

    private JsonNode matrix;

    @BeforeEach
    void loadRailsFixtures() throws IOException {
        matrix = resource(MATRIX_PATH);
        JsonNode fieldGroup = matrix.path("field_group");
        jdbcTemplate.update(
            "INSERT INTO field_groups (id, klass_name, label, \"position\", created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, now(), now())",
            fieldGroup.path("id").asLong(), fieldGroup.path("klass_name").asText(),
            fieldGroup.path("label").asText(), fieldGroup.path("position").asInt());

        JsonNode fields = matrix.path("fields");
        Iterator<String> columns = matrix.path("column_types").fieldNames();
        while (columns.hasNext()) {
            String name = columns.next();
            jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN " + name + " "
                + postgresType(matrix.path("column_types").path(name).asText()));
        }
        for (JsonNode field : fields) {
            insertField(field);
        }
        registry.invalidate();
        for (JsonNode row : matrix.path("rows")) {
            insertRow(row);
        }
        entityManager.clear();
    }

    @Test
    void matchesRailsTriggerDocumentsAndAsJsonValues() throws Exception {
        for (JsonNode row : matrix.path("rows")) {
            long id = row.path("id").asLong();
            JsonNode trigger = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT custom_fields::text FROM accounts WHERE id = ?", String.class, id));
            assertThat(trigger).isEqualTo(row.path("custom_fields"));

            Account account = entityManager.find(Account.class, id);
            JsonNode railsJson = objectMapper.valueToTree(readService.railsJsonValues(account));
            assertThat(railsJson).isEqualTo(row.path("rails_json"));
        }
    }

    @Test
    void matchesRailsValidationMessages() {
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("cf_ab271_required", "");
        invalid.put("cf_ab271_minimum", "a");
        invalid.put("cf_ab271_maximum", "123456");
        invalid.put("cf_ab271_range_start", "2025-02-02");
        invalid.put("cf_ab271_range_end", "2025-02-01");

        ValidationResult result = validator.validate(
            registry.definitionsFor(com.fatfreecrm.domain.support.RailsModelType.ACCOUNT),
            invalid,
            CustomFieldTypeValidator.Mode.WRITE);

        Map<String, List<String>> railsErrors = new LinkedHashMap<>();
        matrix.path("validation_errors").path("errors").properties().forEach(entry -> {
            List<String> messages = new ArrayList<>();
            entry.getValue().forEach(message ->
                messages.add(message.asText().replaceFirst("^\\^", "")));
            railsErrors.put(entry.getKey(), messages);
        });
        JsonNode actualErrors = objectMapper.valueToTree(result.errors());
        JsonNode expectedErrors = objectMapper.valueToTree(railsErrors);
        assertThat(actualErrors).isEqualTo(expectedErrors);
    }

    @Test
    void matchesRailsSearchResultsForSupportedMatrixCases() {
        List<Long> fixtureIds = new ArrayList<>();
        matrix.path("rows").forEach(row -> fixtureIds.add(row.path("id").asLong()));
        Iterator<Map.Entry<String, JsonNode>> fields = matrix.path("search").properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            Iterator<Map.Entry<String, JsonNode>> cases = field.getValue().path("cases").properties().iterator();
            while (cases.hasNext()) {
                Map.Entry<String, JsonNode> searchCase = cases.next();
                String caseName = searchCase.getKey();
                String operator = caseName.substring(0, caseName.indexOf(':'));
                assertSearchCase(field.getKey(), operator, searchCase.getValue(), fixtureIds);
            }
            JsonNode uncoercible = field.getValue().path("uncoercible");
            if (!uncoercible.isMissingNode() && !uncoercible.isNull()) {
                assertSearchCase(field.getKey(), "eq", uncoercible, fixtureIds);
            }
        }
    }

    @Test
    void writesRailsColumnRepresentationsAndMatchesRailsReadback() throws Exception {
        JsonNode expectedColumns = resource(JAVA_COLUMNS_PATH).path("columns");
        Map<String, Object> input = new LinkedHashMap<>();
        for (CustomFieldDefinition definition
            : registry.definitionsFor(com.fatfreecrm.domain.support.RailsModelType.ACCOUNT)) {
            if (expectedColumns.has(definition.name())) {
                input.put(definition.name(), writeValue(definition, expectedColumns.path(definition.name()).asText()));
            }
        }
        input.put("cf_ab271_required", "ok");
        input.put("cf_ab271_minimum", "abc");
        input.put("cf_ab271_maximum", "valid");
        input.put("cf_ab271_range_start", "2025-02-01");
        input.put("cf_ab271_range_end", "2025-02-10");

        Long id = jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name) VALUES ('ab271-java-roundtrip') RETURNING id", Long.class);
        entityManager.clear();
        Account account = entityManager.find(Account.class, id);
        Map<String, Object> values = writeService.write(account, input);

        Map<String, String> actualColumns = new LinkedHashMap<>();
        Iterator<String> names = expectedColumns.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            actualColumns.put(name, jdbcTemplate.queryForObject(
                "SELECT " + name + "::text FROM accounts WHERE id = ?", String.class, id));
        }
        if (Boolean.getBoolean("customfields.updateFixtures")) {
            writeJavaColumnsFixture(actualColumns);
        } else {
            assertThat(actualColumns).isEqualTo(objectMapper.convertValue(expectedColumns, OBJECT_MAP));
        }

        JsonNode railsValues = matrix.path("java_written").path("rails_values");
        Map<String, Object> expectedRails = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> entries = railsValues.properties().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            expectedRails.put(entry.getKey(), objectMapper.convertValue(entry.getValue().path("value"), Object.class));
        }
        Map<String, Object> allRails = readService.railsJsonValues(entityManager.find(Account.class, id));
        Map<String, Object> actualRails = new LinkedHashMap<>();
        expectedRails.keySet().forEach(name -> actualRails.put(name, allRails.get(name)));
        assertThat(actualRails).containsExactlyInAnyOrderEntriesOf(expectedRails);
        assertThat(values.keySet()).containsAll(input.keySet());
    }

    private void insertField(JsonNode field) {
        jdbcTemplate.update(
            "INSERT INTO fields (id, type, field_group_id, \"position\", name, label, \"as\", collection, "
                + "disabled, required, maxlength, minlength, pair_id, settings, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
            field.path("id").asLong(),
            field.path("type").isNull() ? null : field.path("type").asText(),
            field.path("field_group_id").asLong(),
            field.path("position").asInt(),
            field.path("name").asText(),
            field.path("label").asText(),
            field.path("as").asText(),
            yaml(field.path("collection"), STRING_LIST),
            field.path("disabled").asBoolean(),
            field.path("required").asBoolean(),
            nullableInteger(field.path("maxlength")),
            nullableInteger(field.path("minlength")),
            nullableLong(field.path("pair_id")),
            yaml(field.path("settings"), OBJECT_MAP));
    }

    private void assertSearchCase(String field, String operator, JsonNode searchCase, List<Long> fixtureIds) {
        List<String> rawValues = new ArrayList<>();
        searchCase.path("values").forEach(value -> rawValues.add(value.asText()));
        if (searchCase.has("error")) {
            CriteriaBuilder builder = entityManager.getCriteriaBuilder();
            CriteriaQuery<Account> criteria = builder.createQuery(Account.class);
            Root<Account> root = criteria.from(Account.class);
            assertThat(predicates.toPredicate(
                root, criteria, builder, Account.class, field, operator, rawValues))
                .as("%s %s should remain unsupported like Rails", field, operator)
                .isNull();
            return;
        }

        Object queryValue = rawValues.size() == 1 ? rawValues.getFirst() : rawValues;
        var plan = ransackParser.parse(Account.class, Map.of(field + "_" + operator, queryValue));
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> criteria = builder.createQuery(Long.class);
        Root<Account> root = criteria.from(Account.class);
        List<Predicate> restrictions = new ArrayList<>();
        restrictions.add(root.get("id").in(fixtureIds));
        if (plan.where() != null) {
            Predicate search = plan.where().toPredicate(root, criteria, builder);
            if (search != null) {
                restrictions.add(search);
            }
        }
        criteria.select(root.get("id")).where(restrictions.toArray(Predicate[]::new));
        List<Long> actual = entityManager.createQuery(criteria).getResultList().stream().sorted().toList();
        List<Long> expected = objectMapper.convertValue(searchCase.path("ids"), new TypeReference<>() { });
        assertThat(actual).as("%s %s", field, operator).isEqualTo(expected);
    }

    private void insertRow(JsonNode row) {
        List<String> names = new ArrayList<>();
        matrix.path("column_types").fieldNames().forEachRemaining(names::add);
        String columns = String.join(", ", names);
        String values = names.stream()
            .map(name -> "?::" + postgresType(matrix.path("column_types").path(name).asText()))
            .collect(java.util.stream.Collectors.joining(", "));
        String sql = "INSERT INTO accounts (id, name, created_at, updated_at, " + columns
            + ") VALUES (?, ?, ?, ?, " + values + ")";
        List<Object> parameters = new ArrayList<>(List.of(
            row.path("id").asLong(),
            "ab271-rails-roundtrip-" + row.path("id").asText(),
            Timestamp.valueOf("2025-01-01 00:00:00"),
            Timestamp.valueOf("2025-01-01 00:00:00")));
        for (String name : names) {
            JsonNode raw = row.path("raw_columns").path(name);
            parameters.add(raw.isNull() ? null : raw.asText());
        }
        jdbcTemplate.update(sql, parameters.toArray());
    }

    private Object writeValue(CustomFieldDefinition definition, String raw) {
        return switch (definition.as()) {
            case "check_boxes" -> yamlCodec.decode(raw);
            case "boolean" -> Boolean.valueOf(raw);
            case "date", "date_pair" -> raw;
            case "datetime", "datetime_pair" -> raw.replace(' ', 'T') + "Z";
            case "decimal" -> new BigDecimal(raw);
            case "integer" -> Integer.valueOf(raw);
            case "float" -> Double.valueOf(raw);
            default -> raw;
        };
    }

    private JsonNode resource(String path) throws IOException {
        try (var input = getClass().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing custom-fields fixture " + path);
            }
            return objectMapper.readTree(input);
        }
    }

    private static String postgresType(String type) {
        return switch (type) {
            case "character varying" -> "varchar";
            case "timestamp without time zone" -> "timestamp";
            case "double precision" -> "double precision";
            case "numeric" -> "numeric(15,2)";
            case "text", "integer", "boolean", "date" -> type;
            default -> throw new IllegalArgumentException("Unsupported custom-field column type " + type);
        };
    }

    private static Integer nullableInteger(JsonNode value) {
        return value.isNull() || value.isMissingNode() ? null : value.asInt();
    }

    private static Long nullableLong(JsonNode value) {
        return value.isNull() || value.isMissingNode() ? null : value.asLong();
    }

    private String yaml(JsonNode value, TypeReference<?> type) {
        if (value.isNull() || value.isMissingNode()) {
            return null;
        }
        Object parsed = objectMapper.convertValue(value, type);
        return new Yaml().dump(parsed);
    }

    private void writeJavaColumnsFixture(Map<String, String> columns) throws IOException {
        Path path = Path.of("src/test/resources/customfields/java_written_columns.json");
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(Map.of("columns", columns)) + "\n");
    }
}
