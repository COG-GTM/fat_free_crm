package com.fatfreecrm.service.query;

import com.fatfreecrm.service.query.RansackAttributes.Attribute;
import com.fatfreecrm.service.query.RansackAttributes.Kind;
import com.fatfreecrm.service.query.SearchableEntity.AssociationDef;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Translates a Ransack {@code q} tree (as parsed by {@link ListQuery}) into a JPA
 * {@link Specification} that reproduces the Rails-generated SQL: shared LEFT JOINs inside a
 * single {@code root.id IN (subquery)} composition, and {@code q[s]} sort orders.
 */
@Component
public class RansackParser {

    private static final int MAX_DEPTH = 5;
    private static final int MAX_CONDITIONS = 100;

    /** Predicate suffixes ordered so the longest match wins (e.g. {@code not_cont} before {@code cont}). */
    private static final List<String> PREDICATES = List.of(
        "does_not_match", "not_cont", "not_start", "not_end", "not_eq", "not_in", "not_null",
        "i_cont", "matches", "present", "blank", "cont", "start", "end", "eq", "lteq", "gteq",
        "lt", "gt", "in", "null", "true", "false");

    private static final Set<String> LIKE_PREDICATES = Set.of(
        "cont", "not_cont", "i_cont", "start", "not_start", "end", "not_end", "matches", "does_not_match");
    private static final Set<String> COMPOUNDABLE = Set.of(
        "eq", "not_eq", "cont", "not_cont", "start", "not_start", "end", "not_end",
        "matches", "does_not_match", "lt", "lteq", "gt", "gteq");
    private static final Set<String> BOOLEAN_PREDICATES = Set.of(
        "null", "not_null", "present", "blank", "true", "false");
    private static final Set<String> BOOLEAN_TRUE_VALUES = Set.of("1", "t", "true", "y", "yes", "on");
    private static final Set<String> BOOLEAN_FALSE_VALUES = Set.of("0", "f", "false", "n", "no", "off");
    private static final Set<String> LIST_PREDICATES = Set.of("in", "not_in");
    private static final char LIKE_ESCAPE = '\\';

    private final SearchableEntities searchableEntities;
    private final List<DynamicAttributePredicates> dynamicAttributes;
    private final boolean ignoreUnknownConditions;

    public RansackParser(
        SearchableEntities searchableEntities,
        ObjectProvider<DynamicAttributePredicates> dynamicAttributes,
        @Value("${ffcrm.search.ignore-unknown-conditions:true}") boolean ignoreUnknownConditions
    ) {
        this.searchableEntities = searchableEntities;
        this.dynamicAttributes = dynamicAttributes.orderedStream().toList();
        this.ignoreUnknownConditions = ignoreUnknownConditions;
    }

    /** One validated {@code q[s]} sort entry (root attributes only). */
    public record SortEntry(String field, boolean foreignKey, boolean descending) {
    }

    /** A parsed search tree: the WHERE specification plus requested sort entries. */
    public record SearchPlan<T>(Specification<T> where, List<SortEntry> sorts, boolean advanced) {

        public SearchPlan {
            sorts = List.copyOf(sorts);
        }
    }

    private record Hop(String name, AssociationDef association) {
    }

    private sealed interface Node {
    }

    private record Compound(List<Node> children, boolean or) implements Node {
    }

    private record Target(
        List<Hop> hops,
        Attribute attribute,
        DynamicAttributePredicates dynamicBean,
        String dynamicAttribute,
        List<Object> castedValues
    ) {
    }

    private record Condition(
        List<Target> targets,
        boolean targetsOr,
        String predicate,
        Boolean compoundAny,
        Boolean flag
    ) implements Node {
    }

    private static final class ParseContext {
        private final List<String> invalidKeys = new ArrayList<>();
        private int conditions;
    }

    /**
     * Parses the {@code q} tree for the given entity. A {@code null}/empty map means no
     * advanced search. Unknown attributes/predicates are dropped when
     * {@code ffcrm.search.ignore-unknown-conditions=true} (Rails default), otherwise they
     * produce a 400. Structurally malformed input always produces a 400.
     */
    public <T> SearchPlan<T> parse(Class<T> entityType, Map<String, Object> q) {
        if (q == null || q.isEmpty()) {
            return new SearchPlan<>(null, List.of(), false);
        }
        SearchableEntity entity = searchableEntities.forClass(entityType);
        ParseContext context = new ParseContext();
        List<SortEntry> sorts = parseSorts(q.get("s"), entityType, entity);
        Node tree = parseGroup(q, entityType, context, 0);
        if (context.conditions > MAX_CONDITIONS) {
            throw new InvalidSearchQueryException("Search has too many conditions.", List.of("q"));
        }
        if (!ignoreUnknownConditions && !context.invalidKeys.isEmpty()) {
            throw new InvalidSearchQueryException(
                "Search contains unknown conditions.", context.invalidKeys);
        }
        Specification<T> where = tree == null ? null : specification(entityType, entity, tree);
        return new SearchPlan<>(where, sorts, true);
    }

    private <T> Specification<T> specification(Class<T> entityType, SearchableEntity entity, Node tree) {
        return (root, query, cb) -> {
            if (usesAssociations(tree)) {
                Subquery<Long> subquery = query.subquery(Long.class);
                Root<T> subRoot = subquery.from(entityType);
                subquery.select(subRoot.get("id"));
                BuildScope scope = new BuildScope(subRoot, cb, query, searchableEntities);
                subquery.where(compile(tree, entityType, entity, subRoot, scope));
                return root.get("id").in(subquery);
            }
            BuildScope scope = new BuildScope(root, cb, query, searchableEntities);
            return compile(tree, entityType, entity, root, scope);
        };
    }

    private static boolean usesAssociations(Node node) {
        if (node instanceof Condition condition) {
            return condition.targets().stream().anyMatch(target -> !target.hops().isEmpty());
        }
        return ((Compound) node).children().stream().anyMatch(RansackParser::usesAssociations);
    }

    /** Join cache for one compiled query level: association path ({@code "contacts.account"}) to From. */
    private static final class BuildScope {
        private final From<?, ?> base;
        private final CriteriaBuilder cb;
        private final CriteriaQuery<?> query;
        private final SearchableEntities searchableEntities;
        private final Map<String, From<?, ?>> joins = new LinkedHashMap<>();

        private BuildScope(
            From<?, ?> base,
            CriteriaBuilder cb,
            CriteriaQuery<?> query,
            SearchableEntities searchableEntities
        ) {
            this.base = base;
            this.cb = cb;
            this.query = query;
            this.searchableEntities = searchableEntities;
        }

        private From<?, ?> from(List<Hop> hops, String railsName) {
            From<?, ?> current = base;
            StringBuilder path = new StringBuilder();
            String sourceType = railsName;
            for (Hop hop : hops) {
                path.append(path.isEmpty() ? "" : ".").append(hop.name());
                String key = path.toString();
                From<?, ?> existing = joins.get(key);
                if (existing == null) {
                    existing = hop.association().join().join(current, cb, sourceType);
                    joins.put(key, existing);
                }
                current = existing;
                SearchableEntity target = searchableEntities.find(hop.association().targetClass());
                sourceType = target == null
                    ? hop.association().targetClass().getSimpleName() : target.railsName();
            }
            return current;
        }
    }

    private Predicate compile(
        Node node,
        Class<?> entityType,
        SearchableEntity entity,
        From<?, ?> base,
        BuildScope scope
    ) {
        if (node instanceof Compound compound) {
            List<Predicate> predicates = new ArrayList<>();
            for (Node child : compound.children()) {
                Predicate predicate = compile(child, entityType, entity, base, scope);
                if (predicate != null) {
                    predicates.add(predicate);
                }
            }
            if (predicates.isEmpty()) {
                return null;
            }
            Predicate[] all = predicates.toArray(Predicate[]::new);
            return compound.or() ? scope.cb.or(all) : scope.cb.and(all);
        }
        return compileCondition((Condition) node, entityType, entity, base, scope);
    }

    private Predicate compileCondition(
        Condition condition,
        Class<?> entityType,
        SearchableEntity entity,
        From<?, ?> base,
        BuildScope scope
    ) {
        List<Predicate> perTarget = new ArrayList<>();
        for (Target target : condition.targets()) {
            Predicate predicate;
            if (target.dynamicBean() != null) {
                String operator = condition.predicate()
                    + (condition.compoundAny() == null ? "" : (condition.compoundAny() ? "_any" : "_all"));
                @SuppressWarnings("unchecked")
                Root<Object> dynamicRoot = (Root<Object>) scope.base;
                predicate = toDynamicPredicate(target.dynamicBean(), dynamicRoot, scope.query,
                    scope.cb, entityType, target.dynamicAttribute(), operator, target.castedValues());
            } else {
                From<?, ?> from = scope.from(target.hops(), entity.railsName());
                Expression<?> path = target.attribute().foreignKey()
                    ? from.get(target.attribute().field()).get("id")
                    : from.get(target.attribute().field());
                predicate = applyPredicate(
                    path, target.attribute().kind(), condition.predicate(), condition.flag(),
                    condition.compoundAny(), target.castedValues(), scope.cb);
            }
            if (predicate == null) {
                return null;
            }
            perTarget.add(predicate);
        }
        Predicate[] all = perTarget.toArray(Predicate[]::new);
        return condition.targetsOr() ? scope.cb.or(all) : scope.cb.and(all);
    }

    @SuppressWarnings("unchecked")
    private static <X> Predicate toDynamicPredicate(
        DynamicAttributePredicates bean,
        Root<X> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        Class<?> entityType,
        String attribute,
        String operator,
        List<Object> rawValues
    ) {
        List<String> strings = new ArrayList<>();
        for (Object value : rawValues) {
            strings.add(String.valueOf(value));
        }
        return bean.toPredicate(root, query, cb, (Class<X>) entityType, attribute, operator, strings);
    }

    private Predicate applyPredicate(
        Expression<?> path,
        Kind kind,
        String predicate,
        Boolean flag,
        Boolean compoundAny,
        List<Object> values,
        CriteriaBuilder cb
    ) {
        if ("in".equals(predicate)) {
            return path.in(values);
        }
        if ("not_in".equals(predicate)) {
            return cb.not(path.in(values));
        }
        if (compoundAny != null) {
            List<Predicate> parts = new ArrayList<>();
            for (Object value : values) {
                parts.add(single(path, kind, predicate, flag, value, cb));
            }
            Predicate[] all = parts.toArray(Predicate[]::new);
            return compoundAny ? cb.or(all) : cb.and(all);
        }
        return single(path, kind, predicate, flag, values.get(0), cb);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate single(
        Expression<?> path,
        Kind kind,
        String predicate,
        Boolean flag,
        Object value,
        CriteriaBuilder cb
    ) {
        switch (predicate) {
            case "eq":
                return cb.equal(path, value);
            case "not_eq":
                return cb.notEqual(path, value);
            case "lt":
                return cb.lessThan((Expression<? extends Comparable>) path, (Comparable) value);
            case "lteq":
                return cb.lessThanOrEqualTo((Expression<? extends Comparable>) path, (Comparable) value);
            case "gt":
                return cb.greaterThan((Expression<? extends Comparable>) path, (Comparable) value);
            case "gteq":
                return cb.greaterThanOrEqualTo((Expression<? extends Comparable>) path, (Comparable) value);
            case "null":
                return flag ? cb.isNull(path) : cb.isNotNull(path);
            case "not_null":
                return flag ? cb.isNotNull(path) : cb.isNull(path);
            case "present":
                return flag ? present(path, kind, cb) : blank(path, kind, cb);
            case "blank":
                return flag ? blank(path, kind, cb) : present(path, kind, cb);
            case "true":
                return flag ? cb.equal(path, Boolean.TRUE) : cb.notEqual(path, Boolean.TRUE);
            case "false":
                return flag ? cb.equal(path, Boolean.FALSE) : cb.notEqual(path, Boolean.FALSE);
            default:
                return likePredicate(path, predicate, String.valueOf(value), cb);
        }
    }

    private Predicate present(Expression<?> path, Kind kind, CriteriaBuilder cb) {
        if (kind != Kind.STRING) {
            return cb.isNotNull(path);
        }
        return cb.and(cb.isNotNull(path), cb.notEqual(path, ""));
    }

    private Predicate blank(Expression<?> path, Kind kind, CriteriaBuilder cb) {
        if (kind != Kind.STRING) {
            return cb.isNull(path);
        }
        return cb.or(cb.isNull(path), cb.equal(path, ""));
    }

    private Predicate likePredicate(Expression<?> path, String predicate, String rawValue, CriteriaBuilder cb) {
        Expression<String> lowered = cb.lower(path.as(String.class));
        String loweredValue = rawValue.toLowerCase(java.util.Locale.ROOT);
        return switch (predicate) {
            case "cont", "i_cont" ->
                cb.like(lowered, "%" + escapeLike(loweredValue) + "%", LIKE_ESCAPE);
            case "not_cont" ->
                cb.notLike(lowered, "%" + escapeLike(loweredValue) + "%", LIKE_ESCAPE);
            case "start" ->
                cb.like(lowered, escapeLike(loweredValue) + "%", LIKE_ESCAPE);
            case "not_start" ->
                cb.notLike(lowered, escapeLike(loweredValue) + "%", LIKE_ESCAPE);
            case "end" ->
                cb.like(lowered, "%" + escapeLike(loweredValue), LIKE_ESCAPE);
            case "not_end" ->
                cb.notLike(lowered, "%" + escapeLike(loweredValue), LIKE_ESCAPE);
            case "matches" ->
                cb.like(lowered, loweredValue);
            case "does_not_match" ->
                cb.notLike(lowered, loweredValue);
            default -> null;
        };
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Node parseGroup(Map<String, Object> map, Class<?> entityType, ParseContext context, int depth) {
        if (depth > MAX_DEPTH) {
            throw new InvalidSearchQueryException("Search nesting is too deep.", List.of("q"));
        }
        boolean or = combinator(map.get("m"), "m");
        List<Node> children = new ArrayList<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            switch (key) {
                case "m", "s" -> {
                    // handled by the group combinator and the sort parser
                }
                case "g" -> children.add(parseGroupList(value, entityType, context, depth));
                case "c" -> children.add(parseConditionList(value, entityType, context));
                default -> {
                    Node condition = parseKeyCondition(key, value, entityType, context);
                    if (condition != null) {
                        children.add(condition);
                    }
                }
            }
        }
        children.removeIf(java.util.Objects::isNull);
        if (children.isEmpty()) {
            return null;
        }
        return children.size() == 1 ? children.get(0) : new Compound(children, or);
    }

    private static boolean combinator(Object raw, String key) {
        if (raw == null) {
            return false;
        }
        if (!(raw instanceof String value)
            || !(value.equalsIgnoreCase("or") || value.equalsIgnoreCase("and"))) {
            throw new InvalidSearchQueryException("Invalid combinator.", List.of("q[" + key + "]"));
        }
        return value.equalsIgnoreCase("or");
    }

    private Node parseGroupList(Object raw, Class<?> entityType, ParseContext context, int depth) {
        if (!(raw instanceof Map)) {
            throw new InvalidSearchQueryException("Invalid search group.", List.of("q[g]"));
        }
        List<Node> nodes = new ArrayList<>();
        for (Object child : orderedValues(castMap(raw))) {
            if (!(child instanceof Map)) {
                throw new InvalidSearchQueryException("Invalid search group.", List.of("q[g]"));
            }
            Node node = parseGroup(castMap(child), entityType, context, depth + 1);
            if (node != null) {
                nodes.add(node);
            }
        }
        if (nodes.isEmpty()) {
            return null;
        }
        return nodes.size() == 1 ? nodes.get(0) : new Compound(nodes, false);
    }

    private Node parseConditionList(Object raw, Class<?> entityType, ParseContext context) {
        if (!(raw instanceof Map)) {
            throw new InvalidSearchQueryException("Invalid condition list.", List.of("q[c]"));
        }
        List<Node> nodes = new ArrayList<>();
        for (Object child : orderedValues(castMap(raw))) {
            if (!(child instanceof Map)) {
                throw new InvalidSearchQueryException("Invalid condition list.", List.of("q[c]"));
            }
            nodes.add(parseConditionEntry(castMap(child), entityType, context));
        }
        if (nodes.isEmpty()) {
            return null;
        }
        return nodes.size() == 1 ? nodes.get(0) : new Compound(nodes, false);
    }

    private Node parseConditionEntry(
        Map<String, Object> entry, Class<?> entityType, ParseContext context) {
        context.conditions++;
        Object attributes = entry.get("a");
        Object predicate = entry.get("p");
        Object values = entry.get("v");
        boolean attrsOr = combinator(entry.get("m"), "c][m");
        if (attributes == null || values == null || predicate == null) {
            context.invalidKeys.add("q[c]");
            return null;
        }
        if (!(attributes instanceof Map) || !(values instanceof Map) || !(predicate instanceof String)) {
            throw new InvalidSearchQueryException("Invalid condition form.", List.of("q[c]"));
        }
        List<String> attributeNames = new ArrayList<>();
        for (Object attributeEntry : orderedValues(castMap(attributes))) {
            if (!(attributeEntry instanceof Map)
                || !(((Map<?, ?>) attributeEntry).get("name") instanceof String name)) {
                throw new InvalidSearchQueryException("Invalid condition form.", List.of("q[c]"));
            } else {
                attributeNames.add(name);
            }
        }
        List<String> rawValues = new ArrayList<>();
        for (Object valueEntry : orderedValues(castMap(values))) {
            if (!(valueEntry instanceof Map)
                || !(((Map<?, ?>) valueEntry).get("value") instanceof String value)) {
                throw new InvalidSearchQueryException("Invalid condition form.", List.of("q[c]"));
            } else {
                rawValues.add(value);
            }
        }
        String predicateName = (String) predicate;
        if (!PREDICATES.contains(predicateName)) {
            context.invalidKeys.add("q[c]");
            return null;
        }
        return buildCondition(
            attributeNames, attrsOr, predicateName, null, rawValues, entityType, context, "q[c]");
    }

    private Node parseKeyCondition(
        String key, Object value, Class<?> entityType, ParseContext context) {
        context.conditions++;
        List<String> rawValues = valuesOf(value, "q[" + key + "]");

        String rest = key;
        Boolean compound = null;
        if (rest.endsWith("_any")) {
            compound = Boolean.TRUE;
            rest = rest.substring(0, rest.length() - "_any".length());
        } else if (rest.endsWith("_all")) {
            compound = Boolean.FALSE;
            rest = rest.substring(0, rest.length() - "_all".length());
        }
        String predicate = null;
        for (String candidate : PREDICATES) {
            if (rest.endsWith("_" + candidate)) {
                predicate = candidate;
                rest = rest.substring(0, rest.length() - candidate.length() - 1);
                break;
            }
        }
        if (predicate == null || rest.isEmpty()
            || (compound != null && !COMPOUNDABLE.contains(predicate))) {
            context.invalidKeys.add("q[" + key + "]");
            return null;
        }
        List<String> attributeNames = splitAttributes(rest);
        return buildCondition(
            attributeNames, rest.contains("_or_"), predicate, compound, rawValues, entityType, context,
            "q[" + key + "]");
    }

    private static List<String> valuesOf(Object value, String key) {
        List<String> rawValues = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (!(element instanceof String)) {
                    throw new InvalidSearchQueryException(
                        "Condition value must be a string or a list.", List.of(key));
                }
                rawValues.add((String) element);
            }
        } else if (value instanceof String single) {
            rawValues.add(single);
        } else {
            throw new InvalidSearchQueryException(
                "Condition value must be a string or a list.", List.of(key));
        }
        return rawValues;
    }

    private static List<String> splitAttributes(String attributePart) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = 0;
        while (index < attributePart.length()) {
            if (attributePart.startsWith("_or_", index) || attributePart.startsWith("_and_", index)) {
                parts.add(current.toString());
                current = new StringBuilder();
                index += attributePart.startsWith("_or_", index) ? "_or_".length() : "_and_".length();
            } else {
                current.append(attributePart.charAt(index));
                index++;
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private Node buildCondition(
        List<String> attributeNames,
        boolean targetsOr,
        String predicate,
        Boolean compound,
        List<String> rawValues,
        Class<?> entityType,
        ParseContext context,
        String key
    ) {
        List<String> cleaned = new ArrayList<>();
        for (String value : rawValues) {
            String stripped = value.strip();
            if (!stripped.isEmpty()) {
                cleaned.add(stripped);
            }
        }
        if (cleaned.isEmpty()) {
            return null;
        }

        Boolean flag = null;
        if (BOOLEAN_PREDICATES.contains(predicate)) {
            Optional<Boolean> parsedFlag = ransackBoolean(cleaned.get(0));
            if (parsedFlag.isEmpty()) {
                return null;
            }
            flag = parsedFlag.get();
        }

        boolean listForm = compound != null || LIST_PREDICATES.contains(predicate);
        if (cleaned.size() > 1 && !listForm) {
            context.invalidKeys.add(key);
            return null;
        }

        List<Target> targets = new ArrayList<>();
        for (String attributeName : attributeNames) {
            Target target = resolveTarget(attributeName, predicate, cleaned, flag, entityType);
            if (target == null) {
                context.invalidKeys.add(key);
                return null;
            }
            if (target.castedValues().isEmpty()) {
                // Uncastable values are dropped silently, like Rails dropping a garbage condition.
                return null;
            }
            targets.add(target);
        }
        if (targets.isEmpty()) {
            context.invalidKeys.add(key);
            return null;
        }
        return new Condition(targets, targetsOr, predicate, compound, flag);
    }

    private Target resolveTarget(
        String name, String predicate, List<String> values, Boolean flag, Class<?> entityType) {
        List<Hop> hops = new ArrayList<>();
        Attribute attribute = resolveAttribute(name, entityType, hops);
        if (attribute != null) {
            if (!applicable(predicate, attribute.kind())) {
                return null;
            }
            List<Object> castedValues = new ArrayList<>();
            if (BOOLEAN_PREDICATES.contains(predicate)) {
                castedValues.add(flag);
            } else {
                for (String value : values) {
                    Object castedValue = cast(attribute.kind(), value);
                    if (castedValue != null) {
                        castedValues.add(castedValue);
                    }
                }
            }
            return new Target(hops, attribute, null, null, castedValues);
        }
        if (hops.isEmpty()) {
            for (DynamicAttributePredicates bean : dynamicAttributes) {
                if (bean.handles(entityType, name)) {
                    List<Object> raw = new ArrayList<>(values);
                    return new Target(hops, null, bean, name, raw);
                }
            }
        }
        return null;
    }

    private static Optional<Boolean> ransackBoolean(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (BOOLEAN_TRUE_VALUES.contains(normalized)) {
            return Optional.of(Boolean.TRUE);
        }
        if (BOOLEAN_FALSE_VALUES.contains(normalized)) {
            return Optional.of(Boolean.FALSE);
        }
        return Optional.empty();
    }

    /** Resolves a ransack attribute path, pushing association hops into {@code hops}. */
    private Attribute resolveAttribute(String name, Class<?> entityType, List<Hop> hops) {
        Attribute attribute = RansackAttributes.catalog(entityType).get(name);
        if (attribute != null) {
            return attribute;
        }
        SearchableEntity entity = searchableEntities.find(entityType);
        if (entity == null) {
            return null;
        }
        String prefix = longestAssociationPrefix(entity, name);
        if (prefix == null) {
            return null;
        }
        AssociationDef association = entity.associations().get(prefix);
        hops.add(new Hop(prefix, association));
        return resolveAttribute(name.substring(prefix.length() + 1), association.targetClass(), hops);
    }

    private static String longestAssociationPrefix(SearchableEntity entity, String name) {
        String best = null;
        for (String association : entity.associations().keySet()) {
            if (name.startsWith(association + "_")
                && (best == null || association.length() > best.length())) {
                best = association;
            }
        }
        return best;
    }

    private static boolean applicable(String predicate, Kind kind) {
        if (LIKE_PREDICATES.contains(predicate)) {
            return kind == Kind.STRING;
        }
        if (predicate.equals("true") || predicate.equals("false")) {
            return kind == Kind.BOOLEAN;
        }
        return true;
    }

    private static Object cast(Kind kind, String value) {
        return switch (kind) {
            case STRING -> value;
            case INTEGER -> RubyScalars.toLong(value);
            case DECIMAL -> RubyScalars.toDecimal(value);
            case BOOLEAN -> RubyScalars.toBoolean(value);
            case DATE -> RubyScalars.toLocalDate(value);
            case DATETIME -> RubyScalars.toInstant(value);
        };
    }

    private List<SortEntry> parseSorts(Object rawSorts, Class<?> entityType, SearchableEntity entity) {
        List<String> entries = new ArrayList<>();
        if (rawSorts instanceof String single) {
            entries.add(single);
        } else if (rawSorts instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof String sort)) {
                    throw new InvalidSearchQueryException("Invalid sort entry.", List.of("q[s]"));
                }
                entries.add(sort);
            }
        } else if (rawSorts != null) {
            throw new InvalidSearchQueryException("Invalid sort entry.", List.of("q[s]"));
        }
        List<SortEntry> result = new ArrayList<>();
        for (String entry : entries) {
            String[] parts = entry.strip().split("\\s+");
            if (parts.length > 2 || parts[0].isEmpty()) {
                continue;
            }
            boolean descending;
            if (parts.length == 1) {
                descending = false;
            } else if ("desc".equalsIgnoreCase(parts[1])) {
                descending = true;
            } else if ("asc".equalsIgnoreCase(parts[1])) {
                descending = false;
            } else {
                continue;
            }
            List<Hop> hops = new ArrayList<>();
            Attribute attribute = resolveAttribute(parts[0], entityType, hops);
            if (attribute == null || !hops.isEmpty()) {
                continue;
            }
            result.add(new SortEntry(attribute.field(), attribute.foreignKey(), descending));
        }
        return result;
    }

    private static List<Object> orderedValues(Map<String, Object> map) {
        List<Map.Entry<String, Object>> entries = new ArrayList<>(map.entrySet());
        entries.sort((left, right) -> {
            try {
                return Integer.compare(Integer.parseInt(left.getKey()), Integer.parseInt(right.getKey()));
            } catch (NumberFormatException exception) {
                return 0;
            }
        });
        List<Object> values = new ArrayList<>();
        for (Map.Entry<String, Object> entry : entries) {
            values.add(entry.getValue());
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
