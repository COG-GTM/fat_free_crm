package com.fatfreecrm.service.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.config.I18nProperties;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class I18nServiceTest {

    private static Properties bundle(String... pairs) {
        Properties props = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            props.setProperty(pairs[i], pairs[i + 1]);
        }
        return props;
    }

    private static I18nService service(Map<String, Properties> bundles) {
        return new I18nService(new I18nProperties(List.of("en"), Map.of("en", List.of("en-US"))), bundles);
    }

    @Test
    void countDoesNotGroupThousands() {
        I18nService service = service(Map.of(
            "en-US", bundle("items_total", "Total {count, number, ::group-off}.")));
        assertThat(service.t("items_total", Locale.US, Map.of("count", 1234))).isEqualTo("Total 1234.");
    }

    @Test
    void pluralBranchesSelectExactAndCardinal() {
        I18nService service = service(Map.of(
            "ru", bundle("days", "{count, plural, =0{0 дней} one{день} few{дня} many{дней} other{дней}}")));
        Locale ru = Locale.forLanguageTag("ru");
        assertThat(service.t("days", ru, Map.of("count", 0))).isEqualTo("0 дней");
        assertThat(service.t("days", ru, Map.of("count", 1))).isEqualTo("день");
        assertThat(service.t("days", ru, Map.of("count", 3))).isEqualTo("дня");
        assertThat(service.t("days", ru, Map.of("count", 11))).isEqualTo("дней");
        assertThat(service.t("days", ru, Map.of("count", 25))).isEqualTo("дней");
    }

    @Test
    void bracesApostrophesAndHashLiteralRenderCorrectly() {
        I18nService service = service(Map.of(
            "en-US", bundle(
                "braced", "show {value} here",
                "apos", "couldn't delete '%{value}'",
                "hash_in_plural", "{count, plural, one{1 '#' comment} "
                    + "other{{count, number, ::group-off} '#' comments}}")));
        assertThat(service.t("braced", Locale.US)).isEqualTo("show {value} here");
        assertThat(service.t("apos", Locale.US, Map.of("value", "x"))).isEqualTo("couldn't delete 'x'");
        assertThat(service.t("hash_in_plural", Locale.US, Map.of("count", 1))).isEqualTo("1 # comment");
        assertThat(service.t("hash_in_plural", Locale.US, Map.of("count", 5))).isEqualTo("5 # comments");
    }

    @Test
    void missingInterpolationArgumentThrows() {
        I18nService service = service(Map.of("en-US", bundle("greet", "Hi %{name}")));
        assertThatThrownBy(() -> service.t("greet", Locale.US, Map.of("other", "x")))
            .isInstanceOf(MissingInterpolationArgumentException.class);
    }

    @Test
    void missingKeyYieldsRailsMissingString() {
        I18nService service = service(Map.of("en-US", bundle("present", "yes")));
        assertThat(service.t("no.such.key", Locale.US)).isEqualTo("Translation missing: en-US.no.such.key");
        assertThat(service.t("no.such.key", Locale.forLanguageTag("de-DE")))
            .isEqualTo("Translation missing: de-DE.no.such.key");
    }

    @Test
    void fallbackChainMatchesFixtureChains() {
        I18nService service = service(Map.of());
        assertThat(service.fallbackChain("de-DE")).containsExactly("de-DE", "de", "en");
        assertThat(service.fallbackChain("en-US")).containsExactly("en-US", "en");
        assertThat(service.fallbackChain("en")).containsExactly("en-US");
        assertThat(service.fallbackChain("pt-BR")).containsExactly("pt-BR", "pt", "en");
        assertThat(service.fallbackChain("xx")).containsExactly("xx", "en");
    }

    @Test
    void foundLocalePluralizerAppliesInFallback() {
        // Key only exists in en; a ru request formats with the en (found-locale) pluralizer.
        I18nService service = service(Map.of(
            "en", bundle("days", "{count, plural, one{day} other{days}}")));
        assertThat(service.t("days", Locale.forLanguageTag("ru"), Map.of("count", 2))).isEqualTo("days");
    }

    @Test
    void nonCountArgsAreStringifiedRubyStyle() {
        I18nService service = service(Map.of("en-US", bundle("v", "got %{value} and %{other}")));
        assertThat(service.t("v", Locale.US, Map.of("value", 42, "other", true))).isEqualTo("got 42 and true");
        Map<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("value", 42);
        withNull.put("other", null);
        assertThat(service.t("v", Locale.US, withNull)).isEqualTo("got 42 and ");
    }
}
