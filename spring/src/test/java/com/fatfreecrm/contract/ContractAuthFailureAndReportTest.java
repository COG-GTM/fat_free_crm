package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Failure paths of the Rails session and Spring JWT authentication adapters, and the
 * report writer's handling of errors, notes and large diff lists.
 */
class ContractAuthFailureAndReportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String SIGN_IN_PAGE = "<meta name=\"csrf-token\" content=\"token-123\">";

    @Test
    void railsAdapterTreatsMissingOrBrokenSignInPageAsUnreachable() throws Exception {
        AtomicInteger status = new AtomicInteger(404);
        try (StubServer server = new StubServer(exchange ->
            StubServer.respond(exchange, status.get(), "text/html", "down"))) {
            RailsSessionAuth auth = new RailsSessionAuth(server.url() + "/", users());

            IOException notFound = assertThrows(IOException.class, () -> auth.authenticate("alice"));
            assertEquals("Rails sign-in page is unreachable (HTTP 404) at " + server.url() + "/users/sign_in",
                notFound.getMessage());

            status.set(503);
            IOException unavailable = assertThrows(IOException.class, () -> auth.authenticate("alice"));
            assertTrue(unavailable.getMessage().contains("(HTTP 503)"), unavailable.getMessage());

            assertThrows(IllegalArgumentException.class, () -> auth.authenticate("ghost"));
        }

        String closedUrl;
        try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 200, null, ""))) {
            closedUrl = server.url();
        }
        RailsSessionAuth closed = new RailsSessionAuth(closedUrl, users());
        IOException refused = assertThrows(IOException.class, () -> closed.authenticate("alice"));
        assertEquals("Rails sign-in page is unreachable at " + closedUrl + "/users/sign_in", refused.getMessage());
        assertNotNull(refused.getCause());
    }

    @Test
    void railsAdapterReportsUnavailableAuthInsteadOfFailingWhenLoginDoesNotSucceed() throws Exception {
        AtomicReference<String> page = new AtomicReference<>(SIGN_IN_PAGE);
        AtomicInteger loginStatus = new AtomicInteger(200);
        AtomicReference<String> loginLocation = new AtomicReference<>(null);
        AtomicReference<String> homePage = new AtomicReference<>(SIGN_IN_PAGE);
        AtomicInteger signInPageHits = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/users/sign_in") && exchange.getRequestMethod().equals("GET")) {
                signInPageHits.incrementAndGet();
                StubServer.respond(exchange, 200, "text/html", page.get());
            } else if (path.equals("/users/sign_in")) {
                if (loginLocation.get() != null) {
                    exchange.getResponseHeaders().set("Location", server(exchange) + loginLocation.get());
                }
                StubServer.respond(exchange, loginStatus.get(), "text/html", "");
            } else {
                StubServer.respond(exchange, 200, "text/html", homePage.get());
            }
        })) {
            RailsSessionAuth auth = new RailsSessionAuth(server.url(), users());
            HitCounter hits = new HitCounter(signInPageHits);

            page.set("<html><body>no token here</body></html>");
            AuthContext noToken = auth.authenticate("alice");
            assertUnavailable(noToken, "rails auth unavailable (CSRF token missing)");
            hits.assertFreshRequest();

            page.set(SIGN_IN_PAGE);
            loginStatus.set(200);
            AuthContext rerendered = auth.authenticate("alice");
            assertUnavailable(rerendered, "rails auth unavailable (login returned 200)");
            hits.assertFreshRequest();

            loginStatus.set(302);
            loginLocation.set("/users/sign_in");
            AuthContext bounced = auth.authenticate("alice");
            assertUnavailable(bounced, "rails auth unavailable (login returned 302)");
            hits.assertFreshRequest();

            loginLocation.set("/");
            homePage.set("<html>signed in but no csrf meta</html>");
            AuthContext noPostLoginToken = auth.authenticate("alice");
            assertUnavailable(noPostLoginToken, "rails auth unavailable (post-login CSRF token missing)");
            hits.assertFreshRequest();

            homePage.set(SIGN_IN_PAGE);
            AuthContext success = auth.authenticate("alice");
            assertNull(success.note());
            assertEquals("token-123", success.csrfToken());
            assertNull(success.authorization());
            hits.assertFreshRequest();
            page.set("<html><body>no token here</body></html>");
            assertSame(success, auth.authenticate("alice"), "successful sessions are cached per user");
            hits.assertNoRequest();
            page.set(SIGN_IN_PAGE);
        }
    }

    @Test
    void springAdapterHandlesServerErrorsMissingTokensAndCustomTokenTypes() throws Exception {
        AtomicInteger status = new AtomicInteger(500);
        AtomicReference<String> body = new AtomicReference<>("{}");
        AtomicInteger loginHits = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        try (StubServer server = new StubServer(exchange -> {
            loginHits.incrementAndGet();
            assertEquals("/api/v1/auth/login", exchange.getRequestURI().getPath());
            assertEquals("POST", exchange.getRequestMethod());
            try {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes()));
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            StubServer.respond(exchange, status.get(), "application/json", body.get());
        })) {
            SpringJwtAuth auth = new SpringJwtAuth(server.url() + "/", users());
            HitCounter hits = new HitCounter(loginHits);

            assertUnavailable(auth.authenticate("alice"), "spring auth unavailable (login returned 500)");
            assertEquals(JSON.readTree("{\"username\":\"alice\",\"password\":\"contract-password\"}"),
                JSON.readTree(requestBody.get()));
            hits.assertFreshRequest();

            status.set(401);
            assertUnavailable(auth.authenticate("alice"), "spring auth unavailable (login returned 401)");
            hits.assertFreshRequest();

            status.set(200);
            body.set("{\"tokenType\":\"Bearer\"}");
            assertUnavailable(auth.authenticate("alice"),
                "spring auth unavailable (login response omitted accessToken)");
            hits.assertFreshRequest();

            body.set("<html>not json</html>");
            assertThrows(IOException.class, () -> auth.authenticate("alice"));
            hits.assertFreshRequest();

            body.set("{\"accessToken\":\"no.expiry\",\"tokenType\":\"JWT\"}");
            AuthContext uncached = auth.authenticate("alice");
            assertEquals("JWT no.expiry", uncached.authorization());
            assertNull(uncached.note());
            hits.assertFreshRequest();
            assertNotSame(uncached, auth.authenticate("alice"),
                "a login response without expiresIn must not be cached");
            hits.assertFreshRequest();

            body.set("{\"accessToken\":\"abc.def\",\"tokenType\":\"JWT\",\"expiresIn\":3600}");
            AuthContext custom = auth.authenticate("alice");
            assertEquals("JWT abc.def", custom.authorization());
            assertNull(custom.csrfToken());
            assertNull(custom.note());
            hits.assertFreshRequest();
            status.set(500);
            assertSame(custom, auth.authenticate("alice"), "successful sessions are cached per user");
            hits.assertNoRequest();
            status.set(200);

            body.set("{\"accessToken\":\"xyz\",\"expiresIn\":3600}");
            AuthContext bob = auth.authenticate("bob");
            assertEquals("Bearer xyz", bob.authorization());
            assertNotEquals(custom, bob);
            hits.assertFreshRequest();

            assertThrows(IllegalArgumentException.class, () -> auth.authenticate("ghost"));
        }
    }

    @Test
    void reportWriterRecordsErrorsNotesMissingSidesAndTruncatesLongDiffLists(@TempDir Path directory)
        throws Exception {
        ContractCase enforced = CaseLoader.parse(YAML.readTree("""
            - id: enforced-error
              ticket: AB-999
              status: enforced
              method: GET
              path: /accounts
              auth: alice
            """)).getFirst();
        ContractCase pending = CaseLoader.parse(YAML.readTree("""
            - id: pending-diff
              ticket: AB-998
              path: /contacts
              auth: bob
            """)).getFirst();
        CapturedResponse rails = new CapturedResponse(200, "application/json", "{}", JSON.createObjectNode());
        List<Difference> differences = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            differences.add(new Difference("/items/" + index + "/name", Difference.Kind.VALUE,
                JSON.getNodeFactory().textNode("rails-" + index), JSON.getNodeFactory().textNode("spring-" + index),
                index < 5 ? List.of("intentional") : List.of()));
        }
        differences.add(new Difference("/dropped", Difference.Kind.MISSING_IN_SPRING,
            JSON.getNodeFactory().textNode("x".repeat(500)), null, List.of()));
        Allowlist allowlist = new Allowlist(List.of(
            new AllowlistEntry("intentional", "*", "**", null, null, "pointer", "Known drift", "AB-997",
                JSON.createObjectNode()),
            new AllowlistEntry("never-used", "*", "**", null, null, "status", "", "", JSON.createObjectNode())
        ));
        allowlist.hit(allowlist.entries().get(0));
        CaseResult error = new CaseResult(enforced, CaseResult.Outcome.ERROR, "http://rails/accounts.json", null,
            rails, null, List.of("spring auth unavailable (login returned 401)"), List.of(),
            "Enforced case authentication unavailable: spring auth unavailable (login returned 401)");
        CaseResult diff = new CaseResult(pending, CaseResult.Outcome.DIFF, "http://rails/contacts.json",
            "http://spring/api/v1/contacts", rails, rails, List.of(), differences, null);

        ReportWriter.write(directory, "http://rails", "http://spring", List.of(error, diff), allowlist);

        JsonNode report = JSON.readTree(directory.resolve("report.json").toFile());
        assertEquals("http://rails", report.path("railsUrl").asText());
        assertEquals("http://spring", report.path("springUrl").asText());
        JsonNode summary = report.path("summary");
        assertEquals(2, summary.path("total").asInt());
        assertEquals(0, summary.path("clean").asInt());
        assertEquals(1, summary.path("diff").asInt());
        assertEquals(1, summary.path("error").asInt());
        assertEquals(1, summary.path("enforcedFailures").asInt());

        JsonNode errorCase = report.path("cases").get(0);
        assertEquals("enforced-error", errorCase.path("id").asText());
        assertEquals("AB-999", errorCase.path("ticket").asText());
        assertEquals("ERROR", errorCase.path("outcome").asText());
        assertEquals("alice", errorCase.path("auth").asText());
        assertEquals("http://rails/accounts.json", errorCase.path("rails").path("url").asText());
        assertEquals(200, errorCase.path("rails").path("status").asInt());
        assertTrue(errorCase.path("spring").isEmpty(), "missing Spring side must be an empty object");
        assertTrue(errorCase.path("error").asText().startsWith("Enforced case authentication unavailable"));
        assertEquals("spring auth unavailable (login returned 401)", errorCase.path("notes").get(0).asText());
        assertTrue(errorCase.path("differences").isEmpty());

        JsonNode diffCase = report.path("cases").get(1);
        assertEquals(26, diffCase.path("differences").size());
        assertFalse(diffCase.has("error"));
        JsonNode first = diffCase.path("differences").get(0);
        assertEquals("/items/0/name", first.path("pointer").asText());
        assertEquals("VALUE", first.path("kind").asText());
        assertEquals("rails-0", first.path("rails").asText());
        assertEquals("spring-0", first.path("spring").asText());
        assertEquals("intentional", first.path("allowedBy").get(0).asText());
        JsonNode last = diffCase.path("differences").get(25);
        assertEquals("MISSING_IN_SPRING", last.path("kind").asText());
        assertTrue(last.path("spring").isNull());
        assertEquals(500, last.path("rails").asText().length(), "report.json keeps the full value");

        JsonNode allowlistReport = report.path("allowlist");
        assertEquals(1, allowlistReport.get(0).path("hits").asInt());
        assertFalse(allowlistReport.get(0).path("stale").asBoolean());
        assertEquals(0, allowlistReport.get(1).path("hits").asInt());
        assertTrue(allowlistReport.get(1).path("stale").asBoolean());

        String markdown = Files.readString(directory.resolve("report.md"));
        assertTrue(markdown.startsWith("Contract diff: **2 cases** — 0 clean, 1 diff, 1 error, 1 enforced failures."),
            markdown);
        assertTrue(markdown.contains(
            "| enforced-error | enforced | 200 application/json | — | ERROR | 0 / 0 | AB-999 |"), markdown);
        assertTrue(markdown.contains("| pending-diff | pending | 200 application/json | 200 application/json | DIFF"
            + " | 21 / 5 | AB-998 |"), markdown);
        assertTrue(markdown.contains("### enforced-error\n- Error: Enforced case authentication unavailable"),
            markdown);
        assertTrue(markdown.contains("- Note: spring auth unavailable (login returned 401)"), markdown);
        assertTrue(markdown.contains("`VALUE` `/items/0/name`: Rails `\"rails-0\"`, Spring `\"spring-0\"`"
            + " (allowed by intentional)"), markdown);
        assertTrue(markdown.contains("`/items/19/name`"), markdown);
        assertFalse(markdown.contains("`/items/20/name`"), "only the first 20 differences are listed");
        assertTrue(markdown.contains("- 6 more in report.json"), markdown);
        assertTrue(markdown.contains("- `intentional` (pointer): 1 hits — applied in pending-diff: Known drift"
            + " (AB-997)"), markdown);
        assertTrue(markdown.contains("- `never-used` (status): 0 hits — **stale**"), markdown);
    }

    /**
     * Tracks stub hits between adapter calls. Uncached calls must produce at least one new request
     * (the JDK {@code HttpClient} may legitimately resend over a stale pooled connection); cached
     * calls must produce none.
     */
    private static final class HitCounter {
        private final AtomicInteger hits;
        private int seen;

        HitCounter(AtomicInteger hits) {
            this.hits = hits;
            this.seen = hits.get();
        }

        void assertFreshRequest() {
            int current = hits.get();
            assertTrue(current > seen, "unavailable or failed results must not be cached");
            seen = current;
        }

        void assertNoRequest() {
            assertEquals(seen, hits.get(), "successful sessions are cached per user");
        }
    }

    private static void assertUnavailable(AuthContext context, String note) {
        assertNotNull(context.client());
        assertNull(context.authorization());
        assertNull(context.csrfToken());
        assertEquals(note, context.note());
    }

    private static String server(com.sun.net.httpserver.HttpExchange exchange) {
        return "http://" + exchange.getRequestHeaders().getFirst("Host");
    }

    private static Map<String, FixtureUsers.FixtureUser> users() {
        return Map.of(
            "alice", new FixtureUsers.FixtureUser("alice", 2, "alice", "alice@contract.example",
                "contract-password", false, false),
            "bob", new FixtureUsers.FixtureUser("bob", 3, "bob", "bob@contract.example",
                "contract-password", false, false)
        );
    }
}
