package com.fatfreecrm.service.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.config.I18nProperties;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AB-273 (settings-i18n): replays every case in the Rails-recorded i18n matrix
 * (lib/tasks/ffcrm/i18n_matrix.rake) with exact string equality, plus every recorded fallback
 * chain. Unit test — no database.
 *
 * <p>Known deltas, bucketed by category with pinned counts so a new delta fails the test:
 * {@code spring-missing} (Rails result came from a gem translation Spring does not ship —
 * tolerated only when {@code find} is empty in the whole fallback chain; if Spring has the
 * key, any mismatch is a failure), {@code pt-br-zero-plural} (rails-i18n's OneOther rule vs
 * ICU/CLDR for pt-BR at count 0), and {@code raw-hash} (plural key invoked without args —
 * Rails returns the hash itself; Spring has no map-typed return and keeps the ICU pattern).
 */
class RailsI18nParityTest {

    // Pinned per-category delta counts — a new delta fails this test.
    private static final int SPRING_MISSING_DELTAS = 46;
    private static final int PT_BR_ZERO_PLURAL_DELTAS = 6;
    private static final int RAW_HASH_DELTAS = 7;

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode matrix;
    private I18nService service;

    @BeforeEach
    void loadMatrix() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/i18n/rails_i18n_matrix.json")) {
            matrix = JSON.readTree(input);
        }
        Map<String, Properties> bundles = new LinkedHashMap<>();
        for (JsonNode locale : matrix.get("app_locales")) {
            String tag = locale.asText();
            String path = "/i18n/messages_" + tag.replace('-', '_') + ".properties";
            Properties props = new Properties();
            try (InputStream input = getClass().getResourceAsStream(path)) {
                assertThat(input).as("generated bundle %s", path).isNotNull();
                try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
            }
            bundles.put(tag, props);
        }
        service = new I18nService(
            new I18nProperties(List.of("en"), Map.of("en", List.of("en-US"))), bundles);
    }

    @Test
    void everyRecordedFallbackChainMatches() {
        matrix.get("fallbacks").properties().forEach(entry -> {
            List<String> expected = new ArrayList<>();
            entry.getValue().forEach(tag -> expected.add(tag.asText()));
            assertThat(service.fallbackChain(entry.getKey())).as("chain for %s", entry.getKey()).isEqualTo(expected);
        });
    }

    @Test
    void everyRecordedCaseMatchesExactly() {
        Map<String, List<String>> deltas = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        int compared = 0;
        for (JsonNode testCase : matrix.get("cases")) {
            String locale = testCase.get("locale").asText();
            String key = testCase.get("key").asText();
            String label = locale + " " + key + " " + testCase.get("args");

            Map<String, Object> args = args(testCase.get("args"));
            Locale requested = Locale.forLanguageTag(locale);
            if (testCase.has("error")) {
                String errorClass = testCase.get("error").asText();
                Throwable thrown = catchThrowable(() -> service.t(key, requested, args));
                if ("I18n::MissingInterpolationArgument".equals(errorClass)) {
                    assertThat(thrown).as(label).isInstanceOf(MissingInterpolationArgumentException.class);
                    compared++;
                } else if (thrown != null) {
                    // Spring also errored on a non-MIA Rails error — the case is compared.
                    compared++;
                } else {
                    String actual = service.t(key, requested, args);
                    String category = deltaCategory(locale, key, args);
                    if (category != null) {
                        deltas.computeIfAbsent(category, ignored -> new ArrayList<>())
                            .add(label + " rails-error=" + errorClass + " spring=" + actual);
                    } else {
                        failures.add(label + " expected error " + errorClass + " but Spring returned " + actual);
                    }
                }
                continue;
            }
            if (testCase.get("result").isObject()) {
                // Rails returned the raw plural hash (no args passed); Spring has no map-typed
                // return — it keeps the ICU pattern and raises MIA on interpolation instead.
                deltas.computeIfAbsent("raw-hash", ignored -> new ArrayList<>())
                    .add(label + " rails=raw-hash spring=icu-pattern");
                continue;
            }
            Throwable thrown = catchThrowable(() -> service.t(key, requested, args));
            if (thrown != null) {
                failures.add(label + " threw " + thrown.getClass().getSimpleName()
                    + " expected " + testCase.get("result").asText());
                continue;
            }
            String actual = service.t(key, requested, args);
            if (actual.equals(testCase.get("result").asText())) {
                compared++;
            } else {
                String category = deltaCategory(locale, key, args);
                if (category != null) {
                    deltas.computeIfAbsent(category, ignored -> new ArrayList<>())
                        .add(label + " rails=" + testCase.get("result").asText() + " spring=" + actual);
                } else {
                    failures.add(label + " expected " + testCase.get("result").asText() + " got " + actual);
                }
            }
        }
        deltas.forEach((category, list)
            -> System.out.println("i18n parity deltas " + category + " (" + list.size() + "): " + list));
        assertThat(deltas.keySet()).as("delta categories").containsExactlyInAnyOrder(
            "spring-missing", "pt-br-zero-plural", "raw-hash");
        assertThat(deltas.getOrDefault("spring-missing", List.of())).hasSize(SPRING_MISSING_DELTAS);
        assertThat(deltas.getOrDefault("pt-br-zero-plural", List.of())).hasSize(PT_BR_ZERO_PLURAL_DELTAS);
        assertThat(deltas.getOrDefault("raw-hash", List.of())).hasSize(RAW_HASH_DELTAS);
        assertThat(compared).isPositive();
        assertThat(failures).isEmpty();
    }

    /**
     * Returns the tolerated delta category for a mismatch, or null when the mismatch is a
     * genuine failure:
     * <ul>
     *   <li>{@code spring-missing} — Spring has no translation for the key anywhere in the
     *       fallback chain (gem-owned locales Spring intentionally does not ship),</li>
     *   <li>{@code pt-br-zero-plural} — rails-i18n registers the OneOther rule for pt-BR
     *       (rails-i18n-8.1.0 rails/pluralization/pt-BR.rb:3, which maps to
     *       lib/rails_i18n/common_pluralizations/one_other.rb:7 {@code n == 1 ? :one : :other},
     *       so 0 &rarr; {@code :other}) while ICU/CLDR pt maps 0 &rarr; {@code :one}.</li>
     * </ul>
     */
    private String deltaCategory(String locale, String key, Map<String, Object> args) {
        if (service.find(key, Locale.forLanguageTag(locale)).isEmpty()) {
            return "spring-missing";
        }
        if ("pt-BR".equals(locale) && key.startsWith("pluralize.") && isZero(args.get("count"))) {
            return "pt-br-zero-plural";
        }
        return null;
    }

    private static boolean isZero(Object value) {
        return value instanceof Number number && number.longValue() == 0;
    }

    private static Map<String, Object> args(JsonNode node) {
        Map<String, Object> args = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            args.put(entry.getKey(), value.isNumber() ? value.numberValue() : value.asText());
        });
        return args;
    }
}
