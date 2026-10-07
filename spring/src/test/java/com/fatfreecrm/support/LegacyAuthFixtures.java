package com.fatfreecrm.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class LegacyAuthFixtures {

    private static final String RESOURCE = "/auth/rails-legacy-users.json";

    private LegacyAuthFixtures() {
    }

    public record LegacyUser(
        String username,
        String email,
        String password,
        String encryptedPassword,
        String passwordSalt,
        boolean admin,
        boolean confirmed,
        boolean suspended,
        String firstName,
        String lastName
    ) {
    }

    public static String encryptor() {
        return root().path("encryptor").asText();
    }

    public static int stretches() {
        return root().path("stretches").asInt();
    }

    public static List<LegacyUser> users() {
        List<LegacyUser> users = new ArrayList<>();
        for (JsonNode user : root().path("users")) {
            users.add(new LegacyUser(
                user.path("username").asText(),
                user.path("email").asText(),
                user.path("password").asText(),
                user.path("encrypted_password").asText(),
                user.path("password_salt").asText(),
                user.path("admin").asBoolean(),
                user.path("confirmed").asBoolean(),
                user.path("suspended").asBoolean(),
                user.path("first_name").asText(null),
                user.path("last_name").asText(null)
            ));
        }
        return users;
    }

    public static LegacyUser user(String username) {
        return users().stream()
            .filter(user -> user.username().equals(username))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Missing fixture user: " + username));
    }

    private static JsonNode root() {
        try (InputStream resource = LegacyAuthFixtures.class.getResourceAsStream(RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            return new ObjectMapper().readTree(resource);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + RESOURCE, exception);
        }
    }
}
