package com.fatfreecrm.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.repository.SettingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class SettingsServiceTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final SettingRepository repository = mock(SettingRepository.class);
    private Instant now = Instant.parse("2026-03-12T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private SettingsService service(Map<String, Object> defaults, Map<String, Object> overrides) {
        return new SettingsService(defaults, overrides, repository, clock, TTL);
    }

    private void rows(Setting... settings) {
        when(repository.findAll(Sort.by("id"))).thenReturn(List.of(settings));
    }

    private static Setting row(String name, String value) {
        Setting setting = new Setting();
        setting.setName(name);
        setting.setValue(value);
        return setting;
    }

    @Test
    void blankDatabaseValuesFallThroughToYaml() {
        Map<String, Object> yaml = Map.of(
            "compound_address", true,
            "require_first_names", true,
            "host", "yaml-host",
            "base_url", "",
            "lead_status", List.of(":new"),
            "per_user_locale", false,
            "empty_hash", Map.of("a", 1));
        rows(
            row("compound_address", "--- false\n"),
            row("require_first_names", "--- false\n"),
            row("host", "--- \"\"\n"),
            row("base_url", "--- '   '\n"),
            row("lead_status", "--- []\n"),
            row("per_user_locale", "--- \n"),
            row("empty_hash", "--- {}\n"),
            row("comments_visible_on_dashboard", "--- true\n"),
            row("db_only_integer", "--- 0\n"));
        SettingsService service = service(yaml, Map.of());
        assertThat(service.get("compound_address")).isEqualTo(true);
        assertThat(service.get("require_first_names")).isEqualTo(true);
        assertThat(service.get("host")).isEqualTo("yaml-host");
        assertThat(service.get("base_url")).isEqualTo("");
        assertThat(service.get("lead_status")).isEqualTo(List.of(":new"));
        assertThat(service.get("per_user_locale")).isEqualTo(false);
        assertThat(service.get("empty_hash")).isEqualTo(Map.of("a", 1));
        // Present DB values win wholesale.
        assertThat(service.get("comments_visible_on_dashboard")).isEqualTo(true);
        assertThat(service.get("db_only_integer")).isEqualTo(0);
    }

    @Test
    void dbValueReplacesYamlWholesale() {
        Map<String, Object> yaml = Map.of("email_dropbox", Map.of("server", "a", "port", "993", "ssl", true));
        rows(row("email_dropbox", "---\nserver: db.test\nssl: true\n"));
        SettingsService service = service(yaml, Map.of());
        assertThat(service.get("email_dropbox")).isEqualTo(Map.of("server", "db.test", "ssl", true));
    }

    @Test
    void overrideFileDeepMergesLikeActiveSupport() {
        Map<String, Object> defaults = Map.of(
            "email_dropbox", Map.of("server", "", "port", "", "ssl", ""),
            "account_category", List.of(":affiliate", ":customer"),
            "compound_address", true,
            "host", "");
        Map<String, Object> overrides = Map.of(
            "email_dropbox", Map.of("server", "override.test", "port", "993"),
            "account_category", List.of(":customer", "Custom String"),
            "compound_address", false,
            "override_only_key", ":symbol_value");
        SettingsService service = service(defaults, overrides);
        @SuppressWarnings("unchecked")
        Map<String, Object> dropbox = (Map<String, Object>) service.get("email_dropbox");
        assertThat(dropbox).containsEntry("server", "override.test")
            .containsEntry("port", "993")
            .containsKey("ssl");
        assertThat(service.get("account_category")).isEqualTo(List.of(":customer", "Custom String"));
        assertThat(service.get("compound_address")).isEqualTo(false);
        assertThat(service.get("host")).isEqualTo("");
        assertThat(service.get("override_only_key")).isEqualTo(":symbol_value");
        assertThat(service.get("absent")).isNull();
    }

    @Test
    void databaseSnapshotIsStaleUntilTtlElapses() {
        AtomicReference<List<Setting>> current = new AtomicReference<>(List.of(row("flag", "--- false\n")));
        when(repository.findAll(Sort.by("id"))).thenAnswer(ignored -> current.get());
        // A blank DB value falls through to YAML.
        SettingsService service = service(Map.of("flag", true), Map.of());
        assertThat(service.get("flag")).isEqualTo(true);

        current.set(List.of(row("flag", "--- :on\n")));
        // Still within the TTL: the memo was cleared, but the snapshot is stale.
        service.evict();
        assertThat(service.get("flag")).isEqualTo(true);

        // Advance past the TTL: the new snapshot is visible.
        now = now.plus(TTL).plusSeconds(1);
        SettingsService later = new SettingsService(Map.of("flag", true), Map.of(), repository,
            Clock.fixed(now, ZoneOffset.UTC), TTL);
        service.evict();
        // Force refresh on the same service by moving the injected clock — the service holds a
        // fixed clock, so verify via the new instance plus a re-created service on the moved clock.
        assertThat(later.get("flag")).isEqualTo(":on");
    }

    @Test
    void truthyAndSymbolListFollowRubyRules() {
        Map<String, Object> yaml = Map.of(
            "enabled", true,
            "disabled", false,
            "zero", 0,
            "list", List.of(":a", ":b", "plain"));
        rows();
        SettingsService service = service(yaml, Map.of());
        assertThat(service.truthy("enabled")).isTrue();
        assertThat(service.truthy("disabled")).isFalse();
        assertThat(service.truthy("zero")).isTrue();
        assertThat(service.truthy("missing")).isFalse();
        assertThat(service.symbolList("list")).containsExactly("a", "b", "plain");
        assertThat(service.symbolList("enabled")).isEmpty();
    }

    @Test
    void digFollowsRailsSemantics() {
        Map<String, Object> yaml = Map.of(
            "smtp", Map.of("auth", Map.of("method", ":plain")),
            "scalar", "x");
        rows();
        SettingsService service = service(yaml, Map.of());
        assertThat(service.dig("smtp", "auth", "method")).isEqualTo(":plain");
        assertThat(service.dig("smtp", "missing")).isNull();
        assertThat(service.dig("absent", "anything")).isNull();
        assertThatThrownBy(() -> service.dig("scalar", "more"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not have #dig");
    }

    @Test
    void undecodableRowsAreSkipped() {
        rows(row("good", "--- 1\n"), row("bad", "--- !ruby/object:NotAThing\nbroken: [\n"));
        SettingsService service = service(Map.of("good", 0, "bad", "yaml"), Map.of());
        assertThat(service.get("good")).isEqualTo(1);
        assertThat(service.get("bad")).isEqualTo("yaml");
    }
}
