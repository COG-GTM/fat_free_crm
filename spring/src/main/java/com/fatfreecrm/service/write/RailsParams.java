package com.fatfreecrm.service.write;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Applies permitted {@code params.require(:x).permit(...)} maps to an entity. Only keys present in
 * the JSON object are assigned (Rails partial-update semantics); explicit JSON null assigns nil.
 * Rails casts params on assignment: {@code toInt} mirrors the int4 cast ("abc" → nil), booleans
 * accept true/false/"true"/"false"/"1"/"0", strings take the value's text.
 */
public final class RailsParams {

    private final Map<String, JsonNode> values;

    private RailsParams(Map<String, JsonNode> values) {
        this.values = values == null ? Map.of() : values;
    }

    public static RailsParams of(Map<String, JsonNode> values) {
        return new RailsParams(values);
    }

    /** Rails {@code params.require(key)}: a missing or empty root hash raises → 400. */
    public static RailsParams require(Map<String, JsonNode> values, String key) {
        if (values == null || values.isEmpty()) {
            throw new RailsParameterMissing(key);
        }
        return of(values);
    }

    public boolean provided(String key) {
        return values.containsKey(key);
    }

    public java.util.List<String> keys() {
        return java.util.List.copyOf(values.keySet());
    }

    public Optional<JsonNode> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public void assignString(String key, Consumer<String> setter) {
        if (provided(key)) {
            setter.accept(asString(values.get(key)));
        }
    }

    public void assignInteger(String key, Consumer<Integer> setter) {
        if (provided(key)) {
            setter.accept(asInteger(values.get(key)));
        }
    }

    /** Rails {@code validates_presence_of}/blank semantics for cast datetime params. */
    public void assignInstant(String key, java.util.function.Consumer<java.time.Instant> setter) {
        if (provided(key)) {
            setter.accept(asInstant(values.get(key)));
        }
    }

    public void assignBoolean(String key, Consumer<Boolean> setter) {
        if (provided(key)) {
            setter.accept(asBoolean(values.get(key)));
        }
    }

    public static String asString(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    /** Rails int4 cast: numeric values truncate, "3" parses, anything else becomes nil. */
    public static Integer asInteger(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.valueOf(node.asText().trim());
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    /**
     * ActiveModel::Type::Boolean: nil and "" cast to nil; FALSE_VALUES (false, 0, "0", "f", "F",
     * "false", "FALSE", "off", "OFF") cast to false; every other value casts to true.
     */
    public static Boolean asBoolean(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.asInt() != 0;
        }
        String text = node.asText();
        if (text == null || text.isEmpty()) {
            return null;
        }
        return switch (text) {
            case "0", "f", "F", "false", "FALSE", "off", "OFF" -> false;
            default -> true;
        };
    }

    /**
     * Rails datetime cast: ISO-8601 text, including numeric offsets ({@code +02:00},
     * {@code -05:00}); naive strings and unparseable values keep the existing UTC/nil behaviour.
     */
    public static java.time.Instant asInstant(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(text).toInstant();
        } catch (java.time.format.DateTimeParseException ignored) {
            // fall through
        }
        try {
            return java.time.Instant.parse(text);
        } catch (java.time.format.DateTimeParseException ignored) {
            // fall through
        }
        try {
            return java.time.LocalDateTime.parse(text.replace(' ', 'T'))
                .toInstant(java.time.ZoneOffset.UTC);
        } catch (java.time.format.DateTimeParseException ignored) {
            return null;
        }
    }
}
