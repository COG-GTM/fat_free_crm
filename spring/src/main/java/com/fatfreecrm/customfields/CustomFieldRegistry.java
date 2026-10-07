package com.fatfreecrm.customfields;

import com.fatfreecrm.config.CustomFieldsProperties;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.domain.support.RailsYaml;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Caches custom-field definitions and physical columns, refreshing when their
 * content hashes change after the configured TTL.
 */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are intentionally retained by this component."
)
public class CustomFieldRegistry {

    private static final List<RailsModelType> CUSTOM_FIELD_MODELS = List.of(
        RailsModelType.ACCOUNT, RailsModelType.CAMPAIGN, RailsModelType.CONTACT,
        RailsModelType.LEAD, RailsModelType.OPPORTUNITY, RailsModelType.TASK);

    private final JdbcTemplate jdbcTemplate;
    private final CustomFieldsProperties properties;
    private Map<RailsModelType, List<CustomFieldDefinition>> definitions = Map.of();
    private Map<RailsModelType, Map<String, String>> physicalColumnTypes = Map.of();
    private Instant checkedAt = Instant.MIN;
    private Fingerprint fingerprint;
    private boolean invalidated = true;

    public CustomFieldRegistry(JdbcTemplate jdbcTemplate, CustomFieldsProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public List<CustomFieldDefinition> definitionsFor(RailsModelType klass) {
        ensureFresh();
        return definitions.getOrDefault(klass, List.of());
    }

    public Optional<CustomFieldDefinition> find(RailsModelType klass, String name) {
        return definitionsFor(klass).stream().filter(definition -> definition.name().equals(name)).findFirst();
    }

    public Set<String> physicalColumns(RailsModelType klass) {
        ensureFresh();
        return Set.copyOf(physicalColumnTypes.getOrDefault(klass, Map.of()).keySet());
    }

    public Map<String, String> physicalColumnTypes(RailsModelType klass) {
        ensureFresh();
        return physicalColumnTypes.getOrDefault(klass, Map.of());
    }

    public synchronized void invalidate() {
        checkedAt = Instant.MIN;
        invalidated = true;
    }

    private synchronized void ensureFresh() {
        Instant now = Instant.now();
        if (!invalidated
            && java.time.Duration.between(checkedAt, now).toMillis() < properties.registryTtl().toMillis()
            && fingerprint != null) {
            return;
        }
        Fingerprint current = fingerprint();
        if (invalidated || fingerprint == null || !current.equals(fingerprint)) {
            reload();
            fingerprint = current;
        }
        invalidated = false;
        checkedAt = now;
    }

    private Fingerprint fingerprint() {
        String fields = jdbcTemplate.queryForObject(
            "SELECT coalesce(md5(string_agg(f::text, '|' ORDER BY f.id)), '') FROM fields f",
            String.class);
        String groups = jdbcTemplate.queryForObject(
            "SELECT coalesce(md5(string_agg(g::text, '|' ORDER BY g.id)), '') FROM field_groups g",
            String.class);
        String physicalColumns = jdbcTemplate.queryForObject(
            "SELECT coalesce(md5(string_agg(table_name || '.' || column_name || ':' || data_type, '|' "
                + "ORDER BY table_name, column_name)), '') "
                + "FROM information_schema.columns "
                + "WHERE table_schema = current_schema() "
                + "AND table_name IN ('accounts', 'campaigns', 'contacts', 'leads', 'opportunities', 'tasks') "
                + "AND column_name LIKE 'cf\\_%' ESCAPE '\\'",
            String.class);
        return new Fingerprint(fields, groups, physicalColumns);
    }

    private void reload() {
        Map<RailsModelType, List<CustomFieldDefinition>> nextDefinitions = new EnumMap<>(RailsModelType.class);
        // Rails' Field.custom_fields scope is type != 'CoreField'; SQL's != also excludes NULL.
        List<CustomFieldDefinition> rows = jdbcTemplate.query(
            "SELECT f.id, f.type, f.field_group_id, g.klass_name, g.position AS group_position, "
                + "f.position, f.name, f.label, f.hint, f.placeholder, f.\"as\", f.collection, "
                + "f.disabled, f.required, f.minlength, f.maxlength, f.pair_id, f.settings "
                + "FROM fields f JOIN field_groups g ON g.id = f.field_group_id "
                + "WHERE f.type <> 'CoreField' "
                + "ORDER BY g.position, f.position, f.id",
            this::mapDefinition);
        for (CustomFieldDefinition row : rows) {
            if (row.klass() != null) {
                nextDefinitions.computeIfAbsent(row.klass(), ignored -> new ArrayList<>()).add(row);
            }
        }
        Map<RailsModelType, Map<String, String>> nextPhysical = new EnumMap<>(RailsModelType.class);
        for (RailsModelType type : CUSTOM_FIELD_MODELS) {
            Map<String, String> columns = new LinkedHashMap<>();
            jdbcTemplate.query(
                "SELECT column_name, data_type FROM information_schema.columns "
                    + "WHERE table_schema = current_schema() AND table_name = ? "
                    + "AND column_name LIKE 'cf\\_%' ESCAPE '\\' ORDER BY ordinal_position",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                    columns.put(rs.getString("column_name"), rs.getString("data_type")),
                tableName(type));
            nextPhysical.put(type, Map.copyOf(columns));
        }
        Map<RailsModelType, List<CustomFieldDefinition>> frozen = new EnumMap<>(RailsModelType.class);
        nextDefinitions.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        definitions = Map.copyOf(frozen);
        physicalColumnTypes = Map.copyOf(nextPhysical);
    }

    private CustomFieldDefinition mapDefinition(ResultSet rs, int rowNum) throws SQLException {
        Optional<RailsModelType> klass = RailsModelType.fromRailsName(rs.getString("klass_name"));
        return new CustomFieldDefinition(
            rs.getLong("id"),
            rs.getString("type"),
            nullableLong(rs, "field_group_id"),
            klass.orElse(null),
            nullableInt(rs, "group_position"),
            nullableInt(rs, "position"),
            rs.getString("name"),
            rs.getString("label"),
            rs.getString("hint"),
            rs.getString("placeholder"),
            rs.getString("as"),
            readCollection(rs.getString("collection")),
            (Boolean) rs.getObject("disabled"),
            (Boolean) rs.getObject("required"),
            nullableInt(rs, "minlength"),
            nullableInt(rs, "maxlength"),
            nullableLong(rs, "pair_id"),
            readSettings(rs.getString("settings")));
    }

    private static List<String> readCollection(String yaml) {
        return yaml == null ? List.of() : RailsYaml.readStringList(yaml);
    }

    private static Map<String, Object> readSettings(String yaml) {
        return yaml == null ? Map.of() : RailsYaml.readStringMap(yaml);
    }

    private static Integer nullableInt(ResultSet rs, String name) throws SQLException {
        Object value = rs.getObject(name);
        return value == null ? null : ((Number) value).intValue();
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        Object value = rs.getObject(name);
        return value == null ? null : ((Number) value).longValue();
    }

    public static String tableName(RailsModelType type) {
        return switch (type) {
            case ACCOUNT -> "accounts";
            case CAMPAIGN -> "campaigns";
            case CONTACT -> "contacts";
            case LEAD -> "leads";
            case OPPORTUNITY -> "opportunities";
            case TASK -> "tasks";
            default -> throw new IllegalArgumentException("No custom-fields table for " + type);
        };
    }

    private record Fingerprint(String fieldsHash, String groupsHash, String physicalColumnsHash) {
    }

}
