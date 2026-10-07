package com.fatfreecrm.customfields;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.config.CustomFieldsProperties;
import com.fatfreecrm.domain.support.RailsModelType;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class CustomFieldsBackfillJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomFieldsBackfillJob.class);
    private static final List<RailsModelType> MODELS = List.of(
        RailsModelType.ACCOUNT, RailsModelType.CAMPAIGN, RailsModelType.CONTACT,
        RailsModelType.LEAD, RailsModelType.OPPORTUNITY, RailsModelType.TASK);
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() { };

    private final CustomFieldRegistry registry;
    private final CheckBoxesYamlCodec yamlCodec;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final CustomFieldsProperties properties;
    private final ObjectMapper objectMapper;
    private final BiConsumer<String, Integer> afterBatch;

    @Autowired
    public CustomFieldsBackfillJob(
        CustomFieldRegistry registry,
        CheckBoxesYamlCodec yamlCodec,
        JdbcTemplate jdbcTemplate,
        TransactionTemplate transactionTemplate,
        CustomFieldsProperties properties,
        ObjectMapper objectMapper
    ) {
        this(registry, yamlCodec, jdbcTemplate, transactionTemplate, properties, objectMapper, (table, batch) -> { });
    }

    CustomFieldsBackfillJob(
        CustomFieldRegistry registry,
        CheckBoxesYamlCodec yamlCodec,
        JdbcTemplate jdbcTemplate,
        TransactionTemplate transactionTemplate,
        CustomFieldsProperties properties,
        ObjectMapper objectMapper,
        BiConsumer<String, Integer> afterBatch
    ) {
        this.registry = registry;
        this.yamlCodec = yamlCodec;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.afterBatch = afterBatch;
    }

    public CustomFieldsBackfillReport run() {
        Map<String, Long> backfilled = new LinkedHashMap<>();
        for (RailsModelType type : MODELS) {
            backfilled.put(CustomFieldRegistry.tableName(type), backfill(type));
        }
        resolveMarkers();
        return print(verifyInternal(backfilled));
    }

    public CustomFieldsBackfillReport verify() {
        return print(verifyInternal(Map.of()));
    }

    private long backfill(RailsModelType type) {
        String table = CustomFieldRegistry.tableName(type);
        Map<String, String> columnTypes = checkboxAwareColumns(type);
        String expression = CustomFieldsBackfill.setBasedExpression(columnTypes);
        Long[] bounds = jdbcTemplate.queryForObject(
            "SELECT min(id), max(id) FROM " + table,
            (rs, rowNum) -> new Long[] {asLong(rs.getObject(1)), asLong(rs.getObject(2))});
        if (bounds == null || bounds[0] == null) {
            return 0;
        }
        long rowsUpdated = 0;
        int batchSize = properties.backfill().batchSize();
        int batchNumber = 0;
        for (long start = bounds[0]; start <= bounds[1];) {
            long end = Math.min(start + batchSize, bounds[1] + 1);
            long batchStart = start;
            long batchEnd = end;
            Integer updated = transactionTemplate.execute(status -> jdbcTemplate.update(
                "UPDATE " + table + " SET custom_fields = " + expression
                    + " WHERE id >= ? AND id < ? AND custom_fields IS DISTINCT FROM " + expression,
                batchStart, batchEnd));
            rowsUpdated += updated == null ? 0 : updated;
            start = end;
            afterBatch.accept(table, ++batchNumber);
        }
        return rowsUpdated;
    }

    private Map<String, String> checkboxAwareColumns(RailsModelType type) {
        Map<String, String> asByName = new LinkedHashMap<>();
        for (CustomFieldDefinition definition : registry.definitionsFor(type)) {
            asByName.put(definition.name(), definition.as());
        }
        Map<String, String> columns = new TreeMap<>();
        for (String column : registry.physicalColumns(type)) {
            if (!column.matches("cf_[a-z0-9_]+")) {
                throw new IllegalStateException("Invalid custom-field column " + column);
            }
            columns.put(column, asByName.getOrDefault(column, ""));
        }
        return columns;
    }

    private void resolveMarkers() {
        for (RailsModelType type : MODELS) {
            String table = CustomFieldRegistry.tableName(type);
            String columns = registry.physicalColumns(type).stream()
                .filter(column -> column.matches("cf_[a-z0-9_]+"))
                .sorted()
                .toList()
                .stream()
                .reduce("", (prefix, column) -> prefix + ", " + column);
            List<MarkerRow> rows = jdbcTemplate.query(
                "SELECT id, custom_fields::text AS custom_fields" + columns
                    + " FROM " + table + " WHERE custom_fields::text LIKE '%\"$yaml\"%' ORDER BY id",
                (rs, rowNum) -> {
                    Map<String, Object> customFields;
                    try {
                        customFields = objectMapper.readValue(rs.getString("custom_fields"), JSON_MAP);
                    } catch (Exception exception) {
                        throw new SQLException("Unable to parse custom_fields JSONB on " + table, exception);
                    }
                    Map<String, Object> columnValues = new LinkedHashMap<>();
                    for (String column : registry.physicalColumns(type)) {
                        if (column.matches("cf_[a-z0-9_]+")) {
                            columnValues.put(column, rs.getObject(column));
                        }
                    }
                    return new MarkerRow(rs.getLong("id"), customFields, columnValues);
                });
            for (MarkerRow row : rows) {
                for (Map.Entry<String, Object> entry : row.customFields().entrySet()) {
                    if (!(entry.getValue() instanceof Map<?, ?> marker)
                        || marker.size() != 1 || !marker.containsKey("$yaml")) {
                        continue;
                    }
                    if (!entry.getKey().matches("cf_[a-z0-9_]+")) {
                        continue;
                    }
                    Object columnValue = row.columnValues().get(entry.getKey());
                    String raw = String.valueOf(columnValue == null ? marker.get("$yaml") : columnValue);
                    List<String> decoded;
                    try {
                        decoded = yamlCodec.decode(raw);
                    } catch (RuntimeException ignored) {
                        continue;
                    }
                    String json;
                    try {
                        json = writeJson(decoded);
                    } catch (RuntimeException ignored) {
                        continue;
                    }
                    try {
                        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
                            "UPDATE " + table + " SET custom_fields = "
                                + "jsonb_set(custom_fields, ?::text[], ?::jsonb, true) WHERE id = ?",
                            "{" + entry.getKey() + "}", json, row.id()));
                    } catch (DataAccessException ignored) {
                        continue;
                    }
                }
            }
        }
    }

    private CustomFieldsBackfillReport verifyInternal(Map<String, Long> backfilled) {
        Map<String, CustomFieldsBackfillReport.TableReport> reports = new LinkedHashMap<>();
        for (RailsModelType type : MODELS) {
            String table = CustomFieldRegistry.tableName(type);
            Map<String, String> columns = checkboxAwareColumns(type);
            String expression = CustomFieldsBackfill.setBasedExpression(columns);
            long rows = queryCount("SELECT count(*) FROM " + table);
            long drift = queryCount(
                "SELECT count(*) FROM " + table + " WHERE custom_fields IS DISTINCT FROM " + expression);
            long markers = queryCount(
                "SELECT count(*) FROM " + table + " row_value WHERE EXISTS ("
                    + "SELECT 1 FROM jsonb_each(row_value.custom_fields) field_value "
                    + "WHERE jsonb_typeof(field_value.value) = 'object' "
                    + "AND jsonb_exists(field_value.value, '$yaml') "
                    + "AND field_value.value - '$yaml' = '{}'::jsonb)");
            Map<String, CustomFieldsBackfillReport.ColumnCounts> counts = new LinkedHashMap<>();
            for (String column : columns.keySet()) {
                long columnCount = queryCount(
                    "SELECT count(*) FROM " + table + " WHERE " + column + " IS NOT NULL");
                long jsonCount = queryCount(
                    "SELECT count(*) FROM " + table + " WHERE custom_fields -> '" + column
                        + "' IS NOT NULL AND custom_fields -> '" + column + "' <> 'null'::jsonb");
                counts.put(column, new CustomFieldsBackfillReport.ColumnCounts(columnCount, jsonCount));
            }
            reports.put(table, new CustomFieldsBackfillReport.TableReport(
                rows, backfilled.getOrDefault(table, 0L), drift, markers, Map.copyOf(counts)));
        }
        return new CustomFieldsBackfillReport(Map.copyOf(reports));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to encode resolved check_boxes value", exception);
        }
    }

    private long queryCount(String sql) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Long.class));
    }

    private static Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private CustomFieldsBackfillReport print(CustomFieldsBackfillReport report) {
        for (Map.Entry<String, CustomFieldsBackfillReport.TableReport> entry : report.tables().entrySet()) {
            var table = entry.getValue();
            String line = "%s rows=%d rowsBackfilled=%d drift=%d markersRemaining=%d columns=%s".formatted(
                entry.getKey(), table.rows(), table.rowsBackfilled(), table.drift(), table.markersRemaining(),
                table.columns());
            LOGGER.info(line);
            System.out.println(line);
        }
        return report;
    }

    private record MarkerRow(long id, Map<String, Object> customFields, Map<String, Object> columnValues) {
    }
}
