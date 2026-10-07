package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Allowlist {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final List<AllowlistEntry> entries;
    private final Map<String, Integer> hits = new LinkedHashMap<>();

    public Allowlist(List<AllowlistEntry> entries) {
        this.entries = List.copyOf(entries);
        entries.forEach(entry -> hits.put(entry.id(), 0));
    }

    public static Allowlist load() throws IOException {
        try (InputStream input = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("contract/allowlist.yml")) {
            if (input == null) {
                throw new IOException("Missing contract/allowlist.yml resource.");
            }
            JsonNode root = YAML.readTree(input).path("allowlist");
            List<AllowlistEntry> entries = new ArrayList<>();
            root.forEach(node -> {
                JsonNode match = node.path("match");
                entries.add(new AllowlistEntry(
                    node.path("id").asText(),
                    match.path("method").asText("*"),
                    match.path("path").asText("**"),
                    match.path("case").asText(null),
                    node.path("kind").asText(),
                    node.path("reason").asText(""),
                    node.path("reference").asText(""),
                    node
                ));
            });
            return new Allowlist(entries);
        }
    }

    public List<AllowlistEntry> matching(ContractCase contractCase) {
        return entries.stream().filter(entry -> matches(entry, contractCase)).toList();
    }

    public List<AllowlistEntry> entries() {
        return entries;
    }

    public void hit(AllowlistEntry entry) {
        hits.computeIfPresent(entry.id(), (id, count) -> count + 1);
    }

    public int hits(String id) {
        return hits.getOrDefault(id, 0);
    }

    private static boolean matches(AllowlistEntry entry, ContractCase contractCase) {
        return (entry.method().equals("*") || entry.method().equalsIgnoreCase(contractCase.method()))
            && (entry.caseId() == null || entry.caseId().equals(contractCase.id()))
            && glob(entry.path(), contractCase.path());
    }

    private static boolean glob(String glob, String path) {
        return matchesSegments(segments(glob), 0, segments(path), 0);
    }

    private static boolean matchesSegments(String[] pattern, int patternIndex, String[] path, int pathIndex) {
        if (patternIndex == pattern.length) {
            return pathIndex == path.length;
        }
        if (pattern[patternIndex].equals("**")) {
            if (patternIndex == pattern.length - 1) {
                return true;
            }
            for (int index = pathIndex; index <= path.length; index++) {
                if (matchesSegments(pattern, patternIndex + 1, path, index)) {
                    return true;
                }
            }
            return false;
        }
        if (pathIndex == path.length) {
            return false;
        }
        return (pattern[patternIndex].equals("*") || pattern[patternIndex].equals(path[pathIndex]))
            && matchesSegments(pattern, patternIndex + 1, path, pathIndex + 1);
    }

    private static String[] segments(String path) {
        String withoutLeadingSlash = path.startsWith("/") ? path.substring(1) : path;
        return withoutLeadingSlash.isEmpty() ? new String[0] : withoutLeadingSlash.split("/", -1);
    }
}
