package com.fatfreecrm.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.repository.SettingRepository;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AB-273 (settings-i18n): replays the Rails-recorded settings matrix
 * (lib/tasks/ffcrm/settings_matrix.rake): for each tier state the fixture's raw DB texts are
 * re-seeded verbatim and {@link SettingsService} must resolve every key and dig identically.
 *
 * <p>Allowed delta: {@code db_bad_class} — Rails raises {@code Psych::DisallowedClass} (OpenStruct
 * is not in {@code yaml_column_permitted_classes}, config/application.rb:87-99); Spring's safe
 * RailsYaml must NOT throw and returns the decoded mapping instead.
 */
class RailsSettingsParityTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Rails file:line for each allowed delta (asserted as a mapping, not an error). */
    private static final Map<String, String> KNOWN_DELTAS = Map.of(
        "db_bad_class", "config/application.rb:87-99 Psych::DisallowedClass (OpenStruct not permitted)");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JsonNode matrix;

    @BeforeEach
    void loadMatrix() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/settings/rails_settings_matrix.json")) {
            matrix = JSON.readTree(input);
        }
    }

    @Test
    void everyStateMatchesRailsResolution() throws Exception {
        for (JsonNode state : matrix.get("states")) {
            String name = state.get("state").asText();
            seedRows(state.get("rows"));
            SettingsService service = serviceFor(state);
            for (Map.Entry<String, JsonNode> entry : state.get("values").properties()) {
                JsonNode actual = encode(service.get(entry.getKey()));
                if (KNOWN_DELTAS.containsKey(entry.getKey())) {
                    assertThat(entry.getValue().has("$error"))
                        .as("%s.%s: expected a recorded Rails error (%s)",
                            name, entry.getKey(), KNOWN_DELTAS.get(entry.getKey()))
                        .isTrue();
                    assertThat(actual.isNull())
                        .as("%s.%s: Spring tolerates the row and resolves it as nil (skipped)",
                            name, entry.getKey())
                        .isTrue();
                } else {
                    assertThat((Object) actual).as("%s.%s", name, entry.getKey()).isEqualTo(entry.getValue());
                }
            }
            for (JsonNode dig : state.get("digs")) {
                List<String> path = new ArrayList<>();
                dig.get("path").forEach(step -> path.add(step.asText()));
                JsonNode actual = encode(service.dig(path.get(0), path.subList(1, path.size()).toArray(String[]::new)));
                assertThat((Object) actual).as("%s dig %s", name, path).isEqualTo(dig.get("result"));
            }
        }
    }

    private void seedRows(JsonNode rows) {
        jdbcTemplate.update("DELETE FROM settings");
        for (JsonNode row : rows) {
            jdbcTemplate.update(
                "INSERT INTO settings (name, value, created_at, updated_at) VALUES (?, ?, now(), now())",
                row.get("name").asText(), row.get("value").isNull() ? null : row.get("value").asText());
        }
    }

    private SettingsService serviceFor(JsonNode state) throws Exception {
        Map<String, Object> defaults = SettingsService.parseTier(
            resourceText("/settings/settings.default.yml"), "settings.default.yml");
        Map<String, Object> yaml = defaults;
        if (!state.get("override_file").isNull()) {
            yaml = deepMerge(defaults, SettingsService.parseTier(
                resourceText("/settings/settings.override.yml"), "settings.override.yml"));
        }
        return new SettingsService(yaml, Map.of(), repository(),
            Clock.fixed(Instant.now(), ZoneOffset.UTC), Duration.ofMinutes(5));
    }

    private SettingRepository repository() {
        SettingRepository repository = mock(SettingRepository.class);
        when(repository.findAll(org.springframework.data.domain.Sort.by("id"))).thenAnswer(
            ignored -> jdbcTemplate.query(
                "SELECT id, name, value FROM settings ORDER BY id",
                (rs, i) -> {
                    Setting setting = new Setting();
                    setting.setName(rs.getString("name"));
                    setting.setValue(rs.getString("value"));
                    return setting;
                }));
        return repository;
    }

    private static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        // Same ActiveSupport semantics as SettingsService (Hash+Hash recurses, else replace).
        Map<String, Object> merged = new java.util.LinkedHashMap<>(base);
        overlay.forEach((key, value) -> {
            Object existing = merged.get(key);
            if (existing instanceof Map<?, ?> && value instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> combined = deepMerge((Map<String, Object>) existing, (Map<String, Object>) value);
                merged.put(key, combined);
            } else {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private static String resourceText(String path) throws Exception {
        try (InputStream input = RailsSettingsParityTest.class.getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Mirrors the fixture encoding contract (settings_matrix.rake#settings_matrix_encode). */
    @SuppressWarnings("unchecked")
    private static JsonNode encode(Object value) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        if (value == null) {
            return factory.nullNode();
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode node = factory.objectNode();
            map.forEach((key, item) -> node.set(String.valueOf(key), encode(item)));
            return node;
        }
        if (value instanceof List<?> list) {
            var array = factory.arrayNode();
            list.forEach(item -> array.add(encode(item)));
            return array;
        }
        if (value instanceof String text) {
            return factory.textNode(text);
        }
        if (value instanceof Boolean bool) {
            return factory.booleanNode(bool);
        }
        if (value instanceof Integer || value instanceof Short) {
            return factory.numberNode(((Number) value).intValue());
        }
        if (value instanceof Long) {
            return factory.numberNode((Long) value);
        }
        if (value instanceof Double || value instanceof Float) {
            return factory.numberNode(((Number) value).doubleValue());
        }
        if (value instanceof BigDecimal decimal) {
            return factory.numberNode(decimal);
        }
        if (value instanceof Number number) {
            return factory.numberNode(number.doubleValue());
        }
        ObjectNode other = factory.objectNode();
        other.put("$class", value.getClass().getName());
        other.put("inspect", String.valueOf(value));
        return other;
    }
}
