package com.fatfreecrm.customfields;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.support.HasCustomFields;
import com.fatfreecrm.domain.support.RailsModelType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are intentionally retained by this service."
)
public class CustomFieldConsistencyCheck {

    private static final int MAX_REPORTED_IDS = 100;

    private final CustomFieldRegistry registry;
    private final CustomFieldReadService readService;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public CustomFieldConsistencyCheck(
        CustomFieldRegistry registry,
        CustomFieldReadService readService,
        JdbcTemplate jdbcTemplate,
        EntityManager entityManager,
        ObjectMapper objectMapper
    ) {
        this.registry = registry;
        this.readService = readService;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    public CustomFieldConsistencyReport check(RailsModelType type) {
        Map<String, Long> keyDrift = new LinkedHashMap<>();
        List<Long> driftedIds = new ArrayList<>();
        long rows = 0;
        long driftedRows = 0;
        List<CustomFieldDefinition> definitions = registry.definitionsFor(type);
        List<String> columns = registry.physicalColumns(type).stream()
            .filter(name -> name.matches("cf_[a-z0-9_]+"))
            .sorted()
            .toList();
        String projection = columns.isEmpty() ? "" : ", " + String.join(", ", columns);
        String sql = "SELECT id" + projection + " FROM " + CustomFieldRegistry.tableName(type) + " ORDER BY id";
        List<Map<String, Object>> records = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String column : columns) {
                values.put(column, normalizeJdbcValue(rs.getObject(column)));
            }
            values.put("id", rs.getLong("id"));
            return values;
        });
        for (Map<String, Object> record : records) {
            rows++;
            Long id = (Long) record.remove("id");
            HasCustomFields entity = findEntity(type, id);
            if (entity == null) {
                continue;
            }
            Map<String, Object> dualRead = CustomFieldsDualReader.read(
                record, entity.getCustomFields(), definitions);
            Map<String, Object> jsonbOnly = readService.valuesFor(entity);
            Set<String> keys = new LinkedHashSet<>(dualRead.keySet());
            keys.addAll(jsonbOnly.keySet());
            List<String> driftedKeys = new ArrayList<>();
            for (String key : keys) {
                if (!equivalent(dualRead.get(key), jsonbOnly.get(key))) {
                    keyDrift.merge(key, 1L, Long::sum);
                    driftedKeys.add(key);
                }
            }
            if (!driftedKeys.isEmpty()) {
                driftedRows++;
                if (driftedIds.size() < MAX_REPORTED_IDS) {
                    driftedIds.add(id);
                }
            }
        }
        return new CustomFieldConsistencyReport(Map.of(
            CustomFieldRegistry.tableName(type),
            new CustomFieldConsistencyReport.TableReport(rows, driftedRows, List.copyOf(driftedIds), keyDrift)));
    }

    public Map<String, CustomFieldConsistencyReport.TableReport> checkAll() {
        Map<String, CustomFieldConsistencyReport.TableReport> reports = new LinkedHashMap<>();
        for (RailsModelType type : customFieldModels()) {
            reports.putAll(check(type).tables());
        }
        return Map.copyOf(reports);
    }

    private HasCustomFields findEntity(RailsModelType type, Long id) {
        Object entity = entityManager.find(type.entityClass(), id);
        return entity instanceof HasCustomFields customFields ? customFields : null;
    }

    private static Object normalizeJdbcValue(Object value) {
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString() + "Z";
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        return value;
    }

    private static boolean equivalent(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return new BigDecimal(leftNumber.toString()).compareTo(new BigDecimal(rightNumber.toString())) == 0;
        }
        return Objects.deepEquals(left, right);
    }

    private static List<RailsModelType> customFieldModels() {
        return List.of(RailsModelType.ACCOUNT, RailsModelType.CAMPAIGN, RailsModelType.CONTACT,
            RailsModelType.LEAD, RailsModelType.OPPORTUNITY, RailsModelType.TASK);
    }
}
