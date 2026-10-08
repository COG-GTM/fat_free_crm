package com.fatfreecrm.service.write.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fatfreecrm.customfields.CustomFieldRegistry;
import com.fatfreecrm.domain.Field;
import com.fatfreecrm.domain.FieldGroup;
import com.fatfreecrm.repository.FieldRepository;
import com.fatfreecrm.service.validation.RailsErrors;
import com.fatfreecrm.service.write.RailsInternalError;
import com.fatfreecrm.service.write.RailsParameterMissing;
import com.fatfreecrm.service.write.RailsParams;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Rails {@code Admin::FieldsController} writes, including {@code CustomField}'s runtime DDL:
 * {@code before_create :add_column} ({@code ALTER TABLE … ADD} a {@code cf_*} column, same
 * transaction as the {@code fields} INSERT) and {@code after_validation :update_column} on update
 * ({@code ALTER COLUMN … TYPE} only for {@code SAFE_DB_TRANSITIONS}). Destroy never drops the column
 * (Rails doesn't). {@code acts_as_list} (unscoped) positions are mirrored on create/destroy.
 * {@link CustomFieldRegistry} is invalidated after every DDL/metadata change. No PaperTrail.
 */
@Service
public class AdminFieldWriteService {

    /** {@code Field.field_types}: as → [klass, column type]. */
    private static final Map<String, String[]> FIELD_TYPES = fieldTypes();
    private static final Map<String, String> SQL_TYPES = Map.of(
        "string", "character varying", "text", "text", "boolean", "boolean", "date", "date",
        "timestamp", "timestamp(6) without time zone", "decimal", "numeric(15,2)",
        "integer", "integer", "float", "double precision");
    /** {@code has_fields} models (klass_name → table). Others raise in serialize_custom_fields!. */
    private static final Map<String, String> TABLES = Map.of(
        "Account", "accounts", "Campaign", "campaigns", "Contact", "contacts", "Lead", "leads",
        "Opportunity", "opportunities");
    private static final List<Set<String>> SAFE_ANY =
        List.of(Set.of("date", "time", "timestamp"), Set.of("integer", "float"));
    private static final Pattern INTEGER = Pattern.compile("\\A[+-]?\\d+\\z");

    private final FieldRepository fieldRepository;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final CustomFieldRegistry registry;
    private final Clock clock;

    public AdminFieldWriteService(FieldRepository fieldRepository, EntityManager entityManager,
        JdbcTemplate jdbcTemplate, CustomFieldRegistry registry, Clock clock) {
        this.fieldRepository = fieldRepository;
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
        this.clock = clock;
    }

    /** Outcome of a write whose side effects commit even when Rails then answers 422/500. */
    public record Outcome(RailsErrors errors, String internalError) {
        static Outcome ok() {
            return new Outcome(new RailsErrors(), null);
        }
    }

    /** {@code create}: success commits then Rails 500s on the missing {@code custom_field_url}. */
    @Transactional
    public Outcome create(Map<String, JsonNode> fieldParams, Map<String, JsonNode> pairParams) {
        if (fieldParams == null || fieldParams.isEmpty()) {
            throw new RailsParameterMissing("field");
        }
        RailsParams params = RailsParams.of(fieldParams);
        String as = RailsParams.asString(params.get("as").orElse(null));
        String[] type = as == null ? null : FIELD_TYPES.get(as);
        if (type == null) {
            throw new RailsInternalError("undefined method '[]' for nil (Field.lookup_class)");
        }
        if (as.contains("pair")) {
            Map<String, JsonNode> base = pairBase(fieldParams);
            Map<String, JsonNode> start = merge(base, pairSide(pairParams, "0"));
            Created first = createOne(type[0], RailsParams.of(start));
            Map<String, JsonNode> end = merge(base, pairSide(pairParams, "1"));
            Field firstField = first.field();
            end.put("pair_id", firstField == null ? null
                : com.fasterxml.jackson.databind.node.LongNode.valueOf(firstField.getId()));
            end.put("required", bool(firstField == null ? null : firstField.getRequired()));
            end.put("disabled", bool(firstField == null ? null : firstField.getDisabled()));
            createOne(type[0], RailsParams.of(end));
            invalidateRegistry();
            return first.errors().isEmpty()
                ? new Outcome(first.errors(), "undefined method 'custom_field_date_pair_url'")
                : new Outcome(first.errors(), null);
        }
        Created created = createOne(type[0], params);
        invalidateRegistry();
        return created.errors().isEmpty()
            ? new Outcome(created.errors(), "undefined method 'custom_field_url'")
            : new Outcome(created.errors(), null);
    }

    /** {@code update}: {@code field_params["as"].match?(/pair/)} (nil {@code as} raises → 500). */
    @Transactional
    public Outcome update(long id, Map<String, JsonNode> fieldParams, Map<String, JsonNode> pairParams) {
        find(id);
        if (fieldParams == null || fieldParams.isEmpty()) {
            throw new RailsParameterMissing("field");
        }
        String as = RailsParams.asString(fieldParams.get("as"));
        if (as == null) {
            throw new RailsInternalError("undefined method 'match?' for nil");
        }
        if (as.contains("pair")) {
            Map<String, JsonNode> base = pairBase(fieldParams);
            Map<String, JsonNode> startParams = pairSide(pairParams, "0");
            Integer startId = RailsParams.asInteger(startParams.get("id"));
            Field start = startId == null ? null : fieldRepository.findById(startId.longValue())
                .filter(field -> field.getType() != null && field.getType().contains("Pair"))
                .orElse(null);
            if (start == null) {
                throw new EntityNotFoundException("CustomFieldPair " + startId + " was not found");
            }
            Map<String, JsonNode> startAttrs = merge(base, startParams);
            startAttrs.remove("id");
            RailsErrors errors = updateOne(start, RailsParams.of(startAttrs));
            List<Field> paired = entityManager.createQuery(
                    "SELECT f FROM Field f WHERE f.pair.id = :id ORDER BY f.id", Field.class)
                .setParameter("id", start.getId()).setMaxResults(1).getResultList();
            Field end = !paired.isEmpty() ? paired.get(0) : start.getPair();
            if (end == null) {
                invalidateRegistry();
                return new Outcome(errors, "undefined method 'update' for nil (paired_with)");
            }
            Map<String, JsonNode> endAttrs = merge(base, pairSide(pairParams, "1"));
            endAttrs.remove("id");
            endAttrs.put("required", bool(start.getRequired()));
            endAttrs.put("disabled", bool(start.getDisabled()));
            updateOne(end, RailsParams.of(endAttrs));
            invalidateRegistry();
            return new Outcome(errors, null);
        }
        Field field = find(id);
        RailsErrors errors = updateOne(field, RailsParams.of(fieldParams));
        invalidateRegistry();
        if (!errors.isEmpty()) {
            // Rails' save transaction rolls back: nothing persisted, no DDL ran.
            throw new com.fatfreecrm.service.validation.RailsValidationException(errors);
        }
        return Outcome.ok();
    }

    /** {@code destroy}: acts_as_list closes the gap; the physical column is kept. */
    @Transactional
    public void destroy(long id) {
        Field field = find(id);
        if ("CoreField".equals(field.getType())) {
            throw new RailsInternalError("undefined method 'add_to_base' (CoreField#error_on_destroy)");
        }
        if (field.getType() != null && field.getType().contains("Pair")) {
            // has_one :pair, dependent: :destroy
            List<Field> paired = entityManager.createQuery(
                    "SELECT f FROM Field f WHERE f.pair.id = :id ORDER BY f.id", Field.class)
                .setParameter("id", id).setMaxResults(1).getResultList();
            for (Field end : paired) {
                destroyRow(end);
            }
        }
        destroyRow(field);
        invalidateRegistry();
    }

    /** {@code POST /admin/fields/sort}: update_all(position, field_group_id), then Rails 500s. */
    @Transactional
    public void sort(Map<String, JsonNode> params) {
        Integer groupId = params == null ? null : toI(params.get("field_group_id"));
        int group = groupId == null ? 0 : groupId;
        JsonNode ids = params == null ? null : params.get("fields_field_group_" + group);
        if (ids == null || ids.isNull()) {
            return;
        }
        int index = 0;
        for (JsonNode id : ids.isArray() ? ids : List.of(ids)) {
            index++;
            Integer fieldId = RailsParams.asInteger(id);
            if (fieldId != null) {
                jdbcTemplate.update("UPDATE fields SET position = ?, field_group_id = ? WHERE id = ?",
                    index, group, fieldId);
            }
        }
    }

    private record Created(Field field, RailsErrors errors) {
    }

    /** {@code klass.create(attrs)}: validations, then before_create add_column + acts_as_list bottom. */
    private Created createOne(String klass, RailsParams params) {
        Field field = new Field();
        field.setType(klass);
        Map<String, Object> raw = assign(field, params);
        if (raw.containsKey("type") && !klass.equals(raw.get("type"))) {
            throw new RailsInternalError("ActiveRecord::SubclassNotFound: " + raw.get("type"));
        }
        RailsErrors errors = validate(field, raw);
        if (!errors.isEmpty()) {
            return new Created(null, errors);
        }
        String table = table(field);
        if (field.getName() == null || field.getName().isBlank()) {
            field.setName(generateColumnName(table, field.getLabel()));
        }
        jdbcTemplate.execute("ALTER TABLE " + quoteIdent(table) + " ADD " + quoteIdent(field.getName())
            + " " + sqlType(field.getAsValue()));
        if (field.getPosition() == null) {
            Integer max = jdbcTemplate.queryForObject("SELECT max(position) FROM fields", Integer.class);
            field.setPosition(max == null ? 1 : max + 1);
        }
        Instant now = now();
        field.setCreatedAt(now);
        field.setUpdatedAt(now);
        return new Created(fieldRepository.saveAndFlush(field), errors);
    }

    /** {@code field.update(attrs)}: validations, after_validation update_column, save if changed. */
    private RailsErrors updateOne(Field field, RailsParams params) {
        String asWas = field.getAsValue();
        Map<String, Object> before = snapshot(field);
        Map<String, Object> raw = assign(field, params);
        RailsErrors errors = validate(field, raw);
        if (!errors.isEmpty()) {
            entityManager.detach(field);
            return errors;
        }
        boolean custom = field.getType() != null && field.getType().startsWith("CustomField");
        if (custom && "safe".equals(transitionSafety(asWas, field.getAsValue()))) {
            jdbcTemplate.execute("ALTER TABLE " + quoteIdent(table(field)) + " ALTER COLUMN "
                + quoteIdent(field.getName()) + " TYPE " + sqlType(field.getAsValue()));
        }
        if (!before.equals(snapshot(field))) {
            field.setUpdatedAt(now());
            fieldRepository.saveAndFlush(field);
        }
        return errors;
    }

    private void destroyRow(Field field) {
        Integer position = field.getPosition();
        fieldRepository.delete(field);
        fieldRepository.flush();
        if (position != null) {
            jdbcTemplate.update(
                "UPDATE fields SET position = position - 1, updated_at = ? WHERE position > ?",
                java.sql.Timestamp.from(now()), position);
        }
    }

    /** {@code field_params} permit order; returns the raw (before type cast) values for validation. */
    private Map<String, Object> assign(Field field, RailsParams params) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (String key : params.keys()) {
            JsonNode node = params.get(key).orElse(null);
            raw.put(key, node == null || node.isNull() ? null
                : node.isValueNode() ? node.asText() : node);
        }
        params.assignString("as", field::setAsValue);
        if (params.provided("collection_string")) {
            String value = RailsParams.asString(params.get("collection_string").orElse(null));
            List<String> items = new ArrayList<>();
            for (String item : (value == null ? "" : value).split("\\|")) {
                if (!item.strip().isEmpty()) {
                    items.add(item.strip());
                }
            }
            field.setCollection(RubyYaml.dump(items));
        }
        params.assignBoolean("disabled", field::setDisabled);
        params.assignInteger("field_group_id", groupId -> field.setFieldGroup(groupId == null ? null
            : entityManager.find(FieldGroup.class, groupId.longValue())));
        params.assignString("hint", field::setHint);
        params.assignString("label", field::setLabel);
        params.assignInteger("maxlength", field::setMaxlength);
        params.assignInteger("minlength", field::setMinlength);
        params.assignString("name", field::setName);
        params.assignInteger("pair_id", pairId -> field.setPair(pairId == null ? null
            : entityManager.getReference(Field.class, pairId.longValue())));
        params.assignString("placeholder", field::setPlaceholder);
        params.assignInteger("position", field::setPosition);
        params.assignBoolean("required", field::setRequired);
        params.assignString("type", field::setType);
        params.assignString("title", field::setTitle);
        params.get("settings").filter(JsonNode::isObject)
            .ifPresent(settings -> field.setSettings(RubyYaml.dump(RubyYaml.fromJson(settings))));
        if (!raw.containsKey("minlength")) {
            raw.put("minlength", field.getMinlength() == null ? null : field.getMinlength().toString());
        }
        if (!raw.containsKey("maxlength")) {
            raw.put("maxlength", field.getMaxlength() == null ? null : field.getMaxlength().toString());
        }
        return raw;
    }

    /** {@code Field} validations, in declaration order, with Rails' {@code ^} messages. */
    private static RailsErrors validate(Field field, Map<String, Object> raw) {
        RailsErrors errors = new RailsErrors();
        String label = field.getLabel();
        if (label == null || label.isBlank()) {
            errors.add("label", "^Please enter a field label.");
        }
        if (label != null && label.codePointCount(0, label.length()) > 64) {
            errors.add("label", "^The field name must be less than 64 characters in length.");
        }
        Object minRaw = raw.get("minlength");
        Object maxRaw = raw.get("maxlength");
        if (!blank(minRaw)) {
            BigDecimal min = number(minRaw);
            if (min == null || !INTEGER.matcher(minRaw.toString().strip()).matches()
                || min.signum() < 0) {
                errors.add("minlength", "^Min size can only be whole number.");
            }
            if (field.getMaxlength() != null
                && (min == null || min.compareTo(BigDecimal.valueOf(field.getMaxlength())) > 0)) {
                errors.add("minlength", "^Min size cannot be greater than max size.");
            }
        }
        if (!blank(maxRaw)) {
            BigDecimal max = number(maxRaw);
            if (max == null || !INTEGER.matcher(maxRaw.toString().strip()).matches()
                || max.signum() <= 0) {
                errors.add("maxlength", "^Max size can only be whole number.");
            }
        }
        String as = field.getAsValue();
        if (as == null || as.isBlank()) {
            errors.add("as", "^Please specify a field type.");
        } else if (!FIELD_TYPES.containsKey(as)) {
            errors.add("as", "^Invalid field type.");
        }
        return errors;
    }

    private static boolean blank(Object value) {
        return value == null || value.toString().isBlank();
    }

    private static BigDecimal number(Object value) {
        try {
            return new BigDecimal(value.toString().strip());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** {@code CustomField#db_transition_safety}. */
    static String transitionSafety(String oldAs, String newAs) {
        String[] oldType = oldAs == null ? null : FIELD_TYPES.get(oldAs);
        String[] newType = newAs == null ? null : FIELD_TYPES.get(newAs);
        if (oldType == null || newType == null) {
            throw new RailsInternalError("Unknown field_type: " + (oldType == null ? oldAs : newAs));
        }
        String oldCol = oldType[1];
        String newCol = newType[1];
        if (oldCol.equals(newCol)) {
            return "null";
        }
        if ("string".equals(oldCol) && "text".equals(newCol)) {
            return "safe";
        }
        for (Set<String> set : SAFE_ANY) {
            if (set.contains(oldCol) && set.contains(newCol)) {
                return "safe";
            }
        }
        return "unsafe";
    }

    /** {@code CustomField#generate_column_name}: cf_label, then cf_label_2, _3… */
    private String generateColumnName(String table, String label) {
        Set<String> columns = new HashSet<>(jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND table_name = ?", String.class, table));
        String base = "cf_" + label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        String name = base;
        int suffix = 1;
        while (columns.contains(name)) {
            suffix++;
            name = base + "_" + suffix;
        }
        return name;
    }

    private static String table(Field field) {
        FieldGroup group = field.getFieldGroup();
        if (group == null) {
            throw new RailsInternalError("Module::DelegationError: klass delegated to field_group, nil");
        }
        String table = group.getKlassName() == null ? null : TABLES.get(group.getKlassName());
        if (table == null) {
            throw new RailsInternalError("Unsupported custom field klass: " + group.getKlassName());
        }
        return table;
    }

    private static String sqlType(String as) {
        return SQL_TYPES.get(FIELD_TYPES.get(as)[1]);
    }

    private static String quoteIdent(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static Map<String, Object> snapshot(Field field) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("as", field.getAsValue());
        values.put("collection", field.getCollection());
        values.put("disabled", field.getDisabled());
        values.put("field_group_id", field.getFieldGroup() == null ? null : field.getFieldGroup().getId());
        values.put("hint", field.getHint());
        values.put("label", field.getLabel());
        values.put("maxlength", field.getMaxlength());
        values.put("minlength", field.getMinlength());
        values.put("name", field.getName());
        values.put("pair_id", field.getPair() == null ? null : field.getPair().getId());
        values.put("placeholder", field.getPlaceholder());
        values.put("position", field.getPosition());
        values.put("required", field.getRequired());
        values.put("type", field.getType());
        values.put("title", field.getTitle());
        values.put("settings", field.getSettings());
        return values;
    }

    private Field find(long id) {
        return fieldRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Field " + id + " was not found"));
    }

    private static Map<String, JsonNode> pairBase(Map<String, JsonNode> fieldParams) {
        Map<String, JsonNode> base = new LinkedHashMap<>();
        for (String key : List.of("field_group_id", "label", "as")) {
            if (fieldParams.containsKey(key)) {
                base.put(key, fieldParams.get(key));
            }
        }
        return base;
    }

    /** {@code pair_params}: {@code require(:pair).permit("0"/"1": %i[hint required disabled id])}. */
    private static Map<String, JsonNode> pairSide(Map<String, JsonNode> pairParams, String side) {
        if (pairParams == null || pairParams.isEmpty()) {
            throw new RailsParameterMissing("pair");
        }
        Map<String, JsonNode> permitted = new LinkedHashMap<>();
        JsonNode node = pairParams.get(side);
        if (node != null && node.isObject()) {
            for (String key : List.of("hint", "required", "disabled", "id")) {
                if (node.has(key) && node.get(key).isValueNode()) {
                    permitted.put(key, node.get(key));
                }
            }
        }
        return permitted;
    }

    private static Map<String, JsonNode> merge(Map<String, JsonNode> base, Map<String, JsonNode> extra) {
        Map<String, JsonNode> merged = new LinkedHashMap<>(base);
        merged.putAll(extra);
        return merged;
    }

    private static JsonNode bool(Boolean value) {
        return value == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance()
            : com.fasterxml.jackson.databind.node.BooleanNode.valueOf(value);
    }

    /** Ruby {@code to_i}: leading integer, else 0. */
    private static Integer toI(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0;
        }
        java.util.regex.Matcher matcher = Pattern.compile("\\A\\s*([+-]?\\d+)").matcher(node.asText());
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : 0;
    }

    private void invalidateRegistry() {
        registry.invalidate();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    registry.invalidate();
                }
            });
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static Map<String, String[]> fieldTypes() {
        Map<String, String[]> types = new LinkedHashMap<>();
        for (String as : List.of("string", "email", "url", "tel", "select", "radio_buttons")) {
            types.put(as, new String[] {"CustomField", "string"});
        }
        types.put("text", new String[] {"CustomField", "text"});
        types.put("check_boxes", new String[] {"CustomField", "text"});
        types.put("boolean", new String[] {"CustomField", "boolean"});
        types.put("date", new String[] {"CustomField", "date"});
        types.put("datetime", new String[] {"CustomField", "timestamp"});
        types.put("decimal", new String[] {"CustomField", "decimal"});
        types.put("integer", new String[] {"CustomField", "integer"});
        types.put("float", new String[] {"CustomField", "float"});
        types.put("date_pair", new String[] {"CustomFieldDatePair", "date"});
        types.put("datetime_pair", new String[] {"CustomFieldDatetimePair", "timestamp"});
        return types;
    }
}
