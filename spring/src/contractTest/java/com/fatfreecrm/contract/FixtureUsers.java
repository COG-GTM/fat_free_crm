package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FixtureUsers {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private FixtureUsers() {
    }

    public record FixtureUser(String key, int id, String username, String email, String password, boolean admin,
                             boolean suspended) {
    }

    public static Map<String, FixtureUser> load() throws IOException {
        try (InputStream input = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("contract/users.yml")) {
            if (input == null) {
                throw new IOException("Missing contract/users.yml fixture resource.");
            }
            JsonNode root = YAML.readTree(input).path("users");
            Map<String, FixtureUser> users = new LinkedHashMap<>();
            root.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                users.put(entry.getKey(), new FixtureUser(
                    entry.getKey(),
                    value.path("id").asInt(),
                    value.path("username").asText(),
                    value.path("email").asText(),
                    value.path("password").asText(),
                    value.path("admin").asBoolean(false),
                    value.path("suspended").asBoolean(false)
                ));
            });
            return Map.copyOf(users);
        }
    }
}
