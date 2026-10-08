package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContractDifferTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ContractDiffer differ = new ContractDiffer();

    @Test
    void comparesStatusContentTypeAndEveryRecursiveDifferenceKind() throws Exception {
        CapturedResponse rails = response(401, "application/json; charset=utf-8",
            """
                {"changed":1,"missing":2,"type":3}
                """);
        CapturedResponse spring = response(403, "application/problem+json; charset=utf-8",
            """
                {"changed":2,"extra":4,"type":"3"}
                """);
        CaseResult result = diff(rails, spring, new Allowlist(List.of()), JSON.createObjectNode(), "/accounts/2");
        assertEquals(401, rails.status());
        assertEquals("application/json", rails.mediaType());
        assertEquals(Set.of(Difference.Kind.STATUS, Difference.Kind.CONTENT_TYPE, Difference.Kind.VALUE,
            Difference.Kind.TYPE, Difference.Kind.MISSING_IN_SPRING, Difference.Kind.EXTRA_IN_SPRING),
            result.differences().stream().map(Difference::kind).collect(Collectors.toSet()));
        assertEquals(CaseResult.Outcome.DIFF, result.outcome());
    }

    @Test
    void nonJsonResponsesCompareOnlyStatusAndMediaType() throws Exception {
        CaseResult result = diff(response(404, "text/plain", "not found"),
            response(404, "application/xml", "<errors/>"), new Allowlist(List.of()), JSON.createObjectNode(),
            "/accounts/999");
        assertEquals(EnumSet.of(Difference.Kind.CONTENT_TYPE), result.differences().stream()
            .map(Difference::kind).collect(Collectors.toCollection(() -> EnumSet.noneOf(Difference.Kind.class))));
    }

    @Test
    void malformedJsonAndOneSidedJsonBodiesAreOpenDifferences() throws Exception {
        Allowlist allowlist = new Allowlist(List.of(entry("ignore-root", "pointer", "/accounts/**",
            JSON.readTree("""
                {"pointer":"","rule":"ignore"}
                """))));
        String longInvalidBody = "{".repeat(250);
        CaseResult malformedRails = diff(response(200, "application/json", longInvalidBody),
            response(200, "application/json", "{}"), allowlist, JSON.createObjectNode(), "/accounts/1");
        Difference railsInvalidJson = malformedRails.differences().stream()
            .filter(difference -> difference.kind() == Difference.Kind.INVALID_JSON).findFirst().orElseThrow();
        assertEquals("", railsInvalidJson.pointer());
        assertEquals(200, railsInvalidJson.railsValue().asText().length());
        assertFalse(railsInvalidJson.allowed());

        CaseResult malformedSpring = diff(response(200, "application/json", "{}"),
            response(200, "application/problem+json", "{invalid"), allowlist, JSON.createObjectNode(), "/accounts/1");
        Difference springInvalidJson = malformedSpring.differences().stream()
            .filter(difference -> difference.kind() == Difference.Kind.INVALID_JSON).findFirst().orElseThrow();
        assertEquals("", springInvalidJson.pointer());
        assertEquals("{invalid", springInvalidJson.springValue().asText());
        assertFalse(springInvalidJson.allowed());
        assertEquals(0, allowlist.hits("ignore-root"));

        CaseResult blankBodies = diff(response(200, "application/json", " "),
            response(200, "application/json", "\n"), new Allowlist(List.of()), JSON.createObjectNode(), "/accounts/1");
        assertEquals(CaseResult.Outcome.CLEAN, blankBodies.outcome());

        CaseResult oneSidedJson = diff(response(200, "application/json", "{\"ok\":true}"),
            response(200, "application/json", ""), new Allowlist(List.of()), JSON.createObjectNode(), "/accounts/1");
        assertEquals(Difference.Kind.INVALID_JSON, oneSidedJson.differences().getFirst().kind());
        assertEquals("", oneSidedJson.differences().getFirst().pointer());
    }

    @Test
    void bodyPointerComparesArrayAgainstEnvelopeAndReportsThePointer() throws Exception {
        ContractCase base = contractCase("body-pointer-match", "/accounts");
        ContractCase contractCase = withSpringBodyPointer(base, "/items");

        CaseResult result = diff(contractCase, response(200, "application/json", """
            [{"id":101},{"id":102}]
            """), response(200, "application/json", """
            {"items":[{"id":101},{"id":102}],"page":1}
            """), new Allowlist(List.of()), JSON.createObjectNode(), "/accounts", List.of(), false);

        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
        assertTrue(result.notes().contains("spring bodyPointer /items applied"));
    }

    @Test
    void reportIncludesBodyPointerInMarkdownAndJson(@TempDir Path directory) throws Exception {
        ContractCase contractCase = withSpringBodyPointer(contractCase("body-pointer-report", "/accounts"), "/items");
        CaseResult result = diff(contractCase, response(200, "application/json", """
            [{"id":101}]
            """), response(200, "application/json", """
            {"items":[{"id":101}]}
            """), new Allowlist(List.of()), JSON.createObjectNode(), "/accounts", List.of(), false);

        ReportWriter.write(directory, "http://rails", "http://spring", List.of(result), new Allowlist(List.of()));

        JsonNode report = JSON.readTree(Files.readString(directory.resolve("report.json")));
        assertEquals("/items", report.path("cases").get(0).path("spring").path("bodyPointer").asText());
        assertTrue(Files.readString(directory.resolve("report.md")).contains("Spring: `/items`"));
    }

    @Test
    void bodyPointerMismatchIsDetectedWithinTheSelectedValue() throws Exception {
        ContractCase contractCase = withSpringBodyPointer(contractCase("body-pointer-diff", "/accounts"), "/items");

        CaseResult result = diff(contractCase, response(200, "application/json", """
            [{"id":101}]
            """), response(200, "application/json", """
            {"items":[{"id":102}]}
            """), new Allowlist(List.of()), JSON.createObjectNode(), "/accounts", List.of(), false);

        assertEquals(CaseResult.Outcome.DIFF, result.outcome());
        assertEquals("/0/id", result.differences().getFirst().pointer());
        assertEquals(Difference.Kind.VALUE, result.differences().getFirst().kind());
    }

    @Test
    void missingBodyPointerIsOpenInvalidJsonAtThePointer() throws Exception {
        ContractCase contractCase = withSpringBodyPointer(contractCase("body-pointer-missing", "/accounts"), "/items");
        Allowlist allowlist = new Allowlist(List.of(entry("ignore-items", "pointer", "**", JSON.readTree("""
            {"pointer":"/items","rule":"ignore"}
            """))));

        CaseResult result = diff(contractCase, response(200, "application/json", "[]"),
            response(200, "application/json", """
                {"results":[]}
                """), allowlist, JSON.createObjectNode(), "/accounts", List.of(), false);

        Difference difference = result.differences().getFirst();
        assertEquals(Difference.Kind.INVALID_JSON, difference.kind());
        assertEquals("/items", difference.pointer());
        assertFalse(difference.allowed());
        assertTrue(result.notes().contains("spring bodyPointer /items missing from 2xx JSON response"));
        assertEquals(0, allowlist.hits("ignore-items"));
    }

    @Test
    void bodyPointerIsIgnoredForNonSuccessResponses() throws Exception {
        ContractCase contractCase = withSpringBodyPointer(contractCase("body-pointer-error", "/accounts"), "/items");

        CaseResult result = diff(contractCase, response(404, "application/json", """
            {"error":"missing"}
            """), response(404, "application/json", """
            {"error":"denied"}
            """), new Allowlist(List.of()), JSON.createObjectNode(), "/accounts", List.of(), false);

        assertEquals(Difference.Kind.VALUE, result.differences().getFirst().kind());
        assertEquals("/error", result.differences().getFirst().pointer());
        assertTrue(result.notes().contains("spring bodyPointer /items ignored for HTTP 404"));
    }

    @Test
    void bodyPointerDoesNotChangeFullBodyExpectationEvaluation() throws Exception {
        ContractCase base = contractCase("body-pointer-expectation", "/accounts", "alice", JSON.readTree("""
            {"status":200,"json":{"/meta":"complete"}}
            """));
        ContractCase contractCase = withBodyPointers(base, "/items", "/items");
        String body = """
            {"meta":"complete","items":[{"id":101}]}
            """;

        CaseResult result = diff(contractCase, response(200, "application/json", body),
            response(200, "application/json", body), new Allowlist(List.of()), JSON.createObjectNode(), "/accounts",
            List.of(), false);

        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
    }

    @Test
    void responseExpectationsCheckBothSidesAndCannotBeAllowListed() throws Exception {
        Allowlist allowlist = new Allowlist(List.of(entry("ignore-root", "pointer", "**", JSON.readTree("""
            {"pointer":"","rule":"ignore"}
            """))));
        ContractCase contractCase = contractCase("expect-status", "/users/me", "admin", JSON.readTree("""
            {"status":200}
            """));

        CaseResult result = diff(contractCase, response(404, "text/plain", "not found"),
            response(404, "text/plain", "not found"), allowlist, JSON.createObjectNode(), "/users/me",
            List.of(), false);

        List<Difference> expectations = result.differences().stream()
            .filter(difference -> difference.kind() == Difference.Kind.EXPECTATION).toList();
        assertEquals(CaseResult.Outcome.DIFF, result.outcome());
        assertEquals(2, expectations.size());
        assertEquals("rails-side", expectations.get(0).side());
        assertEquals("spring-side", expectations.get(1).side());
        assertEquals("", expectations.get(0).pointer());
        assertEquals(404, expectations.get(0).railsValue().asInt());
        assertEquals(200, expectations.get(0).springValue().asInt());
        assertTrue(expectations.stream().noneMatch(Difference::allowed));
        assertEquals(0, allowlist.hits("ignore-root"));
    }

    @Test
    void jsonExpectationsIdentifyTheFailingSideInReports(@TempDir Path directory) throws Exception {
        ContractCase contractCase = contractCase("expect-username", "/users/me", "admin", JSON.readTree("""
            {"status":200,"json":{"/username":"admin"}}
            """));
        Allowlist allowlist = new Allowlist(List.of(entry("ignore-username", "pointer", "**", JSON.readTree("""
            {"pointer":"/username","rule":"ignore"}
            """))));
        CaseResult result = diff(contractCase, response(200, "application/json", """
            {"username":"admin"}
            """), response(200, "application/json", """
            {"username":"guest"}
            """), allowlist, JSON.createObjectNode(), "/users/me", List.of(), false);

        Difference expectation = result.differences().stream()
            .filter(difference -> difference.kind() == Difference.Kind.EXPECTATION).findFirst().orElseThrow();
        assertEquals(CaseResult.Outcome.DIFF, result.outcome());
        assertEquals("spring-side", expectation.side());
        assertEquals("/username", expectation.pointer());
        assertEquals("guest", expectation.railsValue().asText());
        assertEquals("admin", expectation.springValue().asText());
        assertFalse(expectation.allowed());

        ReportWriter.write(directory, "http://rails", "http://spring", List.of(result), allowlist);
        JsonNode report = JSON.readTree(Files.readString(directory.resolve("report.json")));
        JsonNode reportExpectation = report.path("cases").get(0).path("differences").get(1);
        assertEquals("spring-side", reportExpectation.path("side").asText());
        assertEquals("guest", reportExpectation.path("actual").asText());
        assertEquals("admin", reportExpectation.path("expected").asText());
        assertTrue(Files.readString(directory.resolve("report.md")).contains("`EXPECTATION` on spring-side"));
    }

    @Test
    void matchingResponseExpectationsRemainClean() throws Exception {
        JsonNode body = JSON.readTree("""
            {"id":1,"username":"admin","admin":true}
            """);
        ContractCase contractCase = contractCase("expect-match", "/users/me", "admin", JSON.readTree("""
            {"status":200,"json":{"/id":1,"/username":"admin","/admin":true}}
            """));

        CaseResult result = diff(contractCase, new CapturedResponse(200, "application/json", body.toString(), body),
            new CapturedResponse(200, "application/json", body.toString(), body), new Allowlist(List.of()),
            JSON.createObjectNode(), "/users/me", List.of(), false);

        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
        assertTrue(result.differences().isEmpty());
    }

    @Test
    void appliesStatusAndProblemBodyAllowlistAndValidatesProblemShape() throws Exception {
        Allowlist allowlist = new Allowlist(List.of(
            entry("authz", "status", "/accounts/**", JSON.readTree("""
                {"status":{"rails":401,"spring":403}}
                """)),
            entry("problem", "errorBody", "**", JSON.readTree("{}"))
        ));
        CaseResult allowed = diff(response(401, "application/json", """
            {"error":"denied"}
            """), response(403, "application/problem+json", """
            {"title":"Forbidden","status":403,"detail":"forbidden"}
            """), allowlist, JSON.createObjectNode(), "/accounts/5");
        assertEquals(CaseResult.Outcome.CLEAN, allowed.outcome());
        assertTrue(allowed.differences().stream().allMatch(Difference::allowed));
        assertTrue(allowlist.hits("authz") > 0);
        assertTrue(allowlist.hits("problem") > 0);

        CaseResult invalid = diff(response(401, "application/json", """
            {"error":"denied"}
            """), response(403, "application/problem+json", """
            {"title":"Forbidden","status":500}
            """), allowlist, JSON.createObjectNode(), "/accounts/5");
        assertEquals(CaseResult.Outcome.DIFF, invalid.outcome());
        assertTrue(invalid.differences().stream().anyMatch(difference ->
            difference.pointer().equals("/status") && !difference.allowed()));
    }

    @Test
    void authenticatedStatusAllowlistRequiresSuccessfulSpringSideAuthentication() throws Exception {
        CapturedResponse rails = response(401, "application/json", "");
        CapturedResponse spring = response(403, "application/json", "");
        JsonNode definition = JSON.readTree("""
            {"match":{"authenticated":true},"status":{"rails":401,"spring":403}}
            """);

        Allowlist anonymousAllowlist = new Allowlist(List.of(
            entry("authz-denied-401-vs-403", "status", "**", definition)));
        CaseResult anonymous = diff(contractCase("anonymous", "/accounts"), rails, spring, anonymousAllowlist,
            JSON.createObjectNode(), "/accounts", List.of(), false);
        assertEquals(CaseResult.Outcome.DIFF, anonymous.outcome());
        assertTrue(anonymous.differences().stream().anyMatch(difference ->
            difference.kind() == Difference.Kind.STATUS && !difference.allowed()));
        assertEquals(0, anonymousAllowlist.hits("authz-denied-401-vs-403"));

        Allowlist authenticatedAllowlist = new Allowlist(List.of(
            entry("authz-denied-401-vs-403", "status", "**", definition)));
        CaseResult authenticated = diff(contractCase("authenticated", "/accounts", "alice"), rails, spring,
            authenticatedAllowlist, JSON.createObjectNode(), "/accounts", List.of(), true);
        assertEquals(CaseResult.Outcome.CLEAN, authenticated.outcome());
        assertEquals(1, authenticatedAllowlist.hits("authz-denied-401-vs-403"));

        Allowlist unavailableAllowlist = new Allowlist(List.of(
            entry("authz-denied-401-vs-403", "status", "**", definition)));
        CaseResult unavailable = diff(contractCase("unavailable", "/accounts", "alice"), rails, spring,
            unavailableAllowlist, JSON.createObjectNode(), "/accounts",
            List.of("spring auth unavailable (login returned 401)"), false);
        assertEquals(CaseResult.Outcome.DIFF, unavailable.outcome());
        assertTrue(unavailable.differences().stream().anyMatch(difference ->
            difference.kind() == Difference.Kind.STATUS && !difference.allowed()));
        assertEquals(0, unavailableAllowlist.hits("authz-denied-401-vs-403"));
    }

    @Test
    void errorBodyAllowsRailsInvalidJsonOnlyWithAValidSpringProblemResponse() throws Exception {
        Allowlist allowlist = new Allowlist(List.of(
            entry("authz-denied-401-vs-403", "status", "**", JSON.readTree("""
                {"status":{"rails":401,"spring":403}}
                """)),
            entry("error-body-problem-json", "errorBody", "**", JSON.createObjectNode())
        ));

        CaseResult allowed = diff(response(401, "application/json", "You are not authorized..."),
            response(403, "application/problem+json", """
                {"title":"Forbidden","status":403}
                """), allowlist, JSON.createObjectNode(), "/accounts/17");
        assertEquals(CaseResult.Outcome.CLEAN, allowed.outcome());
        assertTrue(allowed.differences().stream().allMatch(Difference::allowed));
        Set<String> appliedEntries = allowed.differences().stream().flatMap(difference ->
            difference.allowedBy().stream()).collect(Collectors.toSet());
        assertEquals(Set.of("authz-denied-401-vs-403", "error-body-problem-json"), appliedEntries);
        assertTrue(allowlist.hits("authz-denied-401-vs-403") > 0);
        assertTrue(allowlist.hits("error-body-problem-json") > 0);

        CaseResult malformedSpring = diff(response(401, "application/json", "You are not authorized..."),
            response(401, "application/problem+json", "{invalid"), allowlist, JSON.createObjectNode(), "/accounts/17");
        Difference invalidSpringJson = malformedSpring.differences().stream()
            .filter(difference -> difference.kind() == Difference.Kind.INVALID_JSON).findFirst().orElseThrow();
        assertFalse(invalidSpringJson.allowed());
        assertEquals(CaseResult.Outcome.DIFF, malformedSpring.outcome());

        CaseResult nonProblemSpring = diff(response(401, "application/json", "You are not authorized..."),
            response(401, "application/json", """
                {"title":"Unauthorized","status":401}
                """), allowlist, JSON.createObjectNode(), "/accounts/17");
        Difference invalidWithNonProblemSpring = nonProblemSpring.differences().stream()
            .filter(difference -> difference.kind() == Difference.Kind.INVALID_JSON).findFirst().orElseThrow();
        assertFalse(invalidWithNonProblemSpring.allowed());
        assertEquals(CaseResult.Outcome.DIFF, nonProblemSpring.outcome());
    }

    @Test
    void errorBodyAllowsRailsPlainTextNotFoundAgainstSpringProblemJson() throws Exception {
        Allowlist allowlist = new Allowlist(List.of(
            entry("error-body-problem-json", "errorBody", "**", JSON.createObjectNode())
        ));
        CaseResult result = diff(response(404, "text/plain", "Not found"),
            response(404, "application/problem+json", """
                {"title":"Not Found","status":404}
                """), allowlist, JSON.createObjectNode(), "/accounts/404");

        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
        assertEquals(0, result.differences().stream().filter(difference -> !difference.allowed()).count());
        assertTrue(result.differences().stream()
            .flatMap(difference -> difference.allowedBy().stream())
            .anyMatch("error-body-problem-json"::equals));
    }

    @Test
    void pointerAllowlistCanAllowMissingKeyDiagnostics() throws Exception {
        Allowlist allowlist = new Allowlist(List.of(
            entry("allow-missing-key", "pointer", "**", JSON.readTree("""
                {"pointer":"/items/*","rule":"ignore"}
                """))
        ));
        JsonNode options = JSON.readTree("""
            {"unorderedArrays":[{"pointer":"/items","key":"/id"}]}
            """);
        String body = """
            {"items":[{"name":"x"}]}
            """;
        CaseResult result = diff(response(200, "application/json", body),
            response(200, "application/json", body), allowlist, options, "/accounts/1");
        List<Difference> missingKeys = result.differences().stream()
            .filter(difference -> difference.kind() == Difference.Kind.MISSING_KEY).toList();

        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
        assertEquals(2, missingKeys.size());
        assertTrue(missingKeys.stream().allMatch(Difference::allowed));
        assertTrue(missingKeys.stream().allMatch(difference ->
            difference.allowedBy().equals(List.of("allow-missing-key"))));
        assertTrue(allowlist.hits("allow-missing-key") > 0);
    }

    @Test
    void matchesCaseAndPathGlobsAndTracksStaleEntries() throws Exception {
        Allowlist allowlist = new Allowlist(List.of(
            entry("narrow", "pointer", "/accounts/*", JSON.readTree("""
                {"match":{"case":"selected"},"pointer":"/value","rule":"ignore"}
                """)),
            entry("stale", "pointer", "/contacts/**", JSON.readTree("""
                {"pointer":"/ignored","rule":"ignore"}
                """))
        ));
        ContractCase selected = contractCase("selected", "/accounts/17");
        assertEquals(1, allowlist.matching(selected).size());
        assertTrue(allowlist.matching(contractCase("other", "/accounts/17")).isEmpty());
        assertTrue(allowlist.matching(contractCase("selected", "/accounts/17/child")).isEmpty());
        assertEquals(0, allowlist.hits("stale"));
    }

    @Test
    void recursivePathGlobsMatchPrefixAndZeroOrMoreSegments() throws Exception {
        Allowlist recursive = new Allowlist(List.of(entry("recursive", "pointer", "/accounts/**",
            JSON.createObjectNode())));
        assertEquals(1, recursive.matching(contractCase("selected", "/accounts")).size());
        assertEquals(1, recursive.matching(contractCase("selected", "/accounts/101")).size());
        assertEquals(1, recursive.matching(contractCase("selected", "/accounts/101/contacts")).size());
        assertTrue(recursive.matching(contractCase("selected", "/accountsx")).isEmpty());

        Allowlist anyPath = new Allowlist(List.of(entry("all", "pointer", "**", JSON.createObjectNode())));
        assertEquals(1, anyPath.matching(contractCase("selected", "/accounts/101/contacts")).size());

        Allowlist oneSegment = new Allowlist(List.of(entry("one", "pointer", "/accounts/*",
            JSON.createObjectNode())));
        assertTrue(oneSegment.matching(contractCase("selected", "/accounts")).isEmpty());
        assertEquals(1, oneSegment.matching(contractCase("selected", "/accounts/101")).size());

        Allowlist middleRecursive = new Allowlist(List.of(entry("middle", "pointer", "/accounts/**/contacts",
            JSON.createObjectNode())));
        assertEquals(1, middleRecursive.matching(contractCase("selected", "/accounts/contacts")).size());
        assertEquals(1, middleRecursive.matching(contractCase("selected", "/accounts/101/contacts")).size());
    }

    @Test
    void appliesPointerEqualsAfterTransforms() throws Exception {
        Allowlist allowlist = new Allowlist(List.of(entry("casefold", "pointer", "**", JSON.readTree("""
            {"pointer":"/name","rule":"equalsAfter","transform":"lowercase"}
            """))));
        CaseResult result = diff(response(200, "application/json", """
            {"name":"ALICE"}
            """), response(200, "application/json", """
            {"name":"alice"}
            """), allowlist, JSON.createObjectNode(), "/users/1");
        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
        assertEquals(List.of("casefold"), result.differences().getFirst().allowedBy());
    }

    @Test
    void reportsProblemBodyViolationsAsOpenDifferences() throws Exception {
        Allowlist allowlist = new Allowlist(List.of(
            entry("problem", "errorBody", "**", JSON.readTree("{}"))
        ));
        CaseResult result = diff(response(401, "text/plain", "Unauthorized"),
            response(401, "application/problem+json", """
                {"status":401}
                """), allowlist, JSON.createObjectNode(), "/accounts");
        assertFalse(result.differences().stream().filter(difference -> difference.pointer().equals("/title"))
            .findFirst().orElseThrow().allowed());
    }

    @Test
    void removesMatchingRailsYamlKeysAndMultilineObjectChanges() throws Exception {
        Allowlist allowlist = yamlKeysRemovedAllowlist();
        String object = "---\nname: Account\npassword_salt: hidden\n  nested: hidden\n";
        String changes = "---\nname:\n- Before\n- After\nencrypted_password:\n- old\n- new\n";
        String springObject = "---\nname: Account\n";
        String springChanges = "---\nname:\n- Before\n- After\n";

        CaseResult result = diff(response(200, "application/json",
                json(versions(version(object, changes)))),
            response(200, "application/json", json(versions(version(springObject, springChanges)))),
            allowlist, JSON.createObjectNode(), "/activities");

        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
        assertEquals(1, allowlist.hits("object-secret-yaml"));
        assertEquals(1, allowlist.hits("object-changes-secret-yaml"));
    }

    @Test
    void reportsDifferencesInYamlKeysThatDoNotMatchThePattern() throws Exception {
        Allowlist allowlist = yamlKeysRemovedAllowlist();
        String railsObject = "---\nname: Rails Account\npassword: hidden\n";
        String springObject = "---\nname: Spring Account\n";

        CaseResult result = diff(response(200, "application/json", json(versions(version(railsObject, null)))),
            response(200, "application/json", json(versions(version(springObject, null)))),
            allowlist, JSON.createObjectNode(), "/activities");

        assertEquals(CaseResult.Outcome.DIFF, result.outcome());
        assertEquals(Difference.Kind.VALUE, result.differences().getFirst().kind());
        assertEquals("/0/object", result.differences().getFirst().pointer());
    }

    @Test
    void reportsSpringChangesOutsideSecretYamlKeys() throws Exception {
        Allowlist allowlist = yamlKeysRemovedAllowlist();
        String railsObject = "---\nemail: rails@example.test\npassword: hidden\n";
        String springObject = "---\nemail: spring@example.test\n";

        CaseResult result = diff(response(200, "application/json", json(versions(version(railsObject, null)))),
            response(200, "application/json", json(versions(version(springObject, null)))),
            allowlist, JSON.createObjectNode(), "/activities");

        assertEquals(CaseResult.Outcome.DIFF, result.outcome());
        assertEquals(Difference.Kind.VALUE, result.differences().getFirst().kind());
        assertEquals("/0/object", result.differences().getFirst().pointer());
    }

    @Test
    void removesYamlKeysAtWildcardPointersAcrossVersionArrays() throws Exception {
        Allowlist allowlist = new Allowlist(List.of(entry("version-secret-yaml", "yamlKeysRemoved", "/activities",
            JSON.readTree("""
                {"pointer":"/*/object","keyPattern":"(?i).*(password|token|salt).*"}
                """))));
        ArrayNode rails = JSON.createArrayNode()
            .add(version("---\nname: First\npassword: hidden\n", null))
            .add(version("---\nname: Second\nauthentication_token: hidden\n", null));
        ArrayNode spring = JSON.createArrayNode()
            .add(version("---\nname: First\n", null))
            .add(version("---\nname: Second\n", null));

        CaseResult result = diff(response(200, "application/json", json(rails)),
            response(200, "application/json", json(spring)),
            allowlist, JSON.createObjectNode(), "/activities");

        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
        assertEquals(1, allowlist.hits("version-secret-yaml"));
    }

    @Test
    void leavesNonStringYamlValuesUntouched() throws Exception {
        Allowlist allowlist = yamlKeysRemovedAllowlist();
        JsonNode rails = JSON.readTree("""
            [{"object":{"password":"rails"}}]
            """);
        JsonNode spring = JSON.readTree("""
            [{"object":{"password":"spring"}}]
            """);

        CaseResult result = diff(response(200, "application/json", json(rails)),
            response(200, "application/json", json(spring)),
            allowlist, JSON.createObjectNode(), "/activities");

        assertEquals(CaseResult.Outcome.DIFF, result.outcome());
        assertEquals(0, allowlist.hits("object-secret-yaml"));
        assertEquals(Difference.Kind.VALUE, result.differences().getFirst().kind());
        assertEquals("/0/object/password", result.differences().getFirst().pointer());
    }

    private CaseResult diff(
        CapturedResponse rails,
        CapturedResponse spring,
        Allowlist allowlist,
        JsonNode normalize,
        String path
    ) {
        return differ.diff(contractCase("selected", path), rails, spring, allowlist, normalize,
            "http://rails" + path, "http://spring" + path, List.of());
    }

    private CaseResult diff(
        ContractCase contractCase,
        CapturedResponse rails,
        CapturedResponse spring,
        Allowlist allowlist,
        JsonNode normalize,
        String path,
        List<String> notes,
        boolean springAuthenticated
    ) {
        return differ.diff(contractCase, rails, spring, allowlist, normalize,
            "http://rails" + path, "http://spring" + path, notes, springAuthenticated);
    }

    private static ContractCase contractCase(String id, String path) {
        return contractCase(id, path, "anonymous");
    }

    private static ContractCase contractCase(String id, String path, String auth) {
        return new ContractCase(id, "AB-266", "pending", "GET", path,
            new ContractCase.SideRequest(path + ".json", ContractCase.Target.RAILS),
            new ContractCase.SideRequest("/api/v1" + path, ContractCase.Target.SPRING),
            JSON.createObjectNode(), null, auth, JSON.createObjectNode(), "");
    }

    private static ContractCase contractCase(String id, String path, String auth, JsonNode expect) {
        return new ContractCase(id, "AB-266", "pending", "GET", path,
            new ContractCase.SideRequest(path + ".json", ContractCase.Target.RAILS),
            new ContractCase.SideRequest("/api/v1" + path, ContractCase.Target.SPRING),
            JSON.createObjectNode(), null, auth, JSON.createObjectNode(), "", expect);
    }

    private static ContractCase withSpringBodyPointer(ContractCase contractCase, String bodyPointer) {
        return withBodyPointers(contractCase, null, bodyPointer);
    }

    private static ContractCase withBodyPointers(
        ContractCase contractCase,
        String railsPointer,
        String springPointer
    ) {
        return new ContractCase(
            contractCase.id(),
            contractCase.ticket(),
            contractCase.status(),
            contractCase.method(),
            contractCase.path(),
            new ContractCase.SideRequest(
                contractCase.rails().path(), contractCase.rails().target(), railsPointer),
            new ContractCase.SideRequest(
                contractCase.spring().path(), contractCase.spring().target(), springPointer),
            contractCase.params(),
            contractCase.body(),
            contractCase.auth(),
            contractCase.normalize(),
            contractCase.description(),
            contractCase.expect()
        );
    }

    private static CapturedResponse response(int status, String contentType, String body) throws Exception {
        String cleanMediaType = contentType.split(";", 2)[0].trim();
        JsonNode json = null;
        if ((cleanMediaType.equals("application/json") || cleanMediaType.endsWith("+json")) && !body.isBlank()) {
            try {
                json = JSON.readTree(body);
            } catch (Exception ignored) {
                json = null;
            }
        }
        return new CapturedResponse(status, cleanMediaType, body, json);
    }

    private static String json(JsonNode value) throws Exception {
        return JSON.writeValueAsString(value);
    }

    private static ObjectNode version(String object, String objectChanges) {
        ObjectNode version = JSON.createObjectNode();
        if (object != null) {
            version.put("object", object);
        }
        if (objectChanges != null) {
            version.put("object_changes", objectChanges);
        }
        return version;
    }

    private static ArrayNode versions(ObjectNode... versions) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode version : versions) {
            array.add(version);
        }
        return array;
    }

    private static Allowlist yamlKeysRemovedAllowlist() throws Exception {
        return new Allowlist(List.of(
            entry("object-secret-yaml", "yamlKeysRemoved", "/activities", JSON.readTree("""
                {"pointer":"/*/object","keyPattern":"(?i).*(password|token|salt).*"}
                """)),
            entry("object-changes-secret-yaml", "yamlKeysRemoved", "/activities", JSON.readTree("""
                {"pointer":"/*/object_changes","keyPattern":"(?i).*(password|token|salt).*"}
                """))
        ));
    }

    private static AllowlistEntry entry(String id, String kind, String path, JsonNode definition) {
        JsonNode match = definition.path("match");
        return new AllowlistEntry(id, "*", path, match.path("case").asText(null),
            match.has("authenticated") ? match.path("authenticated").asBoolean() : null, kind,
            "test reason", "test ref", definition);
    }
}
