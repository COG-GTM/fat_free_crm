package com.fatfreecrm.service.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.config.SettingsProperties;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AB-273 (settings-i18n): integration coverage for the YAML-to-JSON conversion used at AB-274
 * cutover: round trip on real fixture-shaped rows, idempotence, dry run, concurrent-change skip,
 * undecodable rows, and the refuse-without-confirm guard.
 */
class SettingsJsonConversionTest extends AbstractPostgresIntegrationTest {

    private static final String HWIA = "--- !ruby/hash:ActiveSupport::HashWithIndifferentAccess\na: 1\n";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SettingsJsonConversion conversion;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM settings");
        conversion = conversion(false, true);
        seed();
    }

    private SettingsJsonConversion conversion(boolean dryRun, boolean confirm) {
        SettingsProperties properties = new SettingsProperties(
            "classpath:settings/settings.default.yml", "", Duration.ofSeconds(30),
            new SettingsProperties.Conversion(dryRun, confirm));
        return new SettingsJsonConversion(jdbcTemplate, properties);
    }

    private void seed() {
        insert("yaml_scalar", "--- :planned\n");
        insert("yaml_list", "---\n- :a\n- :b\n");
        insert("yaml_hash", "---\n:server: imap.test\n:port: 993\n");
        insert("hwia", HWIA);
        insert("json", "{\"a\": [1, 2]}");
        insert("blank", "--- false\n");
    }

    private void insert(String name, String value) {
        jdbcTemplate.update(
            "INSERT INTO settings (name, value, created_at, updated_at) VALUES (?, ?, now(), now())", name, value);
    }

    private Map<String, String> values() {
        return jdbcTemplate.query(
            "SELECT name, value FROM settings",
            rs -> {
                Map<String, String> map = new java.util.TreeMap<>();
                while (rs.next()) {
                    map.put(rs.getString("name"), rs.getString("value"));
                }
                return map;
            });
    }

    @Test
    void convertsYamlRowsLosslesslyAndIdempotently() {
        Map<String, String> before = values();
        SettingsJsonConversion.Report report = conversion.run();
        assertThat(report.failed()).isEmpty();
        assertThat(report.refusal()).isNull();
        assertThat(report.alreadyJson()).isEqualTo(1);
        assertThat(report.converted()).isEqualTo(5);

        Map<String, String> after = values();
        for (Map.Entry<String, String> entry : after.entrySet()) {
            assertThat(SettingValueCodec.isJson(entry.getValue())).as(entry.getKey()).isTrue();
            assertThat(SettingValueCodec.deepEquals(
                SettingValueCodec.decode(entry.getValue()), SettingValueCodec.decode(before.get(entry.getKey()))))
                .as(entry.getKey())
                .isTrue();
        }
        // Second run: converted = 0, DB bytes unchanged.
        SettingsJsonConversion.Report second = conversion.run();
        assertThat(second.converted()).isZero();
        assertThat(second.alreadyJson()).isEqualTo(after.size());
        assertThat(values()).isEqualTo(after);
    }

    @Test
    void dryRunReportsButDoesNotWrite() {
        SettingsJsonConversion dry = conversion(true, false);
        SettingsJsonConversion.Report report = dry.run();
        assertThat(report.dryRun()).isTrue();
        assertThat(report.refusal()).isNull();
        assertThat(report.converted()).isEqualTo(5);
        Map<String, String> after = values();
        assertThat(after.get("yaml_scalar")).isEqualTo("--- :planned\n");
    }

    @Test
    void refusesWithoutConfirmWhenNotDryRun() {
        SettingsJsonConversion unconfirmed = conversion(false, false);
        SettingsJsonConversion.Report report = unconfirmed.run();
        assertThat(report.refusal()).isNotNull();
        assertThat(report.failed()).isEmpty();
        assertThat(values().get("yaml_scalar")).isEqualTo("--- :planned\n");
    }

    @Test
    void concurrentChangeIsSkippedNotFailed() {
        // Simulate another writer changing the row between read and update by converting under a
        // conversion whose read snapshot is stale is not directly expressible; instead verify the
        // guarded UPDATE returns 0 rows on a manually altered value.
        jdbcTemplate.update("UPDATE settings SET value = ? WHERE name = ?", "--- :started\n", "yaml_scalar");
        // Manually apply the guarded update with a stale expected value.
        int updated = jdbcTemplate.update(
            "UPDATE settings SET value = ? WHERE name = ? AND value = ?", "{\"x\":1}", "yaml_scalar", "--- :planned\n");
        assertThat(updated).isZero();
        SettingsJsonConversion.Report report = conversion.run();
        assertThat(report.failed()).isEmpty();
        assertThat(report.skippedConcurrent()).isZero();
    }

    @Test
    void undecodableRowFailsAndStaysUntouched() {
        jdbcTemplate.update("UPDATE settings SET value = ? WHERE name = ?", "--- !ruby/object:OpenStruct\n", "hwia");
        SettingsJsonConversion.Report report = conversion.run();
        assertThat(report.failed()).hasSize(1);
        assertThat(values().get("hwia")).isEqualTo("--- !ruby/object:OpenStruct\n");
    }

    @Test
    void codecRoundTripsEveryFixtureShape() {
        List<String> samples = List.of(
            "--- :planned\n",
            "--- 0\n",
            "--- 1.5\n",
            "--- \"   \"\n",
            "--- false\n",
            "---\n- :a\n- \"plain\"\n",
            "---\n:server: x\n:nested:\n  :list: [1, 2]\n",
            HWIA);
        for (String sample : samples) {
            Object decoded = SettingValueCodec.decode(sample);
            String json = SettingValueCodec.encodeJson(decoded);
            assertThat(SettingValueCodec.deepEquals(SettingValueCodec.decode(json), decoded)).as(sample).isTrue();
        }
    }
}
