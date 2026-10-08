package com.fatfreecrm.service.settings;

import com.fatfreecrm.config.SettingsProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * AB-273 (settings-i18n): converts {@code settings.value} YAML text to strict JSON for the AB-274
 * cutover. Must never run while Rails is still writing the table; the non-dry-run mode therefore
 * refuses unless {@code ffcrm.settings.conversion.confirm-rails-stopped=true}.
 *
 * <p>Per row: already-JSON values are skipped (idempotent); otherwise the value is decoded,
 * re-encoded as JSON, and verified by {@link SettingValueCodec#deepEquals} against the decoded
 * original (a mismatch counts as failed, nothing is written). The write uses
 * {@code UPDATE settings SET value = ? WHERE id = ? AND value = ?} as a concurrent-change guard;
 * {@code updated_at} is deliberately left untouched.
 */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are intentionally retained by this service."
)
public class SettingsJsonConversion {

    private final JdbcTemplate jdbcTemplate;
    private final SettingsProperties properties;

    public SettingsJsonConversion(JdbcTemplate jdbcTemplate, SettingsProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public Report run() {
        boolean dryRun = properties.conversion().dryRun();
        if (!dryRun && !properties.conversion().confirmRailsStopped()) {
            return Report.refused(
                "Refusing to convert without ffcrm.settings.conversion.confirm-rails-stopped=true; "
                    + "never run the YAML-to-JSON conversion while Rails is still writing settings rows");
        }

        List<Row> rows = jdbcTemplate.query(
            "SELECT id, name, value FROM settings ORDER BY id",
            (rs, i) -> new Row(rs.getLong("id"), rs.getString("name"), rs.getString("value")));

        int alreadyJson = 0;
        int converted = 0;
        int skippedConcurrent = 0;
        List<Failure> failed = new ArrayList<>();
        for (Row row : rows) {
            if (SettingValueCodec.isJson(row.value())) {
                alreadyJson++;
                continue;
            }
            Object decoded;
            String json;
            try {
                decoded = SettingValueCodec.decode(row.value());
                json = SettingValueCodec.encodeJson(decoded);
                if (!SettingValueCodec.deepEquals(SettingValueCodec.decode(json), decoded)) {
                    failed.add(new Failure(row.id(), "JSON round trip changed the decoded value"));
                    continue;
                }
            } catch (RuntimeException exception) {
                failed.add(new Failure(row.id(), "undecodable value: " + exception.getClass().getSimpleName()));
                continue;
            }
            if (dryRun) {
                converted++;
                continue;
            }
            int updated = jdbcTemplate.update(
                "UPDATE settings SET value = ? WHERE id = ? AND value = ?",
                json, row.id(), row.value());
            if (updated == 0) {
                skippedConcurrent++;
            } else {
                converted++;
            }
        }
        return new Report(rows.size(), alreadyJson, converted, skippedConcurrent, List.copyOf(failed), dryRun, null);
    }

    public record Row(long id, String name, String value) {
    }

    public record Failure(long id, String reason) {
    }

    public record Report(int total, int alreadyJson, int converted, int skippedConcurrent,
        List<Failure> failed, boolean dryRun, String refusal) {

        public Report {
            failed = List.copyOf(failed);
        }

        static Report refused(String reason) {
            return new Report(0, 0, 0, 0, List.of(), false, reason);
        }

        public boolean ok() {
            return refusal == null && failed.isEmpty();
        }
    }
}
