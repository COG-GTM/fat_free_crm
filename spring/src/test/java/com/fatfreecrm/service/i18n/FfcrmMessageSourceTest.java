package com.fatfreecrm.service.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.config.I18nProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.DefaultMessageSourceResolvable;

/** The Spring {@code MessageSource} facade over the Rails-generated bundles. */
class FfcrmMessageSourceTest {

    private static Properties props(String... pairs) {
        Properties props = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            props.setProperty(pairs[i], pairs[i + 1]);
        }
        return props;
    }

    private static FfcrmMessageSource source() {
        Map<String, Properties> bundles = new LinkedHashMap<>();
        bundles.put("en-US", props(
            "hello", "Hello",
            "greet", "Hi %{name}",
            "indexed", "%{0} and %{1}",
            "tasks", "{count, plural, =0 {no tasks} one {# task} other {# tasks}}"));
        bundles.put("en", props("only_en", "Base English"));
        bundles.put("de", props("hello", "Hallo"));
        return new FfcrmMessageSource(new I18nService(
            new I18nProperties(List.of("en"), Map.of("en", List.of("en-US"))), bundles));
    }

    @Test
    void resolvesThroughTheRailsFallbackChain() {
        FfcrmMessageSource source = source();

        assertThat(source.getMessage("hello", null, Locale.GERMAN)).isEqualTo("Hallo");
        assertThat(source.getMessage("hello", null, Locale.forLanguageTag("de-AT"))).isEqualTo("Hallo");
        assertThat(source.getMessage("only_en", null, Locale.GERMAN)).isEqualTo("Base English");
        assertThat(source.getMessage("only_en", null, Locale.forLanguageTag("en-US"))).isEqualTo("Base English");
        // "de" falls back to "en" (fallback default) but never to the en-US regional bundle.
        assertThatThrownBy(() -> source.getMessage("greet", null, Locale.GERMAN))
            .isInstanceOf(NoSuchMessageException.class);
    }

    @Test
    void nullLocaleFallsBackToEnUs() {
        assertThat(source().getMessage("hello", null, null)).isEqualTo("Hello");
    }

    @Test
    void mapArgumentsAreNamedAndPositionalArgumentsAreIndexed() {
        FfcrmMessageSource source = source();
        Locale locale = Locale.US;

        assertThat(source.getMessage("greet", new Object[] {Map.of("name", "Ann")}, locale)).isEqualTo("Hi Ann");
        assertThat(source.getMessage("indexed", new Object[] {"left", "right"}, locale)).isEqualTo("left and right");
        assertThat(source.getMessage("tasks", new Object[] {Map.of("count", 0)}, locale)).isEqualTo("no tasks");
        assertThat(source.getMessage("tasks", new Object[] {Map.of("count", 1)}, locale)).isEqualTo("1 task");
        assertThat(source.getMessage("tasks", new Object[] {Map.of("count", 3)}, locale)).isEqualTo("3 tasks");
    }

    @Test
    void railsInterpolationWithoutArgsReturnsTheRawPattern() {
        assertThat(source().getMessage("greet", null, Locale.US)).isEqualTo("Hi %{name}");
        assertThat(source().getMessage("greet", new Object[0], Locale.US)).isEqualTo("Hi %{name}");
    }

    @Test
    void missingCodesUseTheDefaultMessageOrThrow() {
        FfcrmMessageSource source = source();

        assertThat(source.getMessage("nope", null, "fallback", Locale.US)).isEqualTo("fallback");
        assertThat(source.getMessage("nope", null, null, Locale.US)).isNull();
        assertThatThrownBy(() -> source.getMessage("nope", null, Locale.US))
            .isInstanceOf(NoSuchMessageException.class)
            .hasMessageContaining("nope");
    }

    @Test
    void resolvableUsesTheFirstKnownCodeThenDefaultThenThrowsWithLastCode() {
        FfcrmMessageSource source = source();

        assertThat(source.getMessage(new DefaultMessageSourceResolvable(
            new String[] {"missing", "greet"}, new Object[] {Map.of("name", "Zed")}, "dflt"), Locale.US))
            .isEqualTo("Hi Zed");
        assertThat(source.getMessage(new DefaultMessageSourceResolvable(
            new String[] {"missing"}, null, "dflt"), Locale.US)).isEqualTo("dflt");
        assertThatThrownBy(() -> source.getMessage(
            new DefaultMessageSourceResolvable(new String[] {"first", "last"}), Locale.US))
            .isInstanceOf(NoSuchMessageException.class)
            .hasMessageContaining("last");
        assertThatThrownBy(() -> source.getMessage(new DefaultMessageSourceResolvable((String[]) null), Locale.US))
            .isInstanceOf(NoSuchMessageException.class);
    }
}
