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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Edge cases for the contract-diff harness building blocks: case parsing, the shipped
 * allow-list and fixture-user resources, response capture, request assembly and
 * enforced-case failure semantics.
 */
class ContractHarnessEdgeCaseTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void caseLoaderNormalisesDefaultsAndShorthandSides() throws Exception {
        ContractCase parsed = CaseLoader.parse(YAML.readTree("""
            cases:
              - id: shorthand
                method: post
                status: ENFORCED
                path: /leads
                rails: /custom/leads.json
                spring: /custom/leads
            """)).getFirst();
        assertEquals("POST", parsed.method());
        assertEquals("enforced", parsed.status());
        assertEquals("/leads", parsed.path());
        assertEquals("/custom/leads.json", parsed.rails().path());
        assertEquals(ContractCase.Target.RAILS, parsed.rails().target());
        assertEquals("/custom/leads", parsed.spring().path());
        assertEquals(ContractCase.Target.SPRING, parsed.spring().target());
        assertEquals("anonymous", parsed.auth());
        assertEquals("", parsed.ticket());
        assertEquals("", parsed.description());
        assertTrue(parsed.params().isObject() && parsed.params().isEmpty());
        assertTrue(parsed.normalize().isObject() && parsed.normalize().isEmpty());
        assertNull(parsed.body());
    }

    @Test
    void caseLoaderDerivesLogicalPathFromRailsPathWhenPathIsOmitted() throws Exception {
        ContractCase parsed = CaseLoader.parse(YAML.readTree("""
            - id: no-path
              rails:
                path: /accounts/101.json
              spring:
                path: /api/v1/accounts/101
            """)).getFirst();
        assertEquals("/accounts/101", parsed.path());
        assertEquals("GET", parsed.method());
        assertEquals("pending", parsed.status());
    }

    @Test
    void caseLoaderRejectsMalformedCases() throws Exception {
        IllegalArgumentException missingId = assertThrows(IllegalArgumentException.class,
            () -> CaseLoader.parse(YAML.readTree("""
                - path: /accounts
                """)));
        assertTrue(missingId.getMessage().contains("id"), missingId.getMessage());

        IllegalArgumentException badStatus = assertThrows(IllegalArgumentException.class,
            () -> CaseLoader.parse(YAML.readTree("""
                - id: bad-status
                  path: /accounts
                  status: skipped
                """)));
        assertTrue(badStatus.getMessage().contains("pending or enforced"), badStatus.getMessage());

        IllegalArgumentException badTarget = assertThrows(IllegalArgumentException.class,
            () -> CaseLoader.parse(YAML.readTree("""
                - id: bad-target
                  path: /accounts
                  spring:
                    target: flask
                """)));
        assertNotNull(badTarget.getMessage());

        IllegalArgumentException notAList = assertThrows(IllegalArgumentException.class,
            () -> CaseLoader.parse(YAML.readTree("""
                id: lonely
                path: /accounts
                """)));
        assertTrue(notAList.getMessage().contains("list of cases"), notAList.getMessage());

        IllegalArgumentException expectNotObject = assertThrows(IllegalArgumentException.class,
            () -> CaseLoader.parse(YAML.readTree("""
                - id: bad-expect
                  path: /accounts.json
                  expect: 200
                """)));
        assertTrue(expectNotObject.getMessage().contains("expect must be an object"), expectNotObject.getMessage());

        IllegalArgumentException expectStatusNotInt = assertThrows(IllegalArgumentException.class,
            () -> CaseLoader.parse(YAML.readTree("""
                - id: bad-expect-status
                  path: /accounts.json
                  expect: {status: "200"}
                """)));
        assertTrue(expectStatusNotInt.getMessage().contains("status must be an integer"),
            expectStatusNotInt.getMessage());

        IllegalArgumentException expectBadPointer = assertThrows(IllegalArgumentException.class,
            () -> CaseLoader.parse(YAML.readTree("""
                - id: bad-expect-pointer
                  path: /accounts.json
                  expect: {json: {"id": 1}}
                """)));
        assertTrue(expectBadPointer.getMessage().contains("Invalid expectation JSON pointer: id"),
            expectBadPointer.getMessage());
    }

    @Test
    void shippedCasesOnlyReferenceKnownFixtureUsersAndUniqueIds() throws Exception {
        List<ContractCase> cases = CaseLoader.load();
        Map<String, FixtureUsers.FixtureUser> users = FixtureUsers.load();
        assertEquals(cases.size(), cases.stream().map(ContractCase::id).distinct().count());
        for (ContractCase contractCase : cases) {
            assertTrue(contractCase.auth().equals("anonymous") || users.containsKey(contractCase.auth()),
                contractCase.id() + " references unknown fixture user " + contractCase.auth());
            assertFalse(contractCase.ticket().isBlank(), contractCase.id() + " has no ticket");
        }
        List<ContractCase> enforced = cases.stream().filter(item -> item.status().equals("enforced")).toList();
        assertEquals(List.of("accounts-index-self-check-admin", "auth-login-spring-self-check",
            "accounts-index-anonymous"), enforced.stream().map(ContractCase::id).toList());
    }

    @Test
    void fixtureUsersMirrorTheRailsContractFixtureCorpus() throws Exception {
        Map<String, FixtureUsers.FixtureUser> users = FixtureUsers.load();
        assertEquals(List.of("admin", "alice", "bob", "sam", "carol"), List.copyOf(users.keySet()).stream()
            .sorted((left, right) -> Integer.compare(users.get(left).id(), users.get(right).id())).toList());
        users.forEach((key, user) -> {
            assertEquals(key, user.key());
            assertEquals(key, user.username());
            assertEquals(key + "@contract.example", user.email());
            assertEquals("contract-password", user.password());
        });
        assertEquals(1, users.get("admin").id());
        assertEquals(2, users.get("alice").id());
        assertEquals(3, users.get("bob").id());
        assertEquals(4, users.get("sam").id());
        assertEquals(5, users.get("carol").id());
        assertEquals(List.of("admin"), users.values().stream().filter(FixtureUsers.FixtureUser::admin)
            .map(FixtureUsers.FixtureUser::key).toList());
        assertEquals(List.of("sam"), users.values().stream().filter(FixtureUsers.FixtureUser::suspended)
            .map(FixtureUsers.FixtureUser::key).toList());
    }

    @Test
    void shippedAllowlistEncodesTheDocumentedRailsVersusSpringErrorSemantics() throws Exception {
        Allowlist allowlist = Allowlist.load();
        assertEquals(List.of("authz-denied-401-vs-403", "error-body-problem-json"),
            allowlist.entries().stream().map(AllowlistEntry::id).toList());

        AllowlistEntry authz = allowlist.entries().get(0);
        assertEquals("status", authz.kind());
        assertEquals("*", authz.method());
        assertEquals("**", authz.path());
        assertNull(authz.caseId());
        assertEquals(Boolean.TRUE, authz.authenticated());
        assertEquals(401, authz.definition().path("status").path("rails").asInt());
        assertEquals(403, authz.definition().path("status").path("spring").asInt());
        assertFalse(authz.reason().isBlank());
        assertFalse(authz.reference().isBlank());

        AllowlistEntry errorBody = allowlist.entries().get(1);
        assertEquals("errorBody", errorBody.kind());
        assertNull(errorBody.authenticated());

        ContractCase denied = CaseLoader.parse(YAML.readTree("""
            - id: denied
              method: GET
              path: /accounts/102
              auth: bob
            """)).getFirst();
        assertEquals(List.of("error-body-problem-json"),
            allowlist.matching(denied).stream().map(AllowlistEntry::id).toList());
        assertEquals(List.of("authz-denied-401-vs-403", "error-body-problem-json"),
            allowlist.matching(denied, true).stream().map(AllowlistEntry::id).toList());

        assertEquals(0, allowlist.hits("authz-denied-401-vs-403"));
        allowlist.hit(authz);
        allowlist.hit(authz);
        assertEquals(2, allowlist.hits("authz-denied-401-vs-403"));
        allowlist.hit(new AllowlistEntry("unknown", "*", "**", null, null, "status", "", "",
            JSON.createObjectNode()));
        assertEquals(0, allowlist.hits("unknown"));
    }

    @Test
    void allowlistMatchingIsCaseInsensitiveOnMethodAndExactOnCaseId() throws Exception {
        Allowlist allowlist = new Allowlist(List.of(
            new AllowlistEntry("post-only", "post", "**", null, null, "status", "", "", JSON.createObjectNode()),
            new AllowlistEntry("one-case", "*", "**", "target", null, "status", "", "", JSON.createObjectNode())
        ));
        ContractCase post = CaseLoader.parse(YAML.readTree("""
            - id: target
              method: POST
              path: /accounts
            """)).getFirst();
        ContractCase get = CaseLoader.parse(YAML.readTree("""
            - id: other
              method: GET
              path: /accounts
            """)).getFirst();
        assertEquals(List.of("post-only", "one-case"),
            allowlist.matching(post).stream().map(AllowlistEntry::id).toList());
        assertTrue(allowlist.matching(get).isEmpty());
    }

    @Test
    void capturedResponseParsesJsonOnlyForJsonMediaTypes() throws Exception {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (StubServer server = new StubServer(exchange ->
            StubServer.respond(exchange, 200, contentType.get(), body.get()))) {
            HttpClient client = HttpClient.newHttpClient();

            CapturedResponse charset = capture(client, server, contentType, body,
                "Application/JSON; charset=utf-8", "{\"id\":1}");
            assertEquals("application/json", charset.mediaType());
            assertEquals(1, charset.json().path("id").asInt());
            assertEquals("{\"id\":1}", charset.rawBody());

            CapturedResponse problem = capture(client, server, contentType, body,
                "application/problem+json", "{\"status\":403}");
            assertEquals("application/problem+json", problem.mediaType());
            assertEquals(403, problem.json().path("status").asInt());

            CapturedResponse html = capture(client, server, contentType, body, "text/html", "{\"id\":1}");
            assertEquals("text/html", html.mediaType());
            assertNull(html.json());

            CapturedResponse malformed = capture(client, server, contentType, body,
                "application/json", "You are not authorized to take this action.");
            assertNull(malformed.json());
            assertEquals("You are not authorized to take this action.", malformed.rawBody());

            CapturedResponse blank = capture(client, server, contentType, body, "application/json", "");
            assertNull(blank.json());
            assertEquals("", blank.rawBody());

            CapturedResponse untyped = capture(client, server, contentType, body, null, "{}");
            assertEquals("", untyped.mediaType());
            assertNull(untyped.json());
        }
    }

    @Test
    void contractClientEncodesQueryParamsBodyAndHeadersForAnonymousRequests() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> csrf = new AtomicReference<>();
        AtomicReference<String> requestContentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        try (StubServer server = new StubServer(exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            method.set(exchange.getRequestMethod());
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            csrf.set(exchange.getRequestHeaders().getFirst("X-CSRF-Token"));
            requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            try {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            StubServer.respond(exchange, 200, "application/json", "{}");
        })) {
            ContractClient client = new ContractClient(server.url() + "/", server.url() + "/", Map.of());

            ContractCase query = CaseLoader.parse(YAML.readTree("""
                - id: query
                  path: /accounts
                  params:
                    q: a b&c
                    ids: [1, 2]
                    page: 3
                """)).getFirst();
            ContractClient.RequestResult result = client.send(query, true);
            assertEquals(server.url() + "/accounts.json?q=a+b%26c&ids=1&ids=2&page=3", result.url());
            assertEquals("q=a+b%26c&ids=1&ids=2&page=3", rawQuery.get());
            assertEquals("GET", method.get());
            assertEquals("application/json", accept.get());
            assertNull(authorization.get());
            assertNull(csrf.get());
            assertNull(requestContentType.get());
            assertEquals("", requestBody.get());
            assertFalse(result.authenticated());
            assertTrue(result.notes().isEmpty());

            ContractCase write = CaseLoader.parse(YAML.readTree("""
                - id: write
                  method: PUT
                  path: /accounts/101
                  body:
                    account:
                      name: renamed
                """)).getFirst();
            ContractClient.RequestResult springResult = client.send(write, false);
            assertEquals(server.url() + "/api/v1/accounts/101", springResult.url());
            assertEquals("PUT", method.get());
            assertEquals("application/json", requestContentType.get());
            assertEquals("{\"account\":{\"name\":\"renamed\"}}", requestBody.get());
            assertNull(csrf.get());
        }
    }

    @Test
    void contractClientSurfacesUnknownUsersUnreachableServersAndFailedRailsLogins() throws Exception {
        ContractCase authenticated = CaseLoader.parse(YAML.readTree("""
            - id: authenticated
              path: /accounts
              auth: alice
            """)).getFirst();
        ContractCase anonymous = CaseLoader.parse(YAML.readTree("""
            - id: anonymous
              path: /accounts
            """)).getFirst();

        String closedUrl;
        try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 200, null, ""))) {
            closedUrl = server.url();
        }
        ContractClient closed = new ContractClient(closedUrl, closedUrl, Map.of());
        IOException unreachable = assertThrows(IOException.class, () -> closed.send(anonymous, true));
        assertTrue(unreachable.getMessage().startsWith("Request failed: GET " + closedUrl + "/accounts.json"),
            unreachable.getMessage());

        try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 404, "text/html", ""))) {
            ContractClient missingUser = new ContractClient(server.url(), server.url(), Map.of());
            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> missingUser.send(authenticated, true));
            assertEquals("Unknown contract fixture user: alice", unknown.getMessage());

            ContractClient client = new ContractClient(server.url(), server.url(), Map.of("alice",
                new FixtureUsers.FixtureUser("alice", 2, "alice", "alice@contract.example", "contract-password",
                    false, false)));
            IOException railsDown = assertThrows(IOException.class, () -> client.send(authenticated, true));
            assertTrue(railsDown.getMessage().startsWith("Authentication failed for fixture user alice via RAILS for "
                + server.url() + "/accounts.json: IOException: Rails sign-in page is unreachable (HTTP 404)"),
                railsDown.getMessage());

            ContractClient.RequestResult springSide = client.send(authenticated, false);
            assertFalse(springSide.authenticated());
            assertEquals(List.of("spring auth unavailable (login returned 404)"), springSide.notes());
            assertEquals(404, springSide.response().status());
        }
    }

    @Test
    void caseResultOnlyFailsEnforcedCasesAndOnlyWhenAuthWasUnavailable() throws Exception {
        ContractCase pending = CaseLoader.parse(YAML.readTree("""
            - id: pending-case
              path: /accounts
              auth: alice
            """)).getFirst();
        ContractCase enforced = CaseLoader.parse(YAML.readTree("""
            - id: enforced-case
              path: /accounts
              auth: alice
              status: enforced
            """)).getFirst();
        CapturedResponse ok = new CapturedResponse(200, "application/json", "{}", JSON.createObjectNode());
        List<String> unavailable = List.of("spring auth unavailable (login returned 401)");

        CaseResult pendingDiff = new CaseResult(pending, CaseResult.Outcome.DIFF, null, null, ok, ok,
            unavailable, List.of(), null);
        assertFalse(pendingDiff.enforcedFailure());
        assertEquals(pendingDiff, pendingDiff.failIfEnforcedAuthUnavailable());

        CaseResult enforcedClean = new CaseResult(enforced, CaseResult.Outcome.CLEAN, null, null, ok, ok,
            List.of("rails note"), List.of(), null);
        assertFalse(enforcedClean.enforcedFailure());
        assertEquals(enforcedClean, enforcedClean.failIfEnforcedAuthUnavailable());

        CaseResult enforcedDiff = new CaseResult(enforced, CaseResult.Outcome.DIFF, null, null, ok, ok,
            List.of(), List.of(), null);
        assertTrue(enforcedDiff.enforcedFailure());

        CaseResult enforcedUnavailable = new CaseResult(enforced, CaseResult.Outcome.CLEAN, null, null, ok, ok,
            unavailable, List.of(), null).failIfEnforcedAuthUnavailable();
        assertEquals(CaseResult.Outcome.ERROR, enforcedUnavailable.outcome());
        assertTrue(enforcedUnavailable.enforcedFailure());
        assertEquals("Enforced case authentication unavailable: spring auth unavailable (login returned 401)",
            enforcedUnavailable.error());
        assertEquals(unavailable, enforcedUnavailable.notes());
    }

    @Test
    void jsonNormalizerMergeConcatenatesGlobalAndLocalRuleLists() throws Exception {
        JsonNode global = JSON.readTree("""
            {"ignore":["/created_at"],"unordered":["/items"],"rename":[{"side":"spring","from":"/a","to":"/b"}]}
            """);
        JsonNode local = JSON.readTree("""
            {"ignore":["/updated_at"],"timestamps":["/due_at"],"unordered":"/not-a-list"}
            """);
        JsonNode merged = JsonNormalizer.merge(global, local);
        assertEquals(List.of("/created_at", "/updated_at"), texts(merged.path("ignore")));
        assertEquals(List.of("/items"), texts(merged.path("unordered")));
        assertEquals(List.of("/due_at"), texts(merged.path("timestamps")));
        assertEquals(1, merged.path("rename").size());
        assertTrue(JsonNormalizer.ignored("/created_at", merged));
        assertTrue(JsonNormalizer.ignored("/updated_at", merged));
        assertFalse(JsonNormalizer.ignored("/due_at", merged));

        JsonNode globalOnly = JsonNormalizer.merge(global, JSON.nullNode());
        assertEquals(List.of("/created_at"), texts(globalOnly.path("ignore")));
        assertTrue(JsonNormalizer.merge(JSON.createObjectNode(), JSON.createObjectNode()).isEmpty());
    }

    @Test
    void anonymousCasesNeverQualifyForAuthenticatedStatusAllowlistEvenWhenSpringAuthSucceeded()
        throws Exception {
        ContractCase anonymous = CaseLoader.parse(YAML.readTree("""
            - id: anonymous-denied
              path: /accounts
              auth: anonymous
            """)).getFirst();
        Allowlist allowlist = Allowlist.load();
        CapturedResponse rails = new CapturedResponse(401, "application/json", "nope", null);
        CapturedResponse spring = new CapturedResponse(403, "application/problem+json",
            "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403}",
            JSON.readTree("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403}"));
        CaseResult result = new ContractDiffer().diff(anonymous, rails, spring, allowlist,
            JSON.createObjectNode(), "/accounts.json", "/api/v1/accounts", List.of(), true);
        assertEquals(CaseResult.Outcome.DIFF, result.outcome());
        assertTrue(result.differences().stream().anyMatch(difference ->
            difference.kind() == Difference.Kind.STATUS && !difference.allowed()));
        assertEquals(0, allowlist.hits("authz-denied-401-vs-403"));
    }

    private static CapturedResponse capture(HttpClient client, StubServer server, AtomicReference<String> contentType,
                                            AtomicReference<String> body, String type, String payload)
        throws IOException, InterruptedException {
        contentType.set(type);
        body.set(payload);
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(server.url() + "/probe"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        return CapturedResponse.from(response);
    }

    private static List<String> texts(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }
}
