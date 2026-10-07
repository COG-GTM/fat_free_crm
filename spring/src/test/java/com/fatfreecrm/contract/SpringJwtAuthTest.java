package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private static final Map<String, FixtureUsers.FixtureUser> USERS = Map.of(
        "alice", new FixtureUsers.FixtureUser("alice", 2, "alice", "alice@contract.example", "contract-password",
            false, false)
    );

    @Test
    void postsTheFixtureUsernameAndPasswordAsJsonToTheLoginEndpoint() throws Exception {
        AtomicReference<HttpExchange> seen = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (StubServer server = new StubServer(exchange -> {
            seen.set(exchange);
            body.set(read(exchange));
            StubServer.respond(exchange, 200, "application/json",
                "{\"accessToken\":\"abc\",\"tokenType\":\"Bearer\",\"expiresIn\":900}");
        })) {
            SpringJwtAuth auth = new SpringJwtAuth(server.url() + "/", USERS);

            AuthContext context = auth.authenticate("alice");

            assertEquals("/api/v1/auth/login", seen.get().getRequestURI().getPath());
            assertEquals("POST", seen.get().getRequestMethod());
            assertEquals("application/json", seen.get().getRequestHeaders().getFirst("Content-Type"));
            assertEquals("application/json", seen.get().getRequestHeaders().getFirst("Accept"));
            JsonNode payload = JSON.readTree(body.get());
            assertEquals(2, payload.size());
            assertEquals("alice", payload.path("username").asText());
            assertEquals("contract-password", payload.path("password").asText());
            assertFalse(payload.has("email"));
            assertEquals("Bearer abc", context.authorization());
            assertNull(context.csrfToken());
            assertNull(context.note());
        }
    }

    @Test
    void unknownFixtureUsersAreRejectedBeforeContactingSpring() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            hits.incrementAndGet();
            StubServer.respond(exchange, 200, "application/json", "{}");
        })) {
            SpringJwtAuth auth = new SpringJwtAuth(server.url(), USERS);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> auth.authenticate("zed"));
            assertEquals("Unknown contract fixture user: zed", error.getMessage());
            assertEquals(0, hits.get());
        }
    }

    @Test
    void failedLoginsAreUnavailableWithTheStatusAndAreNotCached() throws Exception {
        AtomicInteger status = new AtomicInteger(500);
        AtomicInteger hits = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            hits.incrementAndGet();
            StubServer.respond(exchange, status.get(), "application/problem+json",
                "{\"title\":\"Error\",\"status\":" + status.get() + "}");
        })) {
            SpringJwtAuth auth = new SpringJwtAuth(server.url(), USERS);

            assertEquals("spring auth unavailable (login returned 500)", auth.authenticate("alice").note());
            status.set(404);
            assertEquals("spring auth unavailable (login returned 404)", auth.authenticate("alice").note());
            status.set(401);
            AuthContext unauthorized = auth.authenticate("alice");
            assertEquals("spring auth unavailable (login returned 401)", unauthorized.note());
            assertNull(unauthorized.authorization());
            assertEquals(3, hits.get());
        }
    }

    @Test
    void successfulLoginsWithoutAnAccessTokenAreUnavailableAndRetried() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("{\"tokenType\":\"Bearer\",\"expiresIn\":900}");
        AtomicInteger hits = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            hits.incrementAndGet();
            StubServer.respond(exchange, 200, "application/json", body.get());
        })) {
            SpringJwtAuth auth = new SpringJwtAuth(server.url(), USERS);

            assertEquals("spring auth unavailable (login response omitted accessToken)",
                auth.authenticate("alice").note());
            body.set("{\"accessToken\":\"\",\"expiresIn\":900}");
            assertEquals("spring auth unavailable (login response omitted accessToken)",
                auth.authenticate("alice").note());
            assertEquals(2, hits.get());
        }
    }

    @Test
    void honoursTheTokenTypeAndDefaultsToBearer() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("{\"accessToken\":\"abc\",\"tokenType\":\"Token\"}");
        try (StubServer server = new StubServer(exchange ->
            StubServer.respond(exchange, 200, "application/json", body.get()))) {
            assertEquals("Token abc", new SpringJwtAuth(server.url(), USERS).authenticate("alice").authorization());

            body.set("{\"accessToken\":\"xyz\"}");
            assertEquals("Bearer xyz", new SpringJwtAuth(server.url(), USERS).authenticate("alice").authorization());
        }
    }

    @Test
    void malformedLoginResponsesSurfaceAsIoExceptions() throws Exception {
        try (StubServer server = new StubServer(exchange ->
            StubServer.respond(exchange, 200, "application/json", "{\"accessToken\":"))) {
            SpringJwtAuth auth = new SpringJwtAuth(server.url(), USERS);
            assertThrows(IOException.class, () -> auth.authenticate("alice"));
        }
    }

    private static String read(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
