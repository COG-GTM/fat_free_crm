package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Edge-case and failure-path coverage for the AB-266 contract harness: case-file validation,
 * request construction, authentication adapter failures, allow-list loading and report rendering.
 */
class ContractHarnessEdgeCaseTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static ContractCase parseSingle(String yaml) throws Exception {
        return CaseLoader.parse(YAML.readTree(yaml)).getFirst();
    }

    private static FixtureUsers.FixtureUser user(String key) {
        return new FixtureUsers.FixtureUser(key, 3, key, key + "@contract.example", "contract-password", false,
            false);
    }

    @Nested
    class CaseLoaderValidation {
        @Test
        void rejectsFilesThatAreNotCaseLists() throws Exception {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CaseLoader.parse(YAML.readTree("cases: {id: not-a-list}")));
            assertTrue(error.getMessage().contains("list of cases"));
            assertThrows(IllegalArgumentException.class, () -> CaseLoader.parse(YAML.readTree("id: top-level")));
        }

        @Test
        void acceptsCasesWrappedInACasesKey() throws Exception {
            List<ContractCase> cases = CaseLoader.parse(YAML.readTree("""
                cases:
                  - id: wrapped
                    path: /leads
                """));
            assertEquals(1, cases.size());
            assertEquals("/leads.json", cases.getFirst().rails().path());
            assertEquals("/api/v1/leads", cases.getFirst().spring().path());
        }

        @Test
        void requiresANonBlankId() {
            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> parseSingle("- path: /accounts"));
            assertTrue(missing.getMessage().contains("id"));
            assertThrows(IllegalArgumentException.class, () -> parseSingle("""
                - id: "  "
                  path: /accounts
                """));
        }

        @Test
        void rejectsUnknownStatusesAndNormalisesCase() throws Exception {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parseSingle("""
                - id: bad-status
                  path: /accounts
                  status: skipped
                """));
            assertTrue(error.getMessage().contains("skipped"));
            ContractCase upper = parseSingle("""
                - id: upper
                  path: /accounts
                  status: ENFORCED
                  method: post
                """);
            assertEquals("enforced", upper.status());
            assertEquals("POST", upper.method());
        }

        @Test
        void appliesDefaultsForOptionalFields() throws Exception {
            ContractCase minimal = parseSingle("""
                - id: minimal
                  path: /accounts
                """);
            assertEquals("pending", minimal.status());
            assertEquals("GET", minimal.method());
            assertEquals("anonymous", minimal.auth());
            assertEquals("", minimal.ticket());
            assertEquals("", minimal.description());
            assertTrue(minimal.params().isObject());
            assertTrue(minimal.params().isEmpty());
            assertTrue(minimal.normalize().isObject());
            assertNull(minimal.body());
            assertNull(minimal.expect());
        }

        @Test
        void derivesLogicalPathFromRailsPathWhenPathIsOmitted() throws Exception {
            ContractCase explicit = parseSingle("""
                - id: explicit-sides
                  rails: /accounts/101.json
                  spring: /api/v1/accounts/101
                """);
            assertEquals("/accounts/101", explicit.path());
            assertEquals("/accounts/101.json", explicit.rails().path());
            assertEquals(ContractCase.Target.RAILS, explicit.rails().target());
            assertEquals("/api/v1/accounts/101", explicit.spring().path());
            assertEquals(ContractCase.Target.SPRING, explicit.spring().target());
        }

        @Test
        void rejectsUnknownSideTargets() {
            assertThrows(IllegalArgumentException.class, () -> parseSingle("""
                - id: bad-target
                  path: /accounts
                  rails:
                    target: django
                """));
        }

        @Test
        void validatesExpectationShape() {
            assertThrows(IllegalArgumentException.class, () -> parseSingle("""
                - id: expect-list
                  path: /accounts
                  expect: [200]
                """));
            assertThrows(IllegalArgumentException.class, () -> parseSingle("""
                - id: expect-status-text
                  path: /accounts
                  expect:
                    status: "200 OK"
                """));
            assertThrows(IllegalArgumentException.class, () -> parseSingle("""
                - id: expect-json-list
                  path: /accounts
                  expect:
                    json: ["/id"]
                """));
            IllegalArgumentException pointer = assertThrows(IllegalArgumentException.class, () -> parseSingle("""
                - id: expect-bad-pointer
                  path: /accounts
                  expect:
                    json:
                      "id": 1
                """));
            assertTrue(pointer.getMessage().contains("pointer"));
        }

        @Test
        void copiesValidExpectationsAndTreatsExplicitNullAsAbsent() throws Exception {
            ContractCase withExpectation = parseSingle("""
                - id: expect-ok
                  path: /accounts/101
                  expect:
                    status: 200
                    json:
                      "/id": 101
                      "/access": Public
                """);
            assertEquals(200, withExpectation.expect().path("status").asInt());
            assertEquals("Public", withExpectation.expect().path("json").path("/access").asText());
            ContractCase nullExpectation = parseSingle("""
                - id: expect-null
                  path: /accounts
                  expect: null
                """);
            assertNull(nullExpectation.expect());
        }

        @Test
        void everyShippedCaseUsesAKnownFixtureUserAndUniqueId() throws Exception {
            List<ContractCase> cases = CaseLoader.load();
            Map<String, FixtureUsers.FixtureUser> users = FixtureUsers.load();
            assertEquals(cases.size(), cases.stream().map(ContractCase::id).distinct().count());
            for (ContractCase contractCase : cases) {
                assertTrue(contractCase.auth().equals("anonymous") || users.containsKey(contractCase.auth()),
                    contractCase.id() + " references unknown fixture user " + contractCase.auth());
                assertTrue(contractCase.ticket().startsWith("AB-"), contractCase.id() + " has no ticket");
            }
            ContractCase denied = cases.stream()
                .filter(contractCase -> contractCase.id().equals("accounts-show-private-denied-bob"))
                .findFirst().orElseThrow();
            assertEquals("bob", denied.auth());
            assertEquals("/accounts/102", denied.path());
            ContractCase anonymous = cases.stream()
                .filter(contractCase -> contractCase.id().equals("accounts-index-anonymous"))
                .findFirst().orElseThrow();
            assertEquals("anonymous", anonymous.auth());
        }
    }

    @Nested
    class FixtureUserParity {
        @Test
        void matchesTheRailsContractFixtureUsers() throws Exception {
            Map<String, FixtureUsers.FixtureUser> users = FixtureUsers.load();
            assertEquals(List.of("admin", "alice", "bob", "sam", "carol"),
                users.values().stream().sorted((a, b) -> Integer.compare(a.id(), b.id()))
                    .map(FixtureUsers.FixtureUser::key).toList());
            for (FixtureUsers.FixtureUser user : users.values()) {
                assertEquals(user.key(), user.username());
                assertEquals(user.key() + "@contract.example", user.email());
                assertEquals("contract-password", user.password());
            }
            assertTrue(users.get("admin").admin());
            assertEquals(1, users.get("admin").id());
            assertTrue(users.get("sam").suspended());
            assertEquals(4, users.get("sam").id());
            assertEquals(1, users.values().stream().filter(FixtureUsers.FixtureUser::admin).count());
            assertEquals(1, users.values().stream().filter(FixtureUsers.FixtureUser::suspended).count());
        }
    }

    @Nested
    class CapturedResponses {
        @Test
        void parsesProblemJsonAndStripsCharsetParameters() throws Exception {
            try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 403,
                "application/problem+json; charset=UTF-8", """
                    {"title":"Forbidden","status":403}
                    """))) {
                CapturedResponse response = send(server);
                assertEquals(403, response.status());
                assertEquals("application/problem+json", response.mediaType());
                assertNotNull(response.json());
                assertEquals("Forbidden", response.json().path("title").asText());
            }
        }

        @Test
        void leavesJsonNullForMalformedBodiesBlankBodiesAndNonJsonMediaTypes() throws Exception {
            try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 200,
                "application/json", "{not json"))) {
                CapturedResponse response = send(server);
                assertNull(response.json());
                assertEquals("{not json", response.rawBody());
            }
            try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 200,
                "application/json", "   "))) {
                assertNull(send(server).json());
            }
            try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 401,
                "text/plain; charset=utf-8", "You are not authorized to take this action."))) {
                CapturedResponse response = send(server);
                assertEquals(401, response.status());
                assertEquals("text/plain", response.mediaType());
                assertNull(response.json());
                assertEquals("You are not authorized to take this action.", response.rawBody());
            }
            try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 204, null, ""))) {
                CapturedResponse response = send(server);
                assertEquals("", response.mediaType());
                assertNull(response.json());
            }
        }

        private CapturedResponse send(StubServer server) throws Exception {
            ContractCase contractCase = parseSingle("""
                - id: captured
                  path: /accounts
                """);
            return new ContractClient(server.url(), server.url(), Map.of()).send(contractCase, false).response();
        }
    }

    @Nested
    class RequestConstruction {
        @Test
        void encodesScalarAndArrayQueryParametersAndSendsJsonAccept() throws Exception {
            AtomicReference<String> query = new AtomicReference<>();
            AtomicReference<String> accept = new AtomicReference<>();
            AtomicReference<String> path = new AtomicReference<>();
            try (StubServer server = new StubServer(exchange -> {
                query.set(exchange.getRequestURI().getRawQuery());
                path.set(exchange.getRequestURI().getPath());
                accept.set(exchange.getRequestHeaders().getFirst("Accept"));
                StubServer.respond(exchange, 200, "application/json", "[]");
            })) {
                ContractCase contractCase = parseSingle("""
                    - id: query
                      path: /accounts
                      params:
                        q: "a b&c"
                        page: 2
                        ids: [101, 102]
                    """);
                ContractClient.RequestResult result = new ContractClient(server.url() + "/", server.url() + "/",
                    Map.of()).send(contractCase, true);
                assertEquals("/accounts.json", path.get());
                assertEquals("q=a+b%26c&page=2&ids=101&ids=102", query.get());
                assertEquals("a b&c", URLDecoder.decode(query.get().split("&")[0].substring(2),
                    StandardCharsets.UTF_8));
                assertEquals("application/json", accept.get());
                assertTrue(result.url().endsWith("/accounts.json?q=a+b%26c&page=2&ids=101&ids=102"));
                assertFalse(result.authenticated());
                assertTrue(result.notes().isEmpty());
            }
        }

        @Test
        void sendsJsonBodiesWithContentTypeAndNoBodyOtherwise() throws Exception {
            AtomicReference<String> method = new AtomicReference<>();
            AtomicReference<String> contentType = new AtomicReference<>();
            AtomicReference<String> body = new AtomicReference<>();
            try (StubServer server = new StubServer(exchange -> {
                method.set(exchange.getRequestMethod());
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                try {
                    body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
                StubServer.respond(exchange, 201, "application/json", "{\"id\":1}");
            })) {
                ContractClient client = new ContractClient(server.url(), server.url(), Map.of());
                client.send(parseSingle("""
                    - id: create
                      method: post
                      path: /accounts
                      body:
                        account:
                          name: Created
                    """), false);
                assertEquals("POST", method.get());
                assertEquals("application/json", contentType.get());
                assertEquals("{\"account\":{\"name\":\"Created\"}}", body.get());

                client.send(parseSingle("""
                    - id: delete
                      method: delete
                      path: /accounts/101
                    """), false);
                assertEquals("DELETE", method.get());
                assertNull(contentType.get());
                assertEquals("", body.get());
            }
        }

        @Test
        void anonymousCasesNeverTouchTheAuthAdapters() throws Exception {
            List<String> paths = new ArrayList<>();
            try (StubServer server = new StubServer(exchange -> {
                paths.add(exchange.getRequestURI().getPath());
                StubServer.respond(exchange, 200, "application/json", "[]");
            })) {
                ContractClient client = new ContractClient(server.url(), server.url(), Map.of("bob", user("bob")));
                ContractCase contractCase = parseSingle("""
                    - id: anonymous
                      path: /accounts
                      auth: anonymous
                    """);
                client.send(contractCase, true);
                client.send(contractCase, false);
                assertEquals(List.of("/accounts.json", "/api/v1/accounts"), paths);
            }
        }
    }

    @Nested
    class AuthenticationFailures {
        @Test
        void unknownFixtureUserIsRejectedBeforeAnyRequest() throws Exception {
            List<String> paths = new ArrayList<>();
            try (StubServer server = new StubServer(exchange -> {
                paths.add(exchange.getRequestURI().getPath());
                StubServer.respond(exchange, 200, "application/json", "[]");
            })) {
                ContractClient client = new ContractClient(server.url(), server.url(), Map.of());
                ContractCase contractCase = parseSingle("""
                    - id: unknown-user
                      path: /accounts
                      auth: mallory
                    """);
                IllegalArgumentException springError = assertThrows(IllegalArgumentException.class,
                    () -> client.send(contractCase, false));
                assertTrue(springError.getMessage().contains("mallory"));
                assertThrows(IllegalArgumentException.class, () -> client.send(contractCase, true));
                assertTrue(paths.isEmpty());
            }
        }

        @Test
        void railsSignInPageErrorsSurfaceAsDescriptiveIoExceptions() throws Exception {
            try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 404, "text/html",
                "missing"))) {
                ContractClient client = new ContractClient(server.url(), server.url(), Map.of("bob", user("bob")));
                IOException error = assertThrows(IOException.class, () -> client.send(parseSingle("""
                    - id: rails-404
                      path: /accounts
                      auth: bob
                    """), true));
                assertTrue(error.getMessage().contains("Authentication failed for fixture user bob"));
                assertTrue(error.getMessage().contains("RAILS"));
                assertTrue(error.getMessage().contains("/users/sign_in"));
            }
            try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 500, "text/html",
                "boom"))) {
                RailsSessionAuth auth = new RailsSessionAuth(server.url(), Map.of("bob", user("bob")));
                IOException error = assertThrows(IOException.class, () -> auth.authenticate("bob"));
                assertTrue(error.getMessage().contains("HTTP 500"));
            }
        }

        @Test
        void railsMissingCsrfTokenIsReportedAsUnavailableNotFatal() throws Exception {
            try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 200, "text/html",
                "<html><body>no token here</body></html>"))) {
                RailsSessionAuth auth = new RailsSessionAuth(server.url(), Map.of("bob", user("bob")));
                AuthContext context = auth.authenticate("bob");
                assertNull(context.authorization());
                assertNull(context.csrfToken());
                assertEquals("rails auth unavailable (CSRF token missing)", context.note());
            }
        }

        @Test
        void railsLoginRedirectBackToSignInMeansBadCredentialsOrSuspendedUser() throws Exception {
            List<String> requests = new ArrayList<>();
            try (StubServer server = new StubServer(exchange -> {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                if (exchange.getRequestMethod().equals("GET")) {
                    StubServer.respond(exchange, 200, "text/html",
                        "<meta name=\"csrf-token\" content=\"tok\" />");
                    return;
                }
                exchange.getResponseHeaders().set("Location", server(exchange) + "/users/sign_in");
                StubServer.respond(exchange, 302, "text/html", "");
            })) {
                ContractClient client = new ContractClient(server.url(), server.url(), Map.of("sam", user("sam")));
                ContractClient.RequestResult result = client.send(parseSingle("""
                    - id: suspended
                      path: /accounts
                      auth: sam
                    """), true);
                assertFalse(result.authenticated());
                assertEquals(List.of("rails auth unavailable (login returned 302)"), result.notes());
                assertEquals(List.of("GET /users/sign_in", "POST /users/sign_in", "GET /accounts.json"), requests);
            }
            try (StubServer server = new StubServer(exchange -> {
                if (exchange.getRequestMethod().equals("GET")) {
                    StubServer.respond(exchange, 200, "text/html",
                        "<input name=\"authenticity_token\" value=\"tok\" />");
                    return;
                }
                StubServer.respond(exchange, 200, "text/html", "<form>Invalid email or password</form>");
            })) {
                AuthContext context = new RailsSessionAuth(server.url(), Map.of("bob", user("bob")))
                    .authenticate("bob");
                assertEquals("rails auth unavailable (login returned 200)", context.note());
            }
        }

        @Test
        void railsPostLoginPageWithoutTokenIsUnavailableAndNotCached() throws Exception {
            List<String> requests = new ArrayList<>();
            try (StubServer server = new StubServer(exchange -> {
                String path = exchange.getRequestURI().getPath();
                requests.add(exchange.getRequestMethod() + " " + path);
                if (path.equals("/users/sign_in") && exchange.getRequestMethod().equals("GET")) {
                    StubServer.respond(exchange, 200, "text/html",
                        "<meta name='csrf-token' content='tok'>");
                } else if (path.equals("/users/sign_in")) {
                    exchange.getResponseHeaders().set("Location", server(exchange) + "/");
                    StubServer.respond(exchange, 302, "text/html", "");
                } else {
                    StubServer.respond(exchange, 200, "text/html", "<html>home without token</html>");
                }
            })) {
                RailsSessionAuth auth = new RailsSessionAuth(server.url(), Map.of("bob", user("bob")));
                assertEquals("rails auth unavailable (post-login CSRF token missing)",
                    auth.authenticate("bob").note());
                assertEquals("rails auth unavailable (post-login CSRF token missing)",
                    auth.authenticate("bob").note());
                assertEquals(6, requests.size(), "unavailable sessions must not be cached");
            }
        }

        @Test
        void springLoginServerErrorsAndMissingTokensAreUnavailable() throws Exception {
            try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 503,
                "application/problem+json", "{\"status\":503}"))) {
                AuthContext context = new SpringJwtAuth(server.url(), Map.of("bob", user("bob")))
                    .authenticate("bob");
                assertNull(context.authorization());
                assertEquals("spring auth unavailable (login returned 503)", context.note());
            }
            try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 200,
                "application/json", "{\"tokenType\":\"Bearer\",\"expiresIn\":3600}"))) {
                AuthContext context = new SpringJwtAuth(server.url(), Map.of("bob", user("bob")))
                    .authenticate("bob");
                assertNull(context.authorization());
                assertEquals("spring auth unavailable (login response omitted accessToken)", context.note());
            }
            try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 200,
                "application/json", "{\"accessToken\":\"abc\"}"))) {
                AuthContext context = new SpringJwtAuth(server.url(), Map.of("bob", user("bob")))
                    .authenticate("bob");
                assertEquals("Bearer abc", context.authorization());
            }
        }

        @Test
        void springLoginPostsUsernameAndPasswordAsJson() throws Exception {
            AtomicReference<String> body = new AtomicReference<>();
            AtomicReference<String> path = new AtomicReference<>();
            try (StubServer server = new StubServer(exchange -> {
                path.set(exchange.getRequestURI().getPath());
                try {
                    body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
                StubServer.respond(exchange, 200, "application/json",
                    "{\"accessToken\":\"jwt\",\"tokenType\":\"Token\",\"expiresIn\":60}");
            })) {
                AuthContext context = new SpringJwtAuth(server.url(), Map.of("bob", user("bob")))
                    .authenticate("bob");
                assertEquals("/api/v1/auth/login", path.get());
                JsonNode payload = JSON.readTree(body.get());
                assertEquals("bob", payload.path("username").asText());
                assertEquals("contract-password", payload.path("password").asText());
                assertEquals(2, payload.size());
                assertEquals("Token jwt", context.authorization());
            }
        }

        private String server(com.sun.net.httpserver.HttpExchange exchange) {
            return "http://" + exchange.getRequestHeaders().getFirst("Host");
        }
    }

    @Nested
    class AllowlistLoading {
        @Test
        void loadsTheShippedAllowlistWithMatchDefaults() throws Exception {
            Allowlist allowlist = Allowlist.load();
            List<String> ids = allowlist.entries().stream().map(AllowlistEntry::id).toList();
            assertEquals(List.of("authz-denied-401-vs-403", "error-body-problem-json"), ids);
            AllowlistEntry authz = allowlist.entries().get(0);
            assertEquals("status", authz.kind());
            assertEquals(Boolean.TRUE, authz.authenticated());
            assertEquals(401, authz.definition().path("status").path("rails").asInt());
            assertEquals(403, authz.definition().path("status").path("spring").asInt());
            assertFalse(authz.reason().isBlank());
            assertFalse(authz.reference().isBlank());
            AllowlistEntry problem = allowlist.entries().get(1);
            assertEquals("errorBody", problem.kind());
            assertEquals("*", problem.method());
            assertEquals("**", problem.path());
            assertNull(problem.caseId());
            assertNull(problem.authenticated());
            allowlist.entries().forEach(entry -> assertEquals(0, allowlist.hits(entry.id())));
        }

        @Test
        void authenticatedEntriesDoNotMatchAnonymousRequests() throws Exception {
            Allowlist allowlist = Allowlist.load();
            ContractCase denied = parseSingle("""
                - id: private-denied
                  path: /accounts/102
                  auth: bob
                """);
            assertTrue(allowlist.matching(denied, true).stream()
                .anyMatch(entry -> entry.id().equals("authz-denied-401-vs-403")));
            assertFalse(allowlist.matching(denied, false).stream()
                .anyMatch(entry -> entry.id().equals("authz-denied-401-vs-403")));
            assertFalse(allowlist.matching(denied).stream()
                .anyMatch(entry -> entry.id().equals("authz-denied-401-vs-403")));
        }

        @Test
        void globMatchingHandlesSingleSegmentWildcardsEmptyPathsAndCaseIds() throws Exception {
            Allowlist allowlist = new Allowlist(List.of(
                new AllowlistEntry("single", "*", "/accounts/*", null, null, "status", "", "", JSON.createObjectNode()),
                new AllowlistEntry("exact-method", "DELETE", "/accounts/*", null, null, "status", "", "",
                    JSON.createObjectNode()),
                new AllowlistEntry("by-case", "*", "**", "only-this-case", null, "status", "", "",
                    JSON.createObjectNode()),
                new AllowlistEntry("root", "*", "", null, null, "status", "", "", JSON.createObjectNode())
            ));
            assertEquals(List.of("single"), ids(allowlist.matching(parseSingle("""
                - id: show
                  path: /accounts/101
                """))));
            assertEquals(List.of(), ids(allowlist.matching(parseSingle("""
                - id: nested
                  path: /accounts/101/contacts
                """))));
            assertEquals(List.of(), ids(allowlist.matching(parseSingle("""
                - id: index
                  path: /accounts
                """))));
            assertEquals(List.of("single", "exact-method"), ids(allowlist.matching(parseSingle("""
                - id: destroy
                  method: delete
                  path: /accounts/101
                """))));
            assertEquals(List.of("by-case"), ids(allowlist.matching(parseSingle("""
                - id: only-this-case
                  path: /leads
                """))));
            assertEquals(List.of("root"), ids(allowlist.matching(parseSingle("""
                - id: root
                  path: /
                """))));
            AllowlistEntry unknown = new AllowlistEntry("unknown", "*", "**", null, null, "status", "", "",
                JSON.createObjectNode());
            allowlist.hit(unknown);
            assertEquals(0, allowlist.hits("unknown"));
        }

        private List<String> ids(List<AllowlistEntry> entries) {
            return entries.stream().map(AllowlistEntry::id).toList();
        }
    }

    @Nested
    class Reporting {
        @Test
        void rendersErrorsExpectationsMissingSidesAndTruncatesLongDiffLists(@TempDir Path directory)
            throws Exception {
            ContractCase errored = parseSingle("""
                - id: errored
                  ticket: AB-266
                  status: enforced
                  path: /accounts
                  auth: bob
                """);
            CaseResult error = new CaseResult(errored, CaseResult.Outcome.ERROR, "http://rails/accounts.json", null,
                null, null, List.of("rails auth unavailable (login returned 302)"), List.of(),
                "Enforced case authentication unavailable");
            List<Difference> many = new ArrayList<>();
            for (int index = 0; index < 25; index++) {
                many.add(new Difference("/accounts/" + index + "/name", Difference.Kind.VALUE,
                    JSON.getNodeFactory().textNode("rails-" + index), JSON.getNodeFactory().textNode("spring-" + index),
                    List.of()));
            }
            many.add(Difference.expectation("spring", "/id", JSON.getNodeFactory().numberNode(7),
                JSON.getNodeFactory().numberNode(1)));
            many.add(new Difference("/extra", Difference.Kind.EXTRA_IN_SPRING, JSON.getNodeFactory().missingNode(),
                JSON.getNodeFactory().textNode("x".repeat(400)), List.of("allowed-extra")));
            ContractCase diffed = parseSingle("""
                - id: diffed
                  ticket: AB-266
                  path: /accounts
                """);
            CaseResult diff = new CaseResult(diffed, CaseResult.Outcome.DIFF, "http://rails/accounts.json",
                "http://spring/api/v1/accounts", new CapturedResponse(200, "application/json", "[]", null),
                new CapturedResponse(200, "application/json", "[]", null), List.of(), many, null);
            Allowlist allowlist = new Allowlist(List.of(new AllowlistEntry("allowed-extra", "*", "**", null, null,
                "pointer", "", "", JSON.createObjectNode())));
            allowlist.hit(allowlist.entries().getFirst());

            ReportWriter.write(directory, "http://rails", "http://spring", List.of(error, diff), allowlist);

            JsonNode report = JSON.readTree(directory.resolve("report.json").toFile());
            assertEquals(2, report.path("summary").path("total").asInt());
            assertEquals(0, report.path("summary").path("clean").asInt());
            assertEquals(1, report.path("summary").path("diff").asInt());
            assertEquals(1, report.path("summary").path("error").asInt());
            assertEquals(1, report.path("summary").path("enforcedFailures").asInt());
            JsonNode errorCase = report.path("cases").get(0);
            assertEquals("Enforced case authentication unavailable", errorCase.path("error").asText());
            assertEquals("bob", errorCase.path("auth").asText());
            assertTrue(errorCase.path("rails").has("url"));
            assertFalse(errorCase.path("rails").has("status"));
            assertTrue(errorCase.path("spring").isEmpty());
            assertEquals("rails auth unavailable (login returned 302)", errorCase.path("notes").get(0).asText());
            JsonNode diffCase = report.path("cases").get(1);
            assertFalse(diffCase.has("error"));
            assertEquals(27, diffCase.path("differences").size());
            JsonNode expectation = diffCase.path("differences").get(25);
            assertEquals("spring", expectation.path("side").asText());
            assertEquals(7, expectation.path("actual").asInt());
            assertEquals(1, expectation.path("expected").asInt());
            JsonNode extra = diffCase.path("differences").get(26);
            assertEquals("<missing>", extra.path("rails").asText());
            assertEquals("allowed-extra", extra.path("allowedBy").get(0).asText());
            assertEquals("allowed-extra", report.path("allowlist").get(0).path("id").asText());
            assertFalse(report.path("allowlist").get(0).path("stale").asBoolean());

            String markdown = Files.readString(directory.resolve("report.md"));
            assertTrue(markdown.startsWith(
                "Contract diff: **2 cases** — 0 clean, 1 diff, 1 error, 1 enforced failures."));
            assertTrue(markdown.contains("| errored | enforced | — | — | ERROR | 0 / 0 | AB-266 |"));
            assertTrue(markdown.contains(
                "| diffed | pending | 200 application/json | 200 application/json | DIFF | 26 / 1 | AB-266 |"));
            assertTrue(markdown.contains("- Error: Enforced case authentication unavailable"));
            assertTrue(markdown.contains("- Note: rails auth unavailable (login returned 302)"));
            assertTrue(markdown.contains("- 7 more in report.json"));
            assertFalse(markdown.contains("/accounts/20/name"));
            assertTrue(markdown.contains("`VALUE` `/accounts/0/name`: Rails `\"rails-0\"`, Spring `\"spring-0\"`"));
            assertTrue(markdown.contains("`allowed-extra` (pointer): 1 hits — applied in diffed"));
        }

        @Test
        void rendersExpectationDifferencesAndTruncatesLongValuesInMarkdown(@TempDir Path directory)
            throws Exception {
            ContractCase contractCase = parseSingle("""
                - id: expectation-only
                  path: /users/me
                """);
            String longValue = "v".repeat(300);
            CaseResult result = new CaseResult(contractCase, CaseResult.Outcome.DIFF, "http://rails/users/me.json",
                "http://spring/api/v1/users/me", new CapturedResponse(200, "application/json", "{}", null),
                new CapturedResponse(200, "application/json", "{}", null), List.of(), List.of(
                    Difference.expectation("rails", "/username", JSON.getNodeFactory().textNode(longValue),
                        JSON.getNodeFactory().textNode("admin"))), null);

            ReportWriter.write(directory, "http://rails", "http://spring", List.of(result),
                new Allowlist(List.of()));

            String markdown = Files.readString(directory.resolve("report.md"));
            assertTrue(markdown.contains("`EXPECTATION` on rails `/username`: actual `"));
            assertTrue(markdown.contains("...`, expected `\"admin\"`"));
            assertFalse(markdown.contains(longValue));
            assertTrue(markdown.endsWith("### Allow-list\n"));
            JsonNode report = JSON.readTree(directory.resolve("report.json").toFile());
            assertTrue(report.path("allowlist").isEmpty());
            assertEquals(longValue, report.path("cases").get(0).path("differences").get(0).path("actual").asText());
        }
    }
}
