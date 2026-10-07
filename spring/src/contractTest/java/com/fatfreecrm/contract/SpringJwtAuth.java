package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public final class SpringJwtAuth implements AuthAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String baseUrl;
    private final Map<String, FixtureUsers.FixtureUser> users;
    private final HttpClient client;

    public SpringJwtAuth(String baseUrl, Map<String, FixtureUsers.FixtureUser> users) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.users = Map.copyOf(users);
        this.client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public AuthContext authenticate(String userKey) throws IOException, InterruptedException {
        FixtureUsers.FixtureUser user = users.get(userKey);
        if (user == null) {
            throw new IllegalArgumentException("Unknown contract fixture user: " + userKey);
        }
        String body = JSON.writeValueAsString(Map.of("username", user.username(), "password", user.password()));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/login"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404 || response.statusCode() == 401) {
            return new AuthContext(client, null, null, "spring auth unavailable (login returned "
                + response.statusCode() + ")");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new AuthContext(client, null, null,
                "spring auth unavailable (login returned " + response.statusCode() + ")");
        }
        JsonNode tokenResponse = JSON.readTree(response.body());
        String token = tokenResponse.path("accessToken").asText();
        if (token.isBlank()) {
            return new AuthContext(client, null, null,
                "spring auth unavailable (login response omitted accessToken)");
        }
        String tokenType = tokenResponse.path("tokenType").asText("Bearer");
        return new AuthContext(client, tokenType + " " + token, null, null);
    }
}
