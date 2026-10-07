package com.fatfreecrm.customfields;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.support.RailsModelType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fatfreecrm.service.query.DynamicAttributePredicates;
import com.fatfreecrm.service.query.RubyScalars;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.stereotype.Component;

@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "The injected ObjectMapper is retained for query-value serialization."
)
public class CustomFieldPredicates implements DynamicAttributePredicates {

    private static final char LIKE_ESCAPE = '\\';

    private final CustomFieldRegistry registry;
    private final ObjectMapper objectMapper;

    public CustomFieldPredicates(CustomFieldRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean handles(Class<?> entityType, String attribute) {
        for (RailsModelType type : RailsModelType.values()) {
            if (type.entityClass() == entityType && isCustomFieldModel(type)) {
                return attribute.matches("cf_[a-z0-9_]+")
                    && registry.find(type, attribute).isPresent();
            }
        }
        return false;
    }

    @Override
    public <T> Predicate toPredicate(
        Root<T> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        Class<T> entityType,
        String attribute,
        String operator,
        List<String> rawValues
    ) {
        RailsModelType type = modelType(entityType);
        CustomFieldDefinition definition = registry.find(type, attribute).orElse(null);
        if (definition == null || rawValues.isEmpty()) {
            return null;
        }
        boolean any = operator.endsWith("_any");
        boolean all = operator.endsWith("_all");
        String predicate = any || all ? operator.substring(0, operator.lastIndexOf('_')) : operator;
        if (any || all) {
            Predicate[] parts = rawValues.stream()
                .map(raw -> singlePredicate(root, cb, attribute, definition, predicate, List.of(raw)))
                .filter(java.util.Objects::nonNull)
                .toArray(Predicate[]::new);
            if (parts.length == 0) {
                return null;
            }
            return any ? cb.or(parts) : cb.and(parts);
        }
        return singlePredicate(root, cb, attribute, definition, predicate, rawValues);
    }

    private Predicate singlePredicate(
        Root<?> root,
        CriteriaBuilder cb,
        String attribute,
        CustomFieldDefinition definition,
        String predicate,
        List<String> rawValues
    ) {
        if (unsupportedByRails(definition.as(), predicate, rawValues)) {
            return null;
        }
        List<Object> values = new ArrayList<>();
        for (String raw : rawValues) {
            Object casted = cast(definition.as(), raw);
            if (casted != null) {
                values.add(casted);
            }
        }
        if (values.isEmpty() && !List.of("null", "not_null", "present", "blank").contains(predicate)) {
            return null;
        }

        Expression<String> text = textExpression(root, cb, attribute);
        return switch (predicate) {
            case "null", "not_null", "present", "blank" ->
                flagPredicate(text, definition.as(), predicate, rawValues.get(0), cb);
            case "true", "false" ->
                booleanPredicate(root, cb, attribute, definition.as(), predicate, rawValues.get(0));
            case "eq", "not_eq", "in", "not_in" ->
                equalityPredicate(root, cb, attribute, definition, predicate, values);
            case "lt", "lteq", "gt", "gteq" ->
                comparisonPredicate(root, cb, attribute, definition, predicate, values.get(0));
            case "cont", "not_cont", "i_cont", "start", "not_start", "end", "not_end",
                "matches", "does_not_match" ->
                likePredicate(text, predicate, rawValues.get(0), definition.as().equals("check_boxes"), cb);
            default -> null;
        };
    }

    private static boolean unsupportedByRails(String as, String predicate, List<String> rawValues) {
        if (as.equals("check_boxes")
            && List.of("blank", "present", "true", "false", "eq", "not_eq", "lt", "lteq",
                "gt", "gteq", "in", "not_in").contains(predicate)) {
            return true;
        }
        if (isLikePredicate(predicate)
            && (isNumeric(as) || isTemporal(as) || as.equals("boolean"))) {
            return true;
        }
        if (isTemporal(as) && List.of("true", "false").contains(predicate)) {
            return true;
        }
        if (as.equals("boolean")
            && List.of("eq", "not_eq", "in", "not_in", "lt", "lteq", "gt", "gteq").contains(predicate)
            && rawValues.stream().anyMatch(value -> !isBooleanToken(value))) {
            return true;
        }
        return false;
    }

    private static boolean isLikePredicate(String predicate) {
        return List.of(
            "cont", "not_cont", "i_cont", "start", "not_start", "end", "not_end",
            "matches", "does_not_match").contains(predicate);
    }

    private static boolean isNumeric(String as) {
        return List.of("integer", "float", "decimal").contains(as);
    }

    private static boolean isTemporal(String as) {
        return List.of("date", "date_pair", "datetime", "datetime_pair").contains(as);
    }

    private static boolean isBooleanToken(String value) {
        return RubyScalars.TRUE_VALUES.contains(value) || RubyScalars.FALSE_VALUES.contains(value);
    }

    private Predicate equalityPredicate(
        Root<?> root,
        CriteriaBuilder cb,
        String attribute,
        CustomFieldDefinition definition,
        String predicate,
        List<Object> values
    ) {
        boolean negative = predicate.equals("not_eq") || predicate.equals("not_in");
        boolean list = predicate.equals("in") || predicate.equals("not_in");
        if (definition.as().equals("check_boxes")) {
            if (list || predicate.equals("eq")) {
                return null;
            }
        }
        if ((definition.as().equals("select") || definition.as().equals("radio_buttons")
                || definition.as().equals("boolean"))
            && (predicate.equals("eq") || predicate.equals("in"))) {
            List<Predicate> predicates = new ArrayList<>();
            for (Object value : values) {
                predicates.add(contains(root, cb, attribute, value));
            }
            Predicate[] options = predicates.toArray(Predicate[]::new);
            if (!negative) {
                return cb.or(options);
            }
            Predicate[] excluded = java.util.Arrays.stream(options).map(cb::not).toArray(Predicate[]::new);
            return cb.and(cb.isNotNull(textExpression(root, cb, attribute)), cb.and(excluded));
        }
        Expression<?> path = typedExpression(root, cb, attribute, definition.as());
        List<Object> comparableValues = values.stream().map(value -> typedBind(definition.as(), value)).toList();
        if (list) {
            List<Expression<?>> boundValues = new ArrayList<>();
            for (Object value : comparableValues) {
                boundValues.add(bindValue(cb, value));
            }
            Predicate included = path.in(boundValues);
            return negative ? cb.not(included) : included;
        }
        Predicate equal = cb.equal(path, bindValue(cb, comparableValues.get(0)));
        return negative ? cb.not(equal) : equal;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate comparisonPredicate(
        Root<?> root,
        CriteriaBuilder cb,
        String attribute,
        CustomFieldDefinition definition,
        String predicate,
        Object value
    ) {
        Expression<? extends Comparable> path = (Expression<? extends Comparable>) typedExpression(
            root, cb, attribute, definition.as());
        Comparable bind = (Comparable) typedBind(definition.as(), value);
        return switch (predicate) {
            case "lt" -> cb.lessThan(path, bindValue(cb, bind));
            case "lteq" -> cb.lessThanOrEqualTo(path, bindValue(cb, bind));
            case "gt" -> cb.greaterThan(path, bindValue(cb, bind));
            default -> cb.greaterThanOrEqualTo(path, bindValue(cb, bind));
        };
    }

    private Predicate booleanPredicate(
        Root<?> root,
        CriteriaBuilder cb,
        String attribute,
        String as,
        String predicate,
        String raw
    ) {
        boolean target = predicate.equals("true") == RubyScalars.toBoolean(raw);
        if (as.equals("boolean")) {
            @SuppressWarnings("unchecked")
            Expression<Boolean> value = (Expression<Boolean>) typedExpression(root, cb, attribute, "boolean");
            return cb.equal(value, target);
        }
        if (!RubyScalars.toBoolean(raw) && isNumeric(as)) {
            return cb.isNotNull(textExpression(root, cb, attribute));
        }
        if (isNumeric(as)) {
            Expression<?> value = typedExpression(root, cb, attribute, as);
            return cb.equal(value, target ? BigDecimal.ONE : BigDecimal.ZERO);
        }
        if (isTemporal(as)) {
            return null;
        }
        Expression<String> value = textExpression(root, cb, attribute);
        if (isStringLike(as) && !RubyScalars.toBoolean(raw)) {
            return cb.isNotNull(value);
        }
        return cb.equal(value, bindValue(cb, Boolean.toString(target)));
    }

    private Predicate flagPredicate(
        Expression<String> value, String as, String predicate, String raw, CriteriaBuilder cb
    ) {
        boolean flag = RubyScalars.toBoolean(raw);
        boolean databaseScalar = isNumeric(as) || isTemporal(as) || as.equals("boolean");
        if (databaseScalar && predicate.equals("blank")) {
            return flag ? cb.isNull(value) : cb.disjunction();
        }
        if (databaseScalar && predicate.equals("present")) {
            return flag ? cb.disjunction() : cb.isNull(value);
        }
        if (predicate.equals("present") && flag && !isStringLike(as)) {
            return cb.disjunction();
        }
        return switch (predicate) {
            case "null" -> flag ? cb.isNull(value) : cb.isNotNull(value);
            case "not_null" -> flag ? cb.isNotNull(value) : cb.isNull(value);
            case "present" -> flag
                ? cb.and(cb.isNotNull(value), cb.notEqual(value, ""))
                : cb.or(cb.isNull(value), cb.equal(value, ""));
            default -> flag
                ? cb.or(cb.isNull(value), cb.equal(value, ""))
                : cb.and(cb.isNotNull(value), cb.notEqual(value, ""));
        };
    }

    private static boolean isStringLike(String as) {
        return List.of("string", "email", "url", "tel", "text", "select", "radio_buttons").contains(as);
    }

    private Predicate likePredicate(
        Expression<String> path, String predicate, String raw, boolean jsonArrayText, CriteriaBuilder cb
    ) {
        Expression<String> lowered = cb.lower(path);
        String value = raw.toLowerCase(Locale.ROOT);
        if (jsonArrayText) {
            try {
                String encoded = objectMapper.writeValueAsString(value);
                value = encoded.substring(1, encoded.length() - 1);
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("Custom-field predicate value is not JSON serializable", exception);
            }
        }
        String escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return switch (predicate) {
            case "cont", "i_cont" -> cb.like(lowered, bindValue(cb, "%" + escaped + "%"), LIKE_ESCAPE);
            case "not_cont" -> cb.notLike(lowered, bindValue(cb, "%" + escaped + "%"), LIKE_ESCAPE);
            case "start" -> cb.like(lowered, bindValue(cb, escaped + "%"), LIKE_ESCAPE);
            case "not_start" -> cb.notLike(lowered, bindValue(cb, escaped + "%"), LIKE_ESCAPE);
            case "end" -> cb.like(lowered, bindValue(cb, "%" + escaped), LIKE_ESCAPE);
            case "not_end" -> cb.notLike(lowered, bindValue(cb, "%" + escaped), LIKE_ESCAPE);
            case "matches" -> cb.like(lowered, bindValue(cb, value));
            case "does_not_match" -> cb.notLike(lowered, bindValue(cb, value));
            default -> null;
        };
    }

    private Predicate contains(Root<?> root, CriteriaBuilder cb, String attribute, Object value) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(attribute, value));
            return cb.isTrue(cb.function("ffcrm_jsonb_contains", Boolean.class,
                root.get("customFields"), bindValue(cb, json)));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Custom-field predicate value is not JSON serializable", exception);
        }
    }

    private static Expression<String> textExpression(
        Root<?> root, CriteriaBuilder cb, String attribute) {
        return cb.function("ffcrm_jsonb_text", String.class, root.get("customFields"),
            bindValue(cb, attribute));
    }

    private static Expression<?> typedExpression(
        Root<?> root, CriteriaBuilder cb, String attribute, String as) {
        String function = switch (as) {
            case "integer", "float", "decimal" -> "ffcrm_jsonb_numeric";
            case "date", "date_pair" -> "ffcrm_jsonb_date";
            case "datetime", "datetime_pair" -> "ffcrm_jsonb_timestamp";
            case "boolean" -> "ffcrm_jsonb_boolean";
            default -> "ffcrm_jsonb_text";
        };
        Class<?> resultType = switch (function) {
            case "ffcrm_jsonb_numeric" -> BigDecimal.class;
            case "ffcrm_jsonb_date" -> Date.class;
            case "ffcrm_jsonb_timestamp" -> Timestamp.class;
            case "ffcrm_jsonb_boolean" -> Boolean.class;
            default -> String.class;
        };
        return cb.function(function, resultType, root.get("customFields"), bindValue(cb, attribute));
    }

    private static <T> Expression<T> bindValue(CriteriaBuilder cb, T value) {
        return ((HibernateCriteriaBuilder) cb).value(value);
    }

    private static Object cast(String as, String raw) {
        return switch (as) {
            case "integer" -> RubyScalars.toLong(raw);
            case "decimal", "float" -> RubyScalars.toDecimal(raw);
            case "date", "date_pair" -> RubyScalars.toLocalDate(raw);
            case "datetime", "datetime_pair" -> RubyScalars.toInstant(raw);
            case "boolean" -> RubyScalars.toBoolean(raw);
            default -> raw;
        };
    }

    private static Object typedBind(String as, Object value) {
        return switch (as) {
            case "integer" -> ((Number) value).longValue();
            case "decimal", "float" -> value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
            case "date", "date_pair" -> Date.valueOf(value.toString());
            case "datetime", "datetime_pair" -> Timestamp.from((Instant) value);
            default -> value;
        };
    }

    private static RailsModelType modelType(Class<?> entityType) {
        for (RailsModelType type : RailsModelType.values()) {
            if (type.entityClass() == entityType && isCustomFieldModel(type)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported custom-fields entity " + entityType);
    }

    private static boolean isCustomFieldModel(RailsModelType type) {
        return switch (type) {
            case ACCOUNT, CAMPAIGN, CONTACT, LEAD, OPPORTUNITY, TASK -> true;
            default -> false;
        };
    }

    private static final class SetFlag {
        private SetFlag() {
        }

        private static boolean isFlagPredicate(String predicate) {
            return List.of("null", "not_null", "present", "blank").contains(predicate);
        }
    }
}
