package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContractHarnessTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void mapsDefaultsAndHonorsExplicitRequestTarget() throws Exception {
        List<ContractCase> cases = CaseLoader.parse(YAML.readTree("""
            - id: default-map
              path: /accounts
            - id: targeted-map
              path: /accounts
              spring:
                path: /accounts.json
                target: rails
            """));
        assertEquals("/accounts.json", cases.get(0).rails().path());
        assertEquals("/api/v1/accounts", cases.get(0).spring().path());
        assertEquals(ContractCase.Target.RAILS, cases.get(1).spring().target());
        assertEquals("/accounts", cases.get(1).path());
    }

    @Test
    void loadsInitialCasesFromClasspath() throws Exception {
        List<ContractCase> cases = CaseLoader.load();
        assertEquals(7, cases.size());
        ContractCase authSelfCheck = cases.stream()
            .filter(contractCase -> contractCase.id().equals("auth-login-spring-self-check"))
            .findFirst()
            .orElseThrow();
        assertEquals("/api/v1/users/me", authSelfCheck.rails().path());
        assertEquals(ContractCase.Target.SPRING, authSelfCheck.rails().target());
        assertEquals("/api/v1/users/me", authSelfCheck.spring().path());
        assertEquals(ContractCase.Target.SPRING, authSelfCheck.spring().target());
        assertEquals(200, authSelfCheck.expect().path("status").asInt());
        assertEquals(1, authSelfCheck.expect().path("json").path("/id").asInt());
        assertEquals("admin", authSelfCheck.expect().path("json").path("/username").asText());
        assertTrue(authSelfCheck.expect().path("json").path("/admin").asBoolean());
        assertEquals(200, cases.getFirst().expect().path("status").asInt());
        assertEquals(5, FixtureUsers.load().size());
    }

    @Test
    void railsSessionAdapterSubmitsCsrfAndCookieAndReusesSession() throws Exception {
        AtomicBoolean loginFieldsValid = new AtomicBoolean();
        AtomicBoolean requestCookieValid = new AtomicBoolean();
        AtomicBoolean writeCookieValid = new AtomicBoolean();
        AtomicBoolean writeCsrfHeaderValid = new AtomicBoolean();
        try (StubServer server = new StubServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/users/sign_in")
                && exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().add("Set-Cookie", "_crm_session=initial; Path=/");
                String signInPage = "<meta name=\"csrf-token\" content=\"token-123\">"
                    + "<input name=\"authenticity_token\" value=\"token-123\">";
                StubServer.respond(exchange, 200, "text/html",
                    signInPage);
                return;
            }
            if (exchange.getRequestURI().getPath().equals("/users/sign_in")) {
                try {
                    String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                    String body = new String(exchange.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                    loginFieldsValid.set(cookie != null && cookie.contains("_crm_session=initial")
                        && body.contains("user%5Bemail%5D=alice%40contract.example")
                        && body.contains("user%5Bpassword%5D=contract-password")
                        && body.contains("authenticity_token=token-123"));
                    exchange.getResponseHeaders().add("Location", "/");
                    exchange.sendResponseHeaders(302, -1);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                return;
            }
            if (exchange.getRequestURI().getPath().equals("/")
                && exchange.getRequestMethod().equals("GET")) {
                StubServer.respond(exchange, 200, "text/html",
                    "<meta name=\"csrf-token\" content=\"post-login-token\">");
                return;
            }
            if (exchange.getRequestURI().getPath().equals("/accounts.json")
                && exchange.getRequestMethod().equals("POST")) {
                writeCookieValid.set(exchange.getRequestHeaders().getFirst("Cookie") != null
                    && exchange.getRequestHeaders().getFirst("Cookie").contains("_crm_session=initial"));
                writeCsrfHeaderValid.set("post-login-token".equals(
                    exchange.getRequestHeaders().getFirst("X-CSRF-Token")));
                StubServer.respond(exchange, writeCsrfHeaderValid.get() ? 201 : 422,
                    "application/json", "{\"id\":101}");
                return;
            }
            requestCookieValid.set(exchange.getRequestHeaders().getFirst("Cookie") != null
                && exchange.getRequestHeaders().getFirst("Cookie").contains("_crm_session=initial"));
            StubServer.respond(exchange, 200, "application/json; charset=utf-8", """
                {"ok":true}
                """);
        })) {
            FixtureUsers.FixtureUser alice = user("alice");
            RailsSessionAuth auth = new RailsSessionAuth(server.url(), Map.of("alice", alice));
            AuthContext context = auth.authenticate("alice");
            assertEquals("post-login-token", context.csrfToken());
            HttpRequest request = HttpRequest.newBuilder(URI.create(server.url() + "/accounts.json"))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<String> response = context.client().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(loginFieldsValid.get());
            assertTrue(requestCookieValid.get());
            assertEquals(context, auth.authenticate("alice"));
            CapturedResponse captured = CapturedResponse.from(response);
            assertEquals("application/json", captured.mediaType());
            assertNotNull(captured.json());

            ContractCase writeCase = CaseLoader.parse(YAML.readTree("""
                - id: csrf-write
                  ticket: AB-266
                  status: pending
                  method: POST
                  path: /accounts
                  auth: alice
                  body:
                    account:
                      name: csrf check
                """)).getFirst();
            ContractClient client = new ContractClient(server.url(), server.url(), Map.of("alice", alice));
            ContractClient.RequestResult write = client.send(writeCase, true);
            assertEquals(201, write.response().status());
            assertTrue(writeCookieValid.get());
            assertTrue(writeCsrfHeaderValid.get());
        }
    }

    @Test
    void springJwtAdapterSendsBearerAndMarksMissingLoginUnavailable() throws Exception {
        AtomicInteger loginRequests = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        try (StubServer server = new StubServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/api/v1/auth/login")) {
                loginRequests.incrementAndGet();
                StubServer.respond(exchange, 200, "application/json", """
                    {"accessToken":"access-123","refreshToken":"refresh","tokenType":"Bearer","expiresIn":3600}
                    """);
            } else {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                StubServer.respond(exchange, 200, "application/json", """
                    {"ok":true}
                    """);
            }
        })) {
            SpringJwtAuth auth = new SpringJwtAuth(server.url(), Map.of("alice", user("alice")));
            AuthContext context = auth.authenticate("alice");
            assertEquals(context, auth.authenticate("alice"));
            HttpRequest request = HttpRequest.newBuilder(URI.create(server.url() + "/api/v1/accounts"))
                .header("Authorization", context.authorization())
                .GET()
                .build();
            context.client().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals("Bearer access-123", authorization.get());
            assertEquals(1, loginRequests.get());

            ContractCase springCase = CaseLoader.parse(YAML.readTree("""
                - id: authenticated-spring-request
                  method: GET
                  path: /accounts
                  auth: alice
                """)).getFirst();
            ContractClient client = new ContractClient(server.url(), server.url(), Map.of("alice", user("alice")));
            ContractClient.RequestResult springResponse = client.send(springCase, false);
            assertTrue(springResponse.authenticated());
        }

        try (StubServer missing = new StubServer(exchange ->
            StubServer.respond(exchange, 404, "application/problem+json", """
                {"status":404}
                """))) {
            AuthContext context = new SpringJwtAuth(missing.url(), Map.of("alice", user("alice")))
                .authenticate("alice");
            assertEquals("spring auth unavailable (login returned 404)", context.note());
            assertFalse(context.authorization() != null);

            ContractCase enforced = CaseLoader.parse(YAML.readTree("""
                - id: auth-required
                  status: enforced
                  method: GET
                  path: /accounts
                """)).getFirst();
            CaseResult result = new CaseResult(enforced, CaseResult.Outcome.CLEAN, null, null, null, null,
                List.of(context.note()), List.of(), null);
            assertEquals(CaseResult.Outcome.ERROR, result.failIfEnforcedAuthUnavailable().outcome());
        }
    }

    @Test
    void springJwtAdapterRefreshesTokensWithLessThanThirtySecondsRemaining() throws Exception {
        AtomicInteger loginRequests = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/api/v1/auth/login")) {
                int login = loginRequests.incrementAndGet();
                StubServer.respond(exchange, 200, "application/json", """
                    {"accessToken":"access-%d","tokenType":"Bearer","expiresIn":60}
                    """.formatted(login));
            } else {
                StubServer.respond(exchange, 200, "application/json", "{}");
            }
        })) {
            MutableClock clock = new MutableClock(Instant.parse("2026-10-07T12:00:00Z"));
            SpringJwtAuth auth = new SpringJwtAuth(server.url(), Map.of("alice", user("alice")), clock);
            AuthContext initial = auth.authenticate("alice");

            clock.advance(Duration.ofSeconds(10));
            assertSame(initial, auth.authenticate("alice"));
            assertEquals(1, loginRequests.get());

            clock.advance(Duration.ofSeconds(35));
            AuthContext refreshed = auth.authenticate("alice");
            assertNotSame(initial, refreshed);
            assertEquals(2, loginRequests.get());

            clock.advance(Duration.ofSeconds(10));
            assertSame(refreshed, auth.authenticate("alice"));
            assertEquals(2, loginRequests.get());
        }
    }

    @Test
    void springJwtAdapterExpiryStartsWhenLoginRequestStarts() throws Exception {
        AtomicInteger loginRequests = new AtomicInteger();
        MutableClock clock = new MutableClock(Instant.parse("2026-10-07T12:00:00Z"));
        try (StubServer server = new StubServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/api/v1/auth/login")) {
                int login = loginRequests.incrementAndGet();
                clock.advance(Duration.ofSeconds(25));
                StubServer.respond(exchange, 200, "application/json", """
                    {"accessToken":"access-%d","tokenType":"Bearer","expiresIn":60}
                    """.formatted(login));
            } else {
                StubServer.respond(exchange, 200, "application/json", "{}");
            }
        })) {
            SpringJwtAuth auth = new SpringJwtAuth(server.url(), Map.of("alice", user("alice")), clock);
            auth.authenticate("alice");

            clock.advance(Duration.ofSeconds(25));
            auth.authenticate("alice");

            assertEquals(2, loginRequests.get());
        }
    }

    @Test
    void springJwtAdapterDoesNotCacheTokensWithoutPositiveExpiry() throws Exception {
        AtomicInteger loginRequests = new AtomicInteger();
        AtomicInteger zeroExpiryLogins = new AtomicInteger();
        AtomicInteger missingExpiryLogins = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/api/v1/auth/login")) {
                if (loginRequests.incrementAndGet() <= 2) {
                    zeroExpiryLogins.incrementAndGet();
                    StubServer.respond(exchange, 200, "application/json", """
                        {"accessToken":"access-zero","tokenType":"Bearer","expiresIn":0}
                        """);
                } else {
                    missingExpiryLogins.incrementAndGet();
                    StubServer.respond(exchange, 200, "application/json", """
                        {"accessToken":"access-missing","tokenType":"Bearer"}
                        """);
                }
            } else {
                StubServer.respond(exchange, 200, "application/json", "{}");
            }
        })) {
            SpringJwtAuth auth = new SpringJwtAuth(server.url(), Map.of("alice", user("alice")));
            auth.authenticate("alice");
            auth.authenticate("alice");
            auth.authenticate("alice");
            auth.authenticate("alice");
            assertEquals(4, loginRequests.get());
            assertEquals(2, zeroExpiryLogins.get());
            assertEquals(2, missingExpiryLogins.get());
        }
    }

    @Test
    void enforcedSpringAuthCaseFailsWhenLoginReturnsUnauthorized() throws Exception {
        AtomicBoolean requestWasUnauthenticated = new AtomicBoolean();
        try (StubServer server = new StubServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/api/v1/auth/login")) {
                StubServer.respond(exchange, 401, "application/problem+json", """
                    {"title":"Unauthorized","status":401}
                    """);
                return;
            }
            requestWasUnauthenticated.set(exchange.getRequestHeaders().getFirst("Authorization") == null);
            StubServer.respond(exchange, 401, "application/problem+json", """
                {"title":"Unauthorized","status":401}
                """);
        })) {
            ContractCase enforced = CaseLoader.load().stream()
                .filter(contractCase -> contractCase.id().equals("auth-login-spring-self-check"))
                .findFirst()
                .orElseThrow();
            ContractClient client = new ContractClient(server.url(), server.url(), Map.of("admin", user("admin")));
            ContractClient.RequestResult rails = client.send(enforced, true);
            ContractClient.RequestResult spring = client.send(enforced, false);
            List<String> notes = new ArrayList<>(rails.notes());
            notes.addAll(spring.notes());

            CaseResult result = new ContractDiffer().diff(enforced, rails.response(), spring.response(),
                new Allowlist(List.of()), JSON.createObjectNode(), rails.url(), spring.url(), notes,
                spring.authenticated())
                .failIfEnforcedAuthUnavailable();

            assertTrue(requestWasUnauthenticated.get());
            assertFalse(spring.authenticated());
            assertEquals(CaseResult.Outcome.ERROR, result.outcome());
            assertTrue(result.enforcedFailure());
        }
    }

    @Test
    void endToEndLoadsCasesSendsRequestsDiffsAndWritesReport(@TempDir Path directory) throws Exception {
        try (StubServer rails = new StubServer(exchange -> StubServer.respond(exchange, 200, "application/json",
            """
                {"accounts":[{"id":101,"name":"Public"}]}
                """));
             StubServer spring = new StubServer(exchange -> StubServer.respond(exchange, 200, "application/json",
                 """
                     {"accounts":[{"id":101,"name":"Spring"}]}
                     """))) {
            ContractCase contractCase = CaseLoader.parse(YAML.readTree("""
                - id: end-to-end
                  ticket: AB-266
                  status: pending
                  method: GET
                  path: /accounts
                  auth: anonymous
                """)).getFirst();
            ContractClient client = new ContractClient(rails.url(), spring.url(), Map.of());
            ContractClient.RequestResult railsResponse = client.send(contractCase, true);
            ContractClient.RequestResult springResponse = client.send(contractCase, false);
            Allowlist allowlist = new Allowlist(List.of(
                new AllowlistEntry("intentional-name", "*", "**", null, null, "pointer", "Expected", "AB-266",
                    JSON.readTree("""
                        {"pointer":"/accounts/0/name","rule":"ignore"}
                        """)),
                new AllowlistEntry("unused", "*", "**", null, null, "pointer", "", "", JSON.createObjectNode())
            ));
            CaseResult result = new ContractDiffer().diff(contractCase, railsResponse.response(),
                springResponse.response(), allowlist, JSON.createObjectNode(),
                railsResponse.url(), springResponse.url(), List.of());
            assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
            ReportWriter.write(directory, rails.url(), spring.url(), List.of(result), allowlist);
            JsonNode report = JSON.readTree(directory.resolve("report.json").toFile());
            assertEquals(1, report.path("summary").path("total").asInt());
            assertEquals("end-to-end", report.path("cases").get(0).path("id").asText());
            assertEquals(CaseResult.Outcome.CLEAN.name(), report.path("cases").get(0).path("outcome").asText());
            assertEquals(200, report.path("cases").get(0).path("rails").path("status").asInt());
            assertTrue(report.path("cases").get(0).path("rails").path("url").asText().endsWith("/accounts.json"));
            assertTrue(java.nio.file.Files.readString(directory.resolve("report.md"))
                .contains("| end-to-end | pending | 200 application/json |"));
            assertEquals(1, report.path("allowlist").get(0).path("hits").asInt());
            assertFalse(report.path("allowlist").get(0).path("stale").asBoolean());
            assertTrue(report.path("allowlist").get(1).path("stale").asBoolean());
            assertTrue(java.nio.file.Files.readString(directory.resolve("report.md"))
                .contains("applied in end-to-end: Expected (AB-266)"));
            assertTrue(CaseLoader.load().stream().anyMatch(item -> item.id().equals("accounts-index-anonymous")));
        }
    }

    private static FixtureUsers.FixtureUser user(String key) {
        return new FixtureUsers.FixtureUser(key, 2, key, key + "@contract.example", "contract-password", false,
            false);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        private MutableClock(Instant initial) {
            this(new AtomicReference<>(initial), ZoneOffset.UTC);
        }

        private MutableClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(current, zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
