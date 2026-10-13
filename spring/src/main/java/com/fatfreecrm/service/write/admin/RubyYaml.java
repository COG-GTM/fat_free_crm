package com.fatfreecrm.service.write.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fatfreecrm.service.audit.PaperTrailYaml;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Psych {@code to_yaml} for the value shapes Rails serializes into {@code settings.value} and
 * {@code fields.collection}/{@code fields.settings}: strings, symbols, booleans, numbers, nil,
 * arrays and {@code HashWithIndifferentAccess} maps (byte-compatible with Ruby 4 / Psych 5).
 */
public final class RubyYaml {

    static final String HWIA_TAG = "!ruby/hash:ActiveSupport::HashWithIndifferentAccess";

    /** A Ruby Symbol ({@code :needs_approval}). */
    public record Symbol(String name) {
    }

    private RubyYaml() {
    }

    /** {@code value.to_yaml}; {@code Map}s are emitted as {@code HashWithIndifferentAccess}. */
    public static String dump(Object value) {
        StringBuilder out = new StringBuilder("---");
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return "--- []\n";
            }
            out.append('\n');
            items(out, list, 0);
        } else if (value instanceof Map<?, ?> map) {
            out.append(' ').append(HWIA_TAG);
            if (map.isEmpty()) {
                return out.append(" {}\n").toString();
            }
            out.append('\n');
            entries(out, map, 0);
        } else {
            out.append(value == null ? "" : " " + scalar(value, 0)).append('\n');
        }
        return out.toString();
    }

    /** JSON → Ruby params value (objects keep key order, as ActionController::Parameters does). */
    public static Object fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.bigIntegerValue();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(fromJson(item)));
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        node.properties().forEach(entry -> map.put(entry.getKey(), fromJson(entry.getValue())));
        return map;
    }

    private static void entries(StringBuilder out, Map<?, ?> map, int indent) {
        String pad = " ".repeat(indent);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.append(pad).append(psychQuote(String.valueOf(entry.getKey()))).append(':');
            Object value = entry.getValue();
            if (value instanceof List<?> list) {
                if (list.isEmpty()) {
                    out.append(" []\n");
                } else {
                    out.append('\n');
                    items(out, list, indent);
                }
            } else if (value instanceof Map<?, ?> nested) {
                out.append(' ').append(HWIA_TAG);
                if (nested.isEmpty()) {
                    out.append(" {}\n");
                } else {
                    out.append('\n');
                    entries(out, nested, indent + 2);
                }
            } else if (value == null) {
                out.append('\n');
            } else {
                out.append(' ').append(scalar(value, indent)).append('\n');
            }
        }
    }

    private static void items(StringBuilder out, List<?> list, int indent) {
        String pad = " ".repeat(indent);
        for (Object item : list) {
            out.append(pad).append('-');
            if (item == null) {
                out.append('\n');
            } else {
                out.append(' ').append(scalar(item, indent)).append('\n');
            }
        }
    }

    /**
     * libyaml picks single quotes for strings that can't be plain (e.g. {@code '1'}, {@code 'true'})
     * and double quotes only when an escape is needed.
     */
    static String psychQuote(String text) {
        String quoted = PaperTrailYaml.quote(text);
        if (quoted.startsWith("\"") && text.chars().noneMatch(c -> c < 0x20 || c == 0x7f)) {
            return "'" + text.replace("'", "''") + "'";
        }
        return quoted;
    }

    private static String scalar(Object value, int indent) {
        if (value instanceof Symbol symbol) {
            return ":" + symbol.name();
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        String quoted = psychQuote(value.toString());
        return indent == 0 ? quoted : quoted.replace("\n", "\n" + " ".repeat(indent));
    }
}
