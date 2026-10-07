package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ContractClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, FixtureUsers.FixtureUser> USERS = Map.of(
        "admin", new FixtureUsers.FixtureUser("admin", 1, "admin", "admin@contract.example", "contract-password",
            true, false)
    );

    @Test
    void anonymousRequestsEncodeQueryParametersAndCarryNoCredentialsOrBody() throws Exception {
        AtomicReference<HttpExchange> seen = new AtomicReference<>();
        try (StubServer server = new StubServer(exchange -> {
            seen.set(exchange);
            StubServer.respond(exchange, 200, "application/json", "[]");
        })) {
            ContractClient client = new ContractClient(server.url() + "/", "http://127.0.0.1:1/", USERS);
            ContractCase contractCase = contractCase("GET", "/accounts", "anonymous",
                JSON.readTree("{\"q\":\"a b&c\",\"ids\":[1,2],\"flag\":true}"), null);

            ContractClient.RequestResult result = client.send(contractCase, true);

            assertEquals(server.url() + "/accounts.json?q=a+b%26c&ids=1&ids=2&flag=true", result.url());
            assertEquals("/accounts.json", seen.get().getRequestURI().getPath());
            assertEquals("q=a+b%26c&ids=1&ids=2&flag=true", seen.get().getRequestURI().getRawQuery());
            assertEquals("GET", seen.get().getRequestMethod());
            assertEquals("application/json", seen.get().getRequestHeaders().getFirst("Accept"));
            assertNull(seen.get().getRequestHeaders().getFirst("Authorization"));
            assertNull(seen.get().getRequestHeaders().getFirst("Content-Type"));
            assertNull(seen.get().getRequestHeaders().getFirst("X-CSRF-Token"));
            assertFalse(result.authenticated());
            assertTrue(result.notes().isEmpty());
            assertEquals(200, result.response().status());
        }
    }

    @Test
    void jsonBodiesAreSerialisedWithAJsonContentType() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (StubServer server = new StubServer(exchange -> {
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(read(exchange));
            StubServer.respond(exchange, 201, "application/json", "{\"id\":9}");
        })) {
            ContractClient client = new ContractClient("http://127.0.0.1:1", server.url(), USERS);
            ContractCase contractCase = contractCase("POST", "/accounts", "anonymous", JSON.createObjectNode(),
                JSON.readTree("{\"name\":\"New account\",\"access\":\"Private\"}"));

            ContractClient.RequestResult result = client.send(contractCase, false);

            assertEquals("POST", method.get());
            assertEquals("application/json", contentType.get());
            assertEquals("{\"name\":\"New account\",\"access\":\"Private\"}", body.get());
            assertEquals(server.url() + "/api/v1/accounts", result.url());
            assertEquals(201, result.response().status());
        }
    }

    @Test
    void railsSideTargetedAtSpringUsesTheSpringBaseUrlAndJwt() throws Exception {
        AtomicInteger railsHits = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        try (StubServer rails = new StubServer(exchange -> {
            railsHits.incrementAndGet();
            StubServer.respond(exchange, 500, "text/plain", "should not be called");
        }); StubServer spring = new StubServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/api/v1/auth/login")) {
                StubServer.respond(exchange, 200, "application/json",
                    "{\"accessToken\":\"jwt-token\",\"tokenType\":\"Bearer\",\"expiresIn\":3600}");
                return;
            }
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            StubServer.respond(exchange, 200, "application/json", "{\"id\":1}");
        })) {
            ContractClient client = new ContractClient(rails.url(), spring.url(), USERS);
            ContractCase selfCheck = new ContractCase("auth-login-spring-self-check", "AB-264", "enforced", "GET",
                "/users/me", new ContractCase.SideRequest("/api/v1/users/me", ContractCase.Target.SPRING),
                new ContractCase.SideRequest("/api/v1/users/me", ContractCase.Target.SPRING),
                JSON.createObjectNode(), null, "admin", JSON.createObjectNode(), "");

            ContractClient.RequestResult result = client.send(selfCheck, true);

            assertEquals(0, railsHits.get());
            assertEquals(spring.url() + "/api/v1/users/me", result.url());
            assertEquals("Bearer jwt-token", authorization.get());
            assertTrue(result.authenticated());
            assertTrue(result.notes().isEmpty());
        }
    }

    @Test
    void unknownFixtureUsersFailFastBeforeAnyRequestIsSent() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            hits.incrementAndGet();
            StubServer.respond(exchange, 200, "text/html", "");
        })) {
            ContractClient client = new ContractClient(server.url(), server.url(), USERS);
            ContractCase contractCase = contractCase("GET", "/accounts", "zed", JSON.createObjectNode(), null);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> client.send(contractCase, true));
            assertEquals("Unknown contract fixture user: zed", error.getMessage());
            assertEquals(0, hits.get());
        }
    }

    @Test
    void authenticationFailuresAreWrappedWithUserTargetAndUrl() throws Exception {
        try (StubServer rails = new StubServer(exchange ->
            StubServer.respond(exchange, 500, "text/html", "boom"))) {
            ContractClient client = new ContractClient(rails.url(), "http://127.0.0.1:1", USERS);
            ContractCase contractCase = contractCase("GET", "/accounts", "admin", JSON.createObjectNode(), null);

            IOException error = assertThrows(IOException.class, () -> client.send(contractCase, true));

            assertTrue(error.getMessage().startsWith("Authentication failed for fixture user admin via RAILS for "
                + rails.url() + "/accounts.json: IOException: Rails sign-in page is unreachable (HTTP 500)"),
                error.getMessage());
        }
    }

    @Test
    void unreachableTargetsFailWithTheMethodAndUrl() throws Exception {
        String railsUrl;
        try (StubServer rails = new StubServer(exchange -> StubServer.respond(exchange, 200, null, ""))) {
            railsUrl = rails.url();
        }
        ContractClient client = new ContractClient(railsUrl, "http://127.0.0.1:1", USERS);
        ContractCase contractCase = contractCase("GET", "/accounts", "anonymous", JSON.createObjectNode(), null);

        IOException error = assertThrows(IOException.class, () -> client.send(contractCase, true));

        assertEquals("Request failed: GET " + railsUrl + "/accounts.json", error.getMessage());
    }

    @Test
    void unavailableRailsAuthIsNotedOnceAndTheRequestStillGoesOutAnonymously() throws Exception {
        AtomicReference<HttpExchange> accounts = new AtomicReference<>();
        try (StubServer rails = new StubServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/users/sign_in")) {
                StubServer.respond(exchange, 200, "text/html", "<html><body>no token here</body></html>");
                return;
            }
            accounts.set(exchange);
            StubServer.respond(exchange, 401, "application/json", "{\"error\":\"You need to sign in\"}");
        })) {
            ContractClient client = new ContractClient(rails.url(), "http://127.0.0.1:1", USERS);
            ContractCase contractCase = contractCase("POST", "/accounts", "admin", JSON.createObjectNode(),
                JSON.readTree("{\"name\":\"x\"}"));

            ContractClient.RequestResult result = client.send(contractCase, true);

            assertFalse(result.authenticated());
            assertEquals(List.of("rails auth unavailable (CSRF token missing)"), result.notes());
            assertEquals(401, result.response().status());
            assertNull(accounts.get().getRequestHeaders().getFirst("X-CSRF-Token"));
            assertNull(accounts.get().getRequestHeaders().getFirst("Authorization"));
        }
    }

    @Test
    void authenticatedRailsReadsDoNotSendTheCsrfHeader() throws Exception {
        AtomicReference<HttpExchange> accounts = new AtomicReference<>();
        try (StubServer rails = new StubServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/users/sign_in") && exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().add("Set-Cookie", "_ffcrm_session=abc; Path=/");
                StubServer.respond(exchange, 200, "text/html",
                    "<meta name=\"csrf-token\" content=\"login-token\" />");
            } else if (path.equals("/users/sign_in")) {
                exchange.getResponseHeaders().add("Location", "/");
                StubServer.respond(exchange, 302, "text/html", "");
            } else if (path.equals("/")) {
                StubServer.respond(exchange, 200, "text/html", "<meta name=\"csrf-token\" content=\"home-token\" />");
            } else {
                accounts.set(exchange);
                StubServer.respond(exchange, 200, "application/json", "[]");
            }
        })) {
            ContractClient client = new ContractClient(rails.url(), "http://127.0.0.1:1", USERS);
            ContractCase contractCase = contractCase("GET", "/accounts", "admin", JSON.createObjectNode(), null);

            ContractClient.RequestResult result = client.send(contractCase, true);

            assertTrue(result.authenticated());
            assertTrue(result.notes().isEmpty());
            assertNull(accounts.get().getRequestHeaders().getFirst("X-CSRF-Token"));
            assertNull(accounts.get().getRequestHeaders().getFirst("Authorization"));
            assertTrue(accounts.get().getRequestHeaders().getFirst("Cookie").contains("_ffcrm_session=abc"));
        }
    }

    private static String read(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ContractCase contractCase(String method, String path, String auth, JsonNode params, JsonNode body) {
        return new ContractCase("case", "AB-266", "pending", method, path,
            new ContractCase.SideRequest(path + ".json", ContractCase.Target.RAILS),
            new ContractCase.SideRequest("/api/v1" + path, ContractCase.Target.SPRING),
            params, body, auth, JSON.createObjectNode(), "");
    }
}
