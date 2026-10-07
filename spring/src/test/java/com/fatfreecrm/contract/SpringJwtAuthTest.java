package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SpringJwtAuthTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void unknownUserIsRejectedBeforeAnyRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            requests.incrementAndGet();
            StubServer.respond(exchange, 200, "application/json", "{}");
        })) {
            SpringJwtAuth auth = new SpringJwtAuth(server.url(), Map.of("alice", user("alice")));
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> auth.authenticate("ghost"));
            assertEquals("Unknown contract fixture user: ghost", exception.getMessage());
            assertEquals(0, requests.get());
        }
    }

    @Test
    void postsFixtureCredentialsAsJsonToTheLoginEndpoint() throws Exception {
        AtomicReference<HttpExchange> request = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (StubServer server = new StubServer(exchange -> {
            request.set(exchange);
            body.set(readBody(exchange));
            StubServer.respond(exchange, 200, "application/json", "{\"accessToken\":\"t\",\"expiresIn\":60}");
        })) {
            SpringJwtAuth auth = new SpringJwtAuth(server.url() + "/", Map.of("alice", user("alice")));
            AuthContext context = auth.authenticate("alice");
            assertEquals("POST", request.get().getRequestMethod());
            assertEquals("/api/v1/auth/login", request.get().getRequestURI().getPath());
            assertEquals("application/json", request.get().getRequestHeaders().getFirst("Content-Type"));
            assertEquals("application/json", request.get().getRequestHeaders().getFirst("Accept"));
            JsonNode sent = JSON.readTree(body.get());
            assertEquals(2, sent.size());
            assertEquals("alice", sent.path("username").asText());
            assertEquals("contract-password", sent.path("password").asText());
            assertEquals("Bearer t", context.authorization());
            assertNull(context.csrfToken());
            assertNull(context.note());
        }
    }

    @Test
    void honorsTheTokenTypeReturnedByTheLoginResponse() throws Exception {
        try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 200, "application/json",
            "{\"accessToken\":\"abc\",\"tokenType\":\"Token\",\"expiresIn\":60}"))) {
            SpringJwtAuth auth = new SpringJwtAuth(server.url(), Map.of("alice", user("alice")));
            assertEquals("Token abc", auth.authenticate("alice").authorization());
        }
    }

    @Test
    void nonSuccessLoginStatusesAreUnavailableAndNotCached() throws Exception {
        for (int status : new int[] {401, 404, 500, 503}) {
            AtomicInteger logins = new AtomicInteger();
            try (StubServer server = new StubServer(exchange -> {
                logins.incrementAndGet();
                StubServer.respond(exchange, status, "application/problem+json", "{\"status\":" + status + "}");
            })) {
                SpringJwtAuth auth = new SpringJwtAuth(server.url(), Map.of("alice", user("alice")));
                AuthContext first = auth.authenticate("alice");
                assertEquals("spring auth unavailable (login returned " + status + ")", first.note());
                assertNull(first.authorization());
                assertNull(first.csrfToken());
                assertNotSame(first, auth.authenticate("alice"));
                assertEquals(2, logins.get());
            }
        }
    }

    @Test
    void loginResponsesWithoutAnAccessTokenAreUnavailableAndNotCached() throws Exception {
        for (String body : new String[] {"{\"tokenType\":\"Bearer\",\"expiresIn\":3600}", "{\"accessToken\":\" \"}"}) {
            AtomicInteger logins = new AtomicInteger();
            try (StubServer server = new StubServer(exchange -> {
                logins.incrementAndGet();
                StubServer.respond(exchange, 200, "application/json", body);
            })) {
                SpringJwtAuth auth = new SpringJwtAuth(server.url(), Map.of("alice", user("alice")));
                AuthContext context = auth.authenticate("alice");
                assertEquals("spring auth unavailable (login response omitted accessToken)", context.note());
                assertNull(context.authorization());
                auth.authenticate("alice");
                assertEquals(2, logins.get());
            }
        }
    }

    @Test
    void malformedLoginBodiesPropagateAsIoErrors() throws Exception {
        try (StubServer server = new StubServer(
            exchange -> StubServer.respond(exchange, 200, "text/html", "<html>not json</html>"))) {
            SpringJwtAuth auth = new SpringJwtAuth(server.url(), Map.of("alice", user("alice")));
            assertThrows(IOException.class, () -> auth.authenticate("alice"));
        }
    }

    @Test
    void cachesTokensPerUser() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            String username = usernameOf(exchange);
            logins.incrementAndGet();
            StubServer.respond(exchange, 200, "application/json",
                "{\"accessToken\":\"token-" + username + "\",\"expiresIn\":3600}");
        })) {
            Map<String, FixtureUsers.FixtureUser> users = Map.of("alice", user("alice"), "bob", user("bob"));
            SpringJwtAuth auth = new SpringJwtAuth(server.url(), users);
            AuthContext alice = auth.authenticate("alice");
            AuthContext bob = auth.authenticate("bob");
            assertEquals("Bearer token-alice", alice.authorization());
            assertEquals("Bearer token-bob", bob.authorization());
            assertNotEquals(alice, bob);
            assertSame(alice, auth.authenticate("alice"));
            assertSame(bob, auth.authenticate("bob"));
            assertEquals(2, logins.get());
        }
    }

    private static String readBody(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String usernameOf(HttpExchange exchange) {
        try {
            return JSON.readTree(readBody(exchange)).path("username").asText();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static FixtureUsers.FixtureUser user(String key) {
        return new FixtureUsers.FixtureUser(key, 2, key, key + "@contract.example", "contract-password", false,
            false);
    }
}
