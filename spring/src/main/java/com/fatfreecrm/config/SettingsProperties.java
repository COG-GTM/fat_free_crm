package com.fatfreecrm.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AB-273 (settings-i18n): settings tier configuration mirroring Rails
 * {@code lib/fat_free_crm/load_settings.rb:14-19} (defaults file, optional overrides file)
 * plus the database snapshot TTL that replaces Rails' per-request
 * {@code Setting.clear_cache!} (application_controller.rb:109-111).
 */
@ConfigurationProperties(prefix = "ffcrm.settings")
public record SettingsProperties(String defaults, String overrides, Duration cacheTtl, Conversion conversion) {

    public SettingsProperties {
        defaults = defaults == null || defaults.isBlank() ? "classpath:settings/settings.default.yml" : defaults;
        overrides = overrides == null ? "" : overrides;
        cacheTtl = cacheTtl == null ? Duration.ofSeconds(30) : cacheTtl;
        conversion = conversion == null ? new Conversion(null, null) : conversion;
    }

    public record Conversion(Boolean dryRun, Boolean confirmRailsStopped) {

        public Conversion {
            dryRun = dryRun == null ? Boolean.TRUE : dryRun;
            confirmRailsStopped = confirmRailsStopped == null ? Boolean.FALSE : confirmRailsStopped;
        }
    }
}
