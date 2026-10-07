package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

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

    private static ContractCase contractCase(String id, String path) {
        return new ContractCase(id, "AB-266", "pending", "GET", path,
            new ContractCase.SideRequest(path + ".json", ContractCase.Target.RAILS),
            new ContractCase.SideRequest("/api/v1" + path, ContractCase.Target.SPRING),
            JSON.createObjectNode(), null, "anonymous", JSON.createObjectNode(), "");
    }

    private static CapturedResponse response(int status, String contentType, String body) throws Exception {
        String cleanMediaType = contentType.split(";", 2)[0];
        JsonNode json = cleanMediaType.equals("application/json") || cleanMediaType.endsWith("+json")
            ? JSON.readTree(body) : null;
        return new CapturedResponse(status, cleanMediaType, body, json);
    }

    private static AllowlistEntry entry(String id, String kind, String path, JsonNode definition) {
        return new AllowlistEntry(id, "*", path, definition.path("match").path("case").asText(null), kind,
            "test reason", "test ref", definition);
    }
}
