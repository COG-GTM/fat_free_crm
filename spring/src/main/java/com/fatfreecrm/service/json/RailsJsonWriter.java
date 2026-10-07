package com.fatfreecrm.service.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.support.RailsYaml;
import com.fatfreecrm.repository.RailsRow;
import com.fatfreecrm.repository.RailsRowRepository;
import com.fatfreecrm.repository.TaggingRepository;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RailsJsonWriter {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final RailsRowRepository rowRepository;
    private final TaggingRepository taggingRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RailsJsonWriter(
        RailsRowRepository rowRepository,
        TaggingRepository taggingRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.rowRepository = rowRepository;
        this.taggingRepository = taggingRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ObjectNode> write(RailsResource resource, List<Long> idsInOrder) {
        if (idsInOrder.isEmpty()) {
            return List.of();
        }
        List<RailsRow> rows = rowRepository.findByIds(resource, idsInOrder);
        Map<Long, RailsRow> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(row.id(), row));
        Map<Long, List<String>> tags = tagsById(resource, idsInOrder);
        Set<String> checkBoxColumns = checkBoxColumns(resource, rows);
        List<ObjectNode> result = new ArrayList<>(idsInOrder.size());
        for (Long id : idsInOrder) {
            RailsRow row = byId.get(id);
            if (row != null) {
                result.add(writeRow(resource, row, tags, checkBoxColumns));
            }
        }
        return List.copyOf(result);
    }

    public ObjectNode writeOne(RailsResource resource, long id) {
        List<RailsRow> rows = rowRepository.findByIds(resource, List.of(id));
        if (rows.isEmpty()) {
            throw new jakarta.persistence.EntityNotFoundException(
                resource.railsModel() + " with id " + id + " was not found");
        }
        Map<Long, List<String>> tags = tagsById(resource, List.of(id));
        return writeRow(resource, rows.getFirst(), tags, checkBoxColumns(resource, rows));
    }

    /** A future User resource should layer its Rails {@code to_json} rules on this safety filter. */
    static boolean shouldSerializeColumn(RailsResource resource, String column) {
        return !resource.excludedColumns().contains(column)
            && !column.matches("(?i).*(password|token|salt).*");
    }

    private ObjectNode writeRow(
        RailsResource resource,
        RailsRow row,
        Map<Long, List<String>> tagsById,
        Set<String> checkBoxColumns
    ) {
        ObjectNode object = objectMapper.createObjectNode();
        for (Map.Entry<String, Object> column : row.columns().entrySet()) {
            String name = column.getKey();
            if (!shouldSerializeColumn(resource, name)) {
                continue;
            }
            Object value = column.getValue();
            if (resource.yamlArrayColumns().contains(name)) {
                object.set(name, yamlArray(value, true));
            } else if (checkBoxColumns.contains(name)) {
                object.set(name, yamlArray(value, false));
            } else {
                object.set(name, jsonValue(value, row.typeNames().get(name)));
            }
        }
        if (resource.taggable()) {
            ArrayNode tagList = object.putArray("tag_list");
            tagsById.getOrDefault(row.id(), List.of()).forEach(tagList::add);
        }
        return object;
    }

    private JsonNode jsonValue(Object value, String typeName) {
        if (value == null) {
            return NODES.nullNode();
        }
        String type = typeName == null ? "" : typeName.toLowerCase(java.util.Locale.ROOT);
        return switch (type) {
            case "int2", "smallint", "smallserial", "serial2" ->
                NODES.numberNode(((Number) value).shortValue());
            case "int4", "integer", "serial", "serial4" -> NODES.numberNode(((Number) value).intValue());
            case "int8", "bigint", "bigserial", "serial8" -> NODES.numberNode(((Number) value).longValue());
            case "float4", "real" -> NODES.numberNode(((Number) value).floatValue());
            case "float8", "double precision" -> NODES.numberNode(((Number) value).doubleValue());
            case "numeric", "decimal" -> NODES.textNode(decimal(value));
            case "bool", "boolean" -> NODES.booleanNode((Boolean) value);
            case "timestamp", "timestamp without time zone", "timestamptz", "timestamp with time zone" ->
                NODES.textNode(TIMESTAMP.format(timestamp(value).truncatedTo(ChronoUnit.MILLIS)));
            case "date" -> NODES.textNode(date(value).toString());
            case "json", "jsonb" -> jsonValueNode(value);
            default -> NODES.textNode(value.toString());
        };
    }

    private JsonNode jsonValueNode(Object value) {
        try {
            return objectMapper.readTree(value.toString());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid PostgreSQL JSON value", exception);
        }
    }

    private static String decimal(Object value) {
        BigDecimal decimal = value instanceof BigDecimal bigDecimal
            ? bigDecimal
            : new BigDecimal(value.toString());
        String text = decimal.stripTrailingZeros().toPlainString();
        return text.contains(".") ? text : text + ".0";
    }

    private static Instant timestamp(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case Timestamp timestamp -> timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
            case LocalDateTime localDateTime -> localDateTime.toInstant(ZoneOffset.UTC);
            case OffsetDateTime offsetDateTime -> offsetDateTime.toInstant();
            default -> throw new IllegalArgumentException("Unsupported PostgreSQL timestamp value: " + value);
        };
    }

    private static LocalDate date(Object value) {
        return switch (value) {
            case LocalDate localDate -> localDate;
            case Date date -> date.toLocalDate();
            default -> LocalDate.parse(value.toString());
        };
    }

    private ArrayNode yamlArray(Object value, boolean integers) {
        ArrayNode result = objectMapper.createArrayNode();
        if (value == null || value.toString().isBlank()) {
            return result;
        }
        Object parsed = RailsYaml.read(value.toString());
        if (parsed == null) {
            return result;
        }
        if (!(parsed instanceof Collection<?> items)) {
            throw new IllegalArgumentException("Expected a YAML sequence");
        }
        for (Object item : items) {
            if (integers) {
                result.add(item instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(item.toString()));
            } else {
                result.add(item == null ? "" : item.toString());
            }
        }
        return result;
    }

    private Map<Long, List<String>> tagsById(RailsResource resource, List<Long> ids) {
        Map<Long, List<String>> tags = new LinkedHashMap<>();
        if (!resource.taggable() || ids.isEmpty()) {
            return tags;
        }
        List<Integer> taggableIds = ids.stream().map(Math::toIntExact).toList();
        taggingRepository.findByTaggableTypeAndContextAndTaggableIdInOrderById(
            resource.railsModel(), "tags", taggableIds
        ).forEach(tagging -> {
            if (tagging.getTag() != null) {
                tags.computeIfAbsent(tagging.getTaggableId().longValue(), ignored -> new ArrayList<>())
                    .add(tagging.getTag().getName());
            }
        });
        return tags;
    }

    private Set<String> checkBoxColumns(RailsResource resource, List<RailsRow> rows) {
        Set<String> dynamicColumns = new LinkedHashSet<>();
        rows.forEach(row -> row.columns().keySet().stream()
            .filter(column -> column.startsWith("cf_"))
            .forEach(dynamicColumns::add));
        if (dynamicColumns.isEmpty()) {
            return Set.of();
        }
        List<String> names = jdbcTemplate.query(
            "SELECT f.name FROM fields f JOIN field_groups g ON g.id = f.field_group_id "
                + "WHERE g.klass_name = ? AND f.\"as\" = 'check_boxes'",
            (resultSet, rowNumber) -> resultSet.getString(1),
            resource.railsModel()
        );
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) {
            String column = name.startsWith("cf_") ? name : "cf_" + name;
            if (dynamicColumns.contains(column)) {
                result.add(column);
            }
        }
        return result;
    }
}
