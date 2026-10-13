package com.fatfreecrm.service.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.config.I18nProperties;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.DefaultMessageSourceResolvable;

/**
 * The {@code messageSource} bean must honour Spring's MessageSource contract (default message or
 * {@link NoSuchMessageException}) while resolving through the Rails catalogs, and never leak the Rails
 * "Translation missing" string that {@link I18nService#t} returns.
 */
class FfcrmMessageSourceTest {

    private static final Locale DE = Locale.forLanguageTag("de");

    private static FfcrmMessageSource source() {
        Properties en = new Properties();
        en.setProperty("greeting", "Hello %{0}, you have %{1} tasks");
        en.setProperty("named", "Dear %{name}");
        en.setProperty("plain", "Plain text");
        Properties enBase = new Properties();
        enBase.setProperty("en_only", "Only in English");
        Properties de = new Properties();
        de.setProperty("plain", "Nur Text");
        I18nService service = new I18nService(
            new I18nProperties(List.of("en"), Map.of("en", List.of("en-US"))),
            Map.of("en-US", en, "en", enBase, "de", de));
        return new FfcrmMessageSource(service);
    }

    @Test
    void missingCodeReturnsTheDefaultMessageNeverTranslationMissing() {
        assertThat(source().getMessage("nope", null, "fallback", Locale.US)).isEqualTo("fallback");
        assertThat(source().getMessage("nope", null, null, Locale.US)).isNull();
    }

    @Test
    void missingCodeWithoutDefaultThrowsNoSuchMessage() {
        assertThatThrownBy(() -> source().getMessage("nope", null, Locale.US))
            .isInstanceOf(NoSuchMessageException.class)
            .hasMessageContaining("nope");
    }

    @Test
    void positionalArgumentsMapToRailsIndexedInterpolations() {
        assertThat(source().getMessage("greeting", new Object[] {"Alice", 3}, Locale.US))
            .isEqualTo("Hello Alice, you have 3 tasks");
    }

    @Test
    void singleMapArgumentIsTreatedAsNamedInterpolations() {
        assertThat(source().getMessage("named", new Object[] {Map.of("name", "Bob")}, Locale.US))
            .isEqualTo("Dear Bob");
    }

    @Test
    void noArgumentsReturnsTheRawPatternLikeRails() {
        assertThat(source().getMessage("named", null, Locale.US)).isEqualTo("Dear %{name}");
        assertThat(source().getMessage("plain", new Object[0], "x", Locale.US)).isEqualTo("Plain text");
    }

    @Test
    void nullLocaleFallsBackToEnUs() {
        assertThat(source().getMessage("plain", null, null)).isEqualTo("Plain text");
        assertThat(source().getMessage("plain", null, "d", null)).isEqualTo("Plain text");
    }

    @Test
    void localeCatalogsAreHonouredWithRailsFallbacks() {
        assertThat(source().getMessage("plain", null, DE)).isEqualTo("Nur Text");
        assertThat(source().getMessage("en_only", null, DE)).isEqualTo("Only in English");
        assertThat(source().getMessage("en_only", null, Locale.US)).isEqualTo("Only in English");
        assertThatThrownBy(() -> source().getMessage("named", null, DE)).isInstanceOf(NoSuchMessageException.class);
    }

    @Test
    void resolvableUsesTheFirstKnownCodeThenDefaultThenThrows() {
        assertThat(source().getMessage(
            new DefaultMessageSourceResolvable(new String[] {"missing.first", "named"},
                new Object[] {Map.of("name", "Cy")}, "unused default"), Locale.US))
            .isEqualTo("Dear Cy");
        assertThat(source().getMessage(
            new DefaultMessageSourceResolvable(new String[] {"missing.first", "missing.second"}, null, "the default"),
            Locale.US))
            .isEqualTo("the default");
        assertThatThrownBy(() -> source().getMessage(
            new DefaultMessageSourceResolvable(new String[] {"missing.first", "missing.second"}), Locale.US))
            .isInstanceOf(NoSuchMessageException.class)
            .hasMessageContaining("missing.second");
        assertThatThrownBy(() -> source().getMessage(new DefaultMessageSourceResolvable(new String[0]), Locale.US))
            .isInstanceOf(NoSuchMessageException.class);
    }

    @Test
    void missingInterpolationArgumentStillSurfacesAsTheRailsError() {
        assertThatThrownBy(() -> source().getMessage("named", new Object[] {"positional"}, Locale.US))
            .isInstanceOf(MissingInterpolationArgumentException.class);
    }
}
