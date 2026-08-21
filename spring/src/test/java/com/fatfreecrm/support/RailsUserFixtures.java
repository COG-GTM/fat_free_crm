package com.fatfreecrm.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads {@code fixtures/rails-legacy-users.json}: rows produced by running the
 * real Rails app (Devise {@code :authlogic_sha512}, {@code stretches = 20})
 * against PostgreSQL. Regenerate with the script in
 * {@code docs/migration/phase1-spring-foundation.md} if Devise settings change.
 */
public final class RailsUserFixtures {

    private static final String RESOURCE = "/fixtures/rails-legacy-users.json";

    private RailsUserFixtures() {
    }

    public record LegacyUser(String username,
                             String email,
                             boolean admin,
                             String password,
                             String encryptedPassword,
                             String passwordSalt) {
    }

    public static int stretches() {
        return root().path("devise").path("stretches").asInt();
    }

    public static String encryptor() {
        return root().path("devise").path("encryptor").asText();
    }

    public static List<LegacyUser> users() {
        List<LegacyUser> users = new ArrayList<>();
        for (JsonNode node : root().path("users")) {
            users.add(new LegacyUser(
                node.path("username").asText(),
                node.path("email").asText(),
                node.path("admin").asBoolean(),
                node.path("password").asText(),
                node.path("encrypted_password").asText(),
                node.path("password_salt").asText()));
        }
        return users;
    }

    public static LegacyUser user(String username) {
        return users().stream()
            .filter(user -> user.username().equals(username))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No fixture user named " + username));
    }

    private static JsonNode root() {
        try (InputStream stream = RailsUserFixtures.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing test resource " + RESOURCE);
            }
            return new ObjectMapper().readTree(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + RESOURCE, e);
        }
    }
}
