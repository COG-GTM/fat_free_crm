package com.fatfreecrm.contract;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public final class CaseLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String DIRECTORY = "contract/cases";

    private CaseLoader() {
    }

    public static List<ContractCase> load() throws IOException {
        List<ContractCase> cases = new ArrayList<>();
        Enumeration<URL> directories = Thread.currentThread().getContextClassLoader().getResources(DIRECTORY);
        while (directories.hasMoreElements()) {
            URL directory = directories.nextElement();
            if (!directory.getProtocol().equals("file")) {
                continue;
            }
            try {
                List<Path> files;
                try (var paths = Files.list(Path.of(directory.toURI()))) {
                    files = paths.filter(path -> path.toString().endsWith(".yml"))
                        .sorted(Comparator.naturalOrder()).toList();
                }
                for (Path file : files) {
                    try (InputStream input = Files.newInputStream(file)) {
                        addCases(YAML.readTree(input), cases);
                    }
                }
            } catch (URISyntaxException exception) {
                throw new IOException("Invalid contract case resource URL: " + directory, exception);
            }
        }
        return List.copyOf(cases);
    }

    public static List<ContractCase> parse(JsonNode root) {
        List<ContractCase> cases = new ArrayList<>();
        addCases(root, cases);
        return List.copyOf(cases);
    }

    private static void addCases(JsonNode root, List<ContractCase> cases) {
        JsonNode entries = root.isArray() ? root : root.path("cases");
        if (!entries.isArray()) {
            throw new IllegalArgumentException("Contract case file must contain a list of cases.");
        }
        entries.forEach(node -> cases.add(parseCase(node)));
    }

    private static ContractCase parseCase(JsonNode node) {
        String path = text(node, "path", null);
        String method = text(node, "method", "GET").toUpperCase(Locale.ROOT);
        String status = text(node, "status", "pending").toLowerCase(Locale.ROOT);
        if (!status.equals("pending") && !status.equals("enforced")) {
            throw new IllegalArgumentException("Contract case status must be pending or enforced: " + status);
        }
        ContractCase.SideRequest rails = side(
            node.get("rails"), path == null ? null : path + ".json", ContractCase.Target.RAILS);
        ContractCase.SideRequest spring = side(
            node.get("spring"), path == null ? null : "/api/v1" + path, ContractCase.Target.SPRING);
        String logicalPath = path != null ? path : removeJsonSuffix(rails.path());
        return new ContractCase(
            requiredText(node, "id"),
            text(node, "ticket", ""),
            status,
            method,
            logicalPath,
            rails,
            spring,
            objectOrEmpty(node.get("params")),
            node.get("body"),
            text(node, "auth", "anonymous"),
            objectOrEmpty(node.get("normalize")),
            text(node, "description", ""),
            expectation(node.get("expect")),
            node.path("reset").asBoolean(false),
            node.get("dbAssert"),
            node.get("setup")
        );
    }

    private static JsonNode expectation(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("Contract case expect must be an object.");
        }
        JsonNode status = node.get("status");
        if (status != null && (!status.isIntegralNumber() || !status.canConvertToInt())) {
            throw new IllegalArgumentException("Contract case expectation status must be an integer.");
        }
        JsonNode json = node.get("json");
        if (json != null && !json.isObject()) {
            throw new IllegalArgumentException("Contract case expectation json must map pointers to values.");
        }
        if (json != null) {
            json.fieldNames().forEachRemaining(pointer -> {
                try {
                    JsonPointer.compile(pointer);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Invalid expectation JSON pointer: " + pointer, exception);
                }
            });
        }
        return node.deepCopy();
    }

    private static ContractCase.SideRequest side(JsonNode node, String defaultPath, ContractCase.Target defaultTarget) {
        if (node == null || node.isNull()) {
            return new ContractCase.SideRequest(defaultPath, defaultTarget);
        }
        if (node.isTextual()) {
            return new ContractCase.SideRequest(node.asText(), defaultTarget);
        }
        String path = text(node, "path", defaultPath);
        ContractCase.Target target = ContractCase.Target.valueOf(
            text(node, "target", defaultTarget.name()).toUpperCase(Locale.ROOT)
        );
        String bodyPointer = text(node, "bodyPointer", null);
        if (bodyPointer != null) {
            validateBodyPointer(bodyPointer);
        }
        return new ContractCase.SideRequest(path, target, bodyPointer);
    }

    private static void validateBodyPointer(String bodyPointer) {
        if (!bodyPointer.startsWith("/")) {
            throw new IllegalArgumentException("Contract side bodyPointer must start with '/': " + bodyPointer);
        }
        for (int index = 0; index < bodyPointer.length(); index++) {
            if (bodyPointer.charAt(index) == '~'
                && (index + 1 == bodyPointer.length()
                    || (bodyPointer.charAt(index + 1) != '0' && bodyPointer.charAt(index + 1) != '1'))) {
                throw new IllegalArgumentException("Invalid contract side bodyPointer: " + bodyPointer);
            }
            if (bodyPointer.charAt(index) == '~') {
                index++;
            }
        }
        try {
            JsonPointer.compile(bodyPointer);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid contract side bodyPointer: " + bodyPointer, exception);
        }
    }

    private static JsonNode objectOrEmpty(JsonNode node) {
        return node == null || node.isNull() ? YAML.createObjectNode() : node;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required contract case field: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private static String removeJsonSuffix(String path) {
        return path != null && path.endsWith(".json") ? path.substring(0, path.length() - 5) : path;
    }
}
