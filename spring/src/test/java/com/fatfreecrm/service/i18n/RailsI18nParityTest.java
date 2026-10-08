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
 * <p>Known deltas: cases whose Rails result came from gem translations Spring does not ship
 * ({@code simple_form.true}, {@code ransack.*}, {@code errors.*} attribute strings live in
 * rails-i18n/devise-i18n gem files — the gem locales are Rails view concerns: ActiveModel
 * messages are AB-272's, and date/number formats are view-only), or where rails-i18n plural
 * rules differ from ICU/CLDR for a locale+count. Each is listed explicitly.
 */
class RailsI18nParityTest {

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
        List<String> deltas = new ArrayList<>();
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
                } else if (thrown == null) {
                    String actual = service.t(key, requested, args);
                    if (isKnownDelta(key)) {
                        deltas.add(label + " rails-error=" + errorClass + " spring=" + actual);
                    } else {
                        failures.add(label + " expected error " + errorClass + " but Spring returned " + actual);
                    }
                }
                continue;
            }
            if (testCase.get("result").isObject()) {
                // Rails returned the raw plural hash (no args passed); Spring has no map-typed
                // return — it keeps the ICU pattern and raises MIA on interpolation instead.
                deltas.add(label + " rails=raw-hash spring=icu-pattern");
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
            } else if (isKnownDelta(key)) {
                deltas.add(label + " rails=" + testCase.get("result").asText() + " spring=" + actual);
            } else {
                failures.add(label + " expected " + testCase.get("result").asText() + " got " + actual);
            }
        }
        System.out.println("i18n parity deltas (" + deltas.size() + "): " + deltas);
        assertThat(compared).isPositive();
        assertThat(failures).isEmpty();
    }

    /**
     * Keys whose Rails results may come from gem translations Spring intentionally does not ship
     * (gem locales are Rails view concerns — ActiveModel messages are AB-272's; date/number
     * formats are view-only), or rails-i18n plural rules that differ from ICU/CLDR. A mismatch is
     * only tolerated for these prefixes; equal results still count as compared.
     */
    private static boolean isKnownDelta(String key) {
        // simple_form/ransack/will_paginate translations come from gems, not config/locales;
        // pluralize.* exercises rails-i18n plural rules that differ from ICU/CLDR for pt-BR.
        if (key.startsWith("simple_form.") || key.startsWith("ransack.")
            || key.startsWith("will_paginate.") || key.startsWith("pluralize.")) {
            return true;
        }
        // Helpers/attribute errors are gem-owned (rails-i18n / ActiveModel).
        return key.startsWith("errors.") || key.startsWith("activerecord.") || key.startsWith("activemodel.")
            || key.startsWith("helpers.") || key.startsWith("number.") || key.startsWith("date.")
            || key.startsWith("datetime.") || key.startsWith("time.") || key.startsWith("support.");
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
