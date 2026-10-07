package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class JsonNormalizer {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNormalizer() {
    }

    public static JsonNode merge(JsonNode global, JsonNode local) {
        ObjectNode result = JSON.createObjectNode();
        appendOptions(result, global);
        appendOptions(result, local);
        return result;
    }

    public static JsonNode normalize(JsonNode input, JsonNode options, String side) {
        return normalizeWithDiagnostics(input, options, side).json();
    }

    public static NormalizationResult normalizeWithDiagnostics(JsonNode input, JsonNode options, String side) {
        JsonNode normalized = input.deepCopy();
        applyRenames(normalized, options.path("rename"), side);
        normalizeTree(normalized, "", options);
        List<MissingKey> missingKeys = new ArrayList<>();
        collectMissingKeys(normalized, "", options.path("unorderedArrays"), side, missingKeys);
        return new NormalizationResult(normalized, missingKeys);
    }

    public static boolean ignored(String pointer, JsonNode options) {
        for (JsonNode pattern : options.path("ignore")) {
            if (matches(pattern.asText(), pointer)) {
                return true;
            }
        }
        return false;
    }

    private static void appendOptions(ObjectNode target, JsonNode source) {
        if (!source.isObject()) {
            return;
        }
        source.properties().forEach(entry -> {
            JsonNode current = target.get(entry.getKey());
            if (current == null || !current.isArray()) {
                ArrayNode values = JSON.createArrayNode();
                target.set(entry.getKey(), values);
                current = values;
            }
            if (entry.getValue().isArray() && current instanceof ArrayNode values) {
                entry.getValue().forEach(value -> values.add(value.deepCopy()));
            }
        });
    }

    private static void applyRenames(JsonNode root, JsonNode renames, String side) {
        List<Rename> operations = new ArrayList<>();
        for (JsonNode rename : renames) {
            if (!rename.path("side").asText().equalsIgnoreCase(side)) {
                continue;
            }
            String from = rename.path("from").asText();
            collectRename(root, "", from, rename.path("to").asText(), operations);
        }
        for (Rename rename : operations) {
            JsonNode value = get(root, rename.from());
            if (value != null) {
                remove(root, rename.from());
                set(root, rename.to(), value);
            }
        }
    }

    private static void collectRename(JsonNode node, String pointer, String from, String to, List<Rename> result) {
        if (matches(from, pointer)) {
            String destination = substitute(from, to, pointer);
            result.add(new Rename(pointer, destination));
        }
        if (node.isObject()) {
            node.properties().forEach(entry ->
                collectRename(entry.getValue(), pointer + "/" + escape(entry.getKey()), from, to, result));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectRename(node.get(index), pointer + "/" + index, from, to, result);
            }
        }
    }

    private static String substitute(String pattern, String replacement, String actual) {
        String[] replacementParts = replacement.split("/", -1);
        String[] actualParts = actual.split("/", -1);
        List<String> output = new ArrayList<>();
        for (int index = 0; index < replacementParts.length; index++) {
            String part = replacementParts[index];
            if (part.equals("*") && index < actualParts.length) {
                output.add(actualParts[index]);
            } else {
                output.add(part);
            }
        }
        return String.join("/", output);
    }

    private static void normalizeTree(JsonNode node, String pointer, JsonNode options) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                normalizeTree(node.get(name), pointer + "/" + escape(name), options);
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                normalizeTree(array.get(index), pointer + "/" + index, options);
            }
            sortIfUnordered(array, pointer, options.path("unorderedArrays"));
        }
    }

    private static void sortIfUnordered(ArrayNode array, String pointer, JsonNode specifications) {
        JsonNode matched = null;
        for (JsonNode spec : specifications) {
            String declaredPointer = spec.isTextual() ? spec.asText() : spec.path("pointer").asText();
            if (matches(declaredPointer, pointer)) {
                matched = spec;
                break;
            }
        }
        if (matched == null) {
            return;
        }
        List<JsonNode> values = new ArrayList<>();
        array.forEach(value -> values.add(value.deepCopy()));
        String keyPointer = matched.isObject() ? matched.path("key").asText(null) : null;
        if (keyPointer == null) {
            values.sort(Comparator.comparing(JsonNormalizer::canonical));
        } else {
            String selectedKey = keyPointer;
            values.sort(Comparator.comparingInt((JsonNode value) -> get(value, selectedKey) == null ? 1 : 0)
                .thenComparing(value -> {
                    JsonNode key = get(value, selectedKey);
                    return key == null ? canonical(value) : canonical(key);
                }));
        }
        array.removeAll();
        values.forEach(array::add);
    }

    private static void collectMissingKeys(
        JsonNode node,
        String pointer,
        JsonNode specifications,
        String side,
        List<MissingKey> missingKeys
    ) {
        if (node.isObject()) {
            node.properties().forEach(entry ->
                collectMissingKeys(entry.getValue(), pointer + "/" + escape(entry.getKey()), specifications,
                    side, missingKeys));
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            String keyPointer = keyPointer(pointer, specifications);
            for (int index = 0; index < array.size(); index++) {
                JsonNode value = array.get(index);
                if (keyPointer != null && get(value, keyPointer) == null) {
                    missingKeys.add(new MissingKey(side, pointer + "/" + index, value.deepCopy()));
                }
                collectMissingKeys(value, pointer + "/" + index, specifications, side, missingKeys);
            }
        }
    }

    private static String keyPointer(String pointer, JsonNode specifications) {
        for (JsonNode specification : specifications) {
            String declared = specification.isTextual()
                ? specification.asText() : specification.path("pointer").asText();
            if (matches(declared, pointer)) {
                return specification.isObject() ? specification.path("key").asText(null) : null;
            }
        }
        return null;
    }

    private static boolean matches(String pattern, String pointer) {
        if (pattern == null || pattern.isEmpty()) {
            return pointer.isEmpty();
        }
        String[] expected = pattern.split("/", -1);
        String[] actual = pointer.split("/", -1);
        if (expected.length != actual.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (!expected[index].equals("*") && !unescape(expected[index]).equals(unescape(actual[index]))) {
                return false;
            }
        }
        return true;
    }

    private static String canonical(JsonNode value) {
        try {
            return JSON.writeValueAsString(canonicalNode(value));
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }

    private static JsonNode canonicalNode(JsonNode value) {
        if (value.isObject()) {
            ObjectNode canonical = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) {
                canonical.set(name, canonicalNode(value.get(name)));
            }
            return canonical;
        }
        if (value.isArray()) {
            ArrayNode canonical = JSON.createArrayNode();
            value.forEach(item -> canonical.add(canonicalNode(item)));
            return canonical;
        }
        return value;
    }

    private static JsonNode get(JsonNode root, String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return root;
        }
        JsonNode current = root;
        for (String segment : pointer.substring(1).split("/")) {
            if (current == null) {
                return null;
            }
            String name = unescape(segment);
            if (current.isArray()) {
                try {
                    current = current.path(Integer.parseInt(name));
                } catch (NumberFormatException exception) {
                    return null;
                }
            } else {
                current = current.path(name);
            }
        }
        return current == null || current.isMissingNode() ? null : current;
    }

    private static void remove(JsonNode root, String pointer) {
        String parentPointer = pointer.substring(0, pointer.lastIndexOf('/'));
        String name = unescape(pointer.substring(pointer.lastIndexOf('/') + 1));
        JsonNode parent = parentPointer.isEmpty() ? root : get(root, parentPointer);
        if (parent instanceof ObjectNode object) {
            object.remove(name);
        } else if (parent instanceof ArrayNode array) {
            array.remove(Integer.parseInt(name));
        }
    }

    private static void set(JsonNode root, String pointer, JsonNode value) {
        String parentPointer = pointer.substring(0, pointer.lastIndexOf('/'));
        String name = unescape(pointer.substring(pointer.lastIndexOf('/') + 1));
        JsonNode parent = parentPointer.isEmpty() ? root : get(root, parentPointer);
        if (parent instanceof ObjectNode object) {
            object.set(name, value);
        } else if (parent instanceof ArrayNode array) {
            array.set(Integer.parseInt(name), value);
        }
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static String unescape(String value) {
        return value.replace("~1", "/").replace("~0", "~");
    }

    private record Rename(String from, String to) {
    }

    public record MissingKey(String side, String pointer, JsonNode value) {
    }

    public record NormalizationResult(JsonNode json, List<MissingKey> missingKeys) {
        public NormalizationResult {
            missingKeys = List.copyOf(missingKeys);
        }
    }
}
