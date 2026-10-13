package com.fatfreecrm.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AB-273 (settings-i18n): locale fallback configuration mirroring the Rails
 * {@code I18n::Locale::Fallbacks} state captured by {@code ffcrm:migration:i18n_matrix}
 * (config/initializers/locale.rb:15-19).
 *
 * <p>{@code fallbackDefaults} is the defaults list appended to every computed chain (Rails:
 * {@code [en]} — fallbacks were initialised while {@code I18n.default_locale} was still {@code :en},
 * before {@code Setting.locale} is applied in after_initialize). {@code fallbackOverrides} is an
 * exact-chain override per requested tag, mirroring the {@code Hash#[]=} store performed by
 * {@code I18n.fallbacks[:en] = [:"en-US"]}.
 */
@ConfigurationProperties(prefix = "ffcrm.i18n")
public record I18nProperties(List<String> fallbackDefaults, Map<String, List<String>> fallbackOverrides) {

    public I18nProperties {
        fallbackDefaults = fallbackDefaults == null ? List.of("en") : List.copyOf(fallbackDefaults);
        fallbackOverrides = fallbackOverrides == null ? Map.of("en", List.of("en-US")) : Map.copyOf(fallbackOverrides);
    }
}
