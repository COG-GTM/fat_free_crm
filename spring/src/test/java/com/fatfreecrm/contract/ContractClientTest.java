package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ContractClientTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private record Captured(String method, String path, String rawQuery, Headers headers, String body) {
    }

    @Test
    void anonymousRequestsSendAcceptJsonWithoutAuthHeadersAndEncodeQueryParameters() throws Exception {
        AtomicReference<Captured> captured = new AtomicReference<>();
        try (StubServer server = new StubServer(exchange -> capture(exchange, captured, 200, "{\"ok\":true}"))) {
            ContractCase contractCase = parse("""
                - id: query
                  path: /accounts
                  params:
                    page: 1
                    q: "x y"
                    tags: ["a b", "c&d"]
                """);
            ContractClient client = new ContractClient(server.url() + "/", server.url() + "/", Map.of());
            ContractClient.RequestResult rails = client.send(contractCase, true);
            assertEquals(server.url() + "/accounts.json?page=1&q=x+y&tags=a+b&tags=c%26d", rails.url());
            assertEquals("GET", captured.get().method());
            assertEquals("/accounts.json", captured.get().path());
            assertEquals("page=1&q=x+y&tags=a+b&tags=c%26d", captured.get().rawQuery());
            assertEquals("application/json", captured.get().headers().getFirst("Accept"));
            assertNull(captured.get().headers().getFirst("Authorization"));
            assertNull(captured.get().headers().getFirst("X-CSRF-Token"));
            assertNull(captured.get().headers().getFirst("Content-Type"));
            assertEquals("", captured.get().body());
            assertFalse(rails.authenticated());
            assertTrue(rails.notes().isEmpty());
            assertEquals(200, rails.response().status());
            assertTrue(rails.response().json().path("ok").asBoolean());

            ContractClient.RequestResult spring = client.send(contractCase, false);
            assertEquals(server.url() + "/api/v1/accounts?page=1&q=x+y&tags=a+b&tags=c%26d", spring.url());
            assertEquals("/api/v1/accounts", captured.get().path());
        }
    }

    @Test
    void jsonBodiesAreSerializedWithContentTypeAndTheCaseMethod() throws Exception {
        AtomicReference<Captured> captured = new AtomicReference<>();
        try (StubServer server = new StubServer(exchange -> capture(exchange, captured, 200, "{}"))) {
            ContractCase contractCase = parse("""
                - id: update
                  method: PATCH
                  path: /accounts/101
                  body:
                    account:
                      name: Renamed
                      tags: [1, 2]
                """);
            ContractClient client = new ContractClient(server.url(), server.url(), Map.of());
            client.send(contractCase, false);
            assertEquals("PATCH", captured.get().method());
            assertEquals("/api/v1/accounts/101", captured.get().path());
            assertEquals("application/json", captured.get().headers().getFirst("Content-Type"));
            assertEquals("{\"account\":{\"name\":\"Renamed\",\"tags\":[1,2]}}", captured.get().body());

            client.send(contractCase, true);
            assertEquals("/accounts/101.json", captured.get().path());
            assertNull(captured.get().headers().getFirst("X-CSRF-Token"));
            assertEquals("{\"account\":{\"name\":\"Renamed\",\"tags\":[1,2]}}", captured.get().body());
        }
    }

    @Test
    void explicitSideTargetRoutesToTheOtherBaseUrl() throws Exception {
        AtomicReference<String> railsHit = new AtomicReference<>();
        AtomicReference<String> springHit = new AtomicReference<>();
        try (StubServer rails = new StubServer(exchange -> {
            railsHit.set(exchange.getRequestURI().getPath());
            StubServer.respond(exchange, 200, "application/json", "{}");
        });
             StubServer spring = new StubServer(exchange -> {
                 springHit.set(exchange.getRequestURI().getPath());
                 StubServer.respond(exchange, 200, "application/json", "{}");
             })) {
            ContractCase contractCase = parse("""
                - id: self-check
                  path: /users/me
                  rails:
                    path: /api/v1/users/me
                    target: spring
                """);
            ContractClient client = new ContractClient(rails.url(), spring.url(), Map.of());
            ContractClient.RequestResult result = client.send(contractCase, true);
            assertEquals(spring.url() + "/api/v1/users/me", result.url());
            assertEquals("/api/v1/users/me", springHit.get());
            assertNull(railsHit.get());
        }
    }

    @Test
    void unknownFixtureUsersFailFastOnBothSides() throws Exception {
        try (StubServer server = new StubServer(
            exchange -> StubServer.respond(exchange, 200, "application/json", "{}"))) {
            ContractCase contractCase = parse("""
                - id: ghost
                  path: /accounts
                  auth: ghost
                """);
            ContractClient client = new ContractClient(server.url(), server.url(), Map.of());
            IllegalArgumentException rails = assertThrows(IllegalArgumentException.class,
                () -> client.send(contractCase, true));
            assertEquals("Unknown contract fixture user: ghost", rails.getMessage());
            IllegalArgumentException spring = assertThrows(IllegalArgumentException.class,
                () -> client.send(contractCase, false));
            assertEquals("Unknown contract fixture user: ghost", spring.getMessage());
        }
    }

    @Test
    void railsAuthenticationFailuresAreWrappedWithUserSideAndUrl() throws Exception {
        try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 503, "text/html", "down"))) {
            ContractCase contractCase = parse("""
                - id: down
                  path: /accounts
                  auth: alice
                """);
            ContractClient client = new ContractClient(server.url(), server.url(), Map.of("alice", user("alice")));
            IOException exception = assertThrows(IOException.class, () -> client.send(contractCase, true));
            assertEquals("Authentication failed for fixture user alice via RAILS for " + server.url()
                + "/accounts.json: IOException: Rails sign-in page is unreachable (HTTP 503) at " + server.url()
                + "/users/sign_in", exception.getMessage());
            assertInstanceOf(IOException.class, exception.getCause());
        }
    }

    @Test
    void unavailableAuthStillSendsTheRequestUnauthenticatedAndRecordsASideNote() throws Exception {
        AtomicReference<Captured> captured = new AtomicReference<>();
        try (StubServer server = new StubServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/users/sign_in")) {
                StubServer.respond(exchange, 200, "text/html", "<html>no token here</html>");
            } else if (path.equals("/api/v1/auth/login")) {
                StubServer.respond(exchange, 404, "application/problem+json", "{\"status\":404}");
            } else {
                capture(exchange, captured, 401, "{\"error\":\"unauthorized\"}");
            }
        })) {
            ContractCase contractCase = parse("""
                - id: unavailable
                  method: POST
                  path: /accounts
                  auth: alice
                  body:
                    account:
                      name: x
                """);
            ContractClient client = new ContractClient(server.url(), server.url(), Map.of("alice", user("alice")));
            ContractClient.RequestResult rails = client.send(contractCase, true);
            assertEquals(List.of("rails auth unavailable (CSRF token missing)"), rails.notes());
            assertFalse(rails.authenticated());
            assertEquals(401, rails.response().status());
            assertEquals("/accounts.json", captured.get().path());
            assertNull(captured.get().headers().getFirst("X-CSRF-Token"));
            assertNull(captured.get().headers().getFirst("Authorization"));

            ContractClient.RequestResult spring = client.send(contractCase, false);
            assertEquals(List.of("spring auth unavailable (login returned 404)"), spring.notes());
            assertFalse(spring.authenticated());
            assertEquals("/api/v1/accounts", captured.get().path());
            assertNull(captured.get().headers().getFirst("Authorization"));
        }
    }

    @Test
    void connectionFailuresAreWrappedWithMethodAndUrl() throws Exception {
        String unreachable;
        try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 200, "text/plain", ""))) {
            unreachable = server.url();
        }
        ContractCase contractCase = parse("""
            - id: offline
              path: /accounts
            """);
        ContractClient client = new ContractClient(unreachable, unreachable, Map.of());
        IOException exception = assertThrows(IOException.class, () -> client.send(contractCase, true));
        assertEquals("Request failed: GET " + unreachable + "/accounts.json", exception.getMessage());
        assertInstanceOf(IOException.class, exception.getCause());
    }

    private static void capture(HttpExchange exchange, AtomicReference<Captured> captured, int status, String body) {
        try {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.set(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders(), requestBody));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        StubServer.respond(exchange, status, "application/json", body);
    }

    private static ContractCase parse(String yaml) throws Exception {
        return CaseLoader.parse(YAML.readTree(yaml)).getFirst();
    }

    private static FixtureUsers.FixtureUser user(String key) {
        return new FixtureUsers.FixtureUser(key, 2, key, key + "@contract.example", "contract-password", false,
            false);
    }
}
