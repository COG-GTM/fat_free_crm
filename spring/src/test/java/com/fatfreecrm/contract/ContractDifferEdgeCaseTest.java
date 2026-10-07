package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ContractDifferEdgeCaseTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ContractDiffer differ = new ContractDiffer();

    @Test
    void statusAllowlistRequiresTheExactRailsAndSpringStatusPair() throws Exception {
        Allowlist allowlist = new Allowlist(List.of(statusEntry("authz", 401, 403)));

        CaseResult mismatch = diff(contractCase("denied", "/accounts/102", "bob"),
            response(401, "application/json", "{\"error\":\"denied\"}"),
            response(404, "application/json", "{\"error\":\"denied\"}"), allowlist, true);
        Difference status = mismatch.differences().getFirst();
        assertEquals(Difference.Kind.STATUS, status.kind());
        assertFalse(status.allowed());
        assertEquals(CaseResult.Outcome.DIFF, mismatch.outcome());
        assertEquals(0, allowlist.hits("authz"));

        CaseResult match = diff(contractCase("denied", "/accounts/102", "bob"),
            response(401, "application/json", "{\"error\":\"denied\"}"),
            response(403, "application/json", "{\"error\":\"denied\"}"), allowlist, true);
        assertEquals(CaseResult.Outcome.CLEAN, match.outcome());
        assertEquals(List.of("authz"), match.differences().getFirst().allowedBy());
        assertEquals(1, allowlist.hits("authz"));
    }

    @Test
    void errorBodyAllowlistNeedsBothSidesToFailAndSpringToReturnProblemJson() throws Exception {
        Allowlist allowlist = new Allowlist(List.of(errorBodyEntry()));

        CaseResult railsSucceeded = diff(contractCase("show", "/accounts/101", "anonymous"),
            response(200, "application/json", "{\"id\":101}"),
            response(500, "application/problem+json", "{\"title\":\"Boom\",\"status\":500}"), allowlist, false);
        assertEquals(CaseResult.Outcome.DIFF, railsSucceeded.outcome());
        assertTrue(railsSucceeded.differences().stream().noneMatch(Difference::allowed));
        assertTrue(railsSucceeded.differences().stream()
            .anyMatch(difference -> difference.kind() == Difference.Kind.CONTENT_TYPE));

        CaseResult plainJsonError = diff(contractCase("missing", "/accounts/999", "anonymous"),
            response(404, "application/json", "This account is no longer available."),
            response(404, "application/json", "{\"title\":\"Not Found\",\"status\":404}"), allowlist, false);
        assertEquals(CaseResult.Outcome.DIFF, plainJsonError.outcome());
        assertTrue(plainJsonError.differences().stream().noneMatch(Difference::allowed));
        assertEquals(0, allowlist.hits("error-body-problem-json"));
    }

    @Test
    void equalsAfterTransformsCanoniciseInstantsNumbersAndWhitespaceOnly() throws Exception {
        Allowlist allowlist = new Allowlist(List.of(
            pointerEntry("instant", "/createdAt", "equalsAfter", "instant"),
            pointerEntry("bad-instant", "/updatedAt", "equalsAfter", "instant"),
            pointerEntry("number", "/amount", "equalsAfter", "number"),
            pointerEntry("trim", "/name", "equalsAfter", "trim"),
            pointerEntry("string", "/code", "equalsAfter", null),
            pointerEntry("unknown", "/odd", "equalsAfter", "reverse"),
            pointerEntry("null-text", "/deletedAt", "equalsAfter", null)
        ));
        CaseResult result = diff(contractCase("transforms", "/accounts/101", "anonymous"),
            response(200, "application/json", """
                {"createdAt":"2026-01-01T09:00:00Z","updatedAt":"yesterday","amount":"1.50","name":" Alice ",
                 "code":7,"odd":1,"deletedAt":null}
                """),
            response(200, "application/json", """
                {"createdAt":"2026-01-01T09:00:00.000Z","updatedAt":"2026-01-01T09:00:00Z","amount":1.5,
                 "name":"Alice","code":"7","odd":2,"deletedAt":"null"}
                """), allowlist, false);

        Map<String, Difference> byPointer = result.differences().stream()
            .collect(Collectors.toMap(Difference::pointer, Function.identity()));
        assertEquals(List.of("instant"), byPointer.get("/createdAt").allowedBy());
        assertEquals(List.of(), byPointer.get("/updatedAt").allowedBy());
        assertEquals(Difference.Kind.TYPE, byPointer.get("/amount").kind());
        assertEquals(List.of("number"), byPointer.get("/amount").allowedBy());
        assertEquals(List.of("trim"), byPointer.get("/name").allowedBy());
        assertEquals(Difference.Kind.TYPE, byPointer.get("/code").kind());
        assertEquals(List.of("string"), byPointer.get("/code").allowedBy());
        assertEquals(List.of(), byPointer.get("/odd").allowedBy());
        assertEquals(List.of("null-text"), byPointer.get("/deletedAt").allowedBy());
        assertEquals(CaseResult.Outcome.DIFF, result.outcome());
        assertEquals(0, allowlist.hits("unknown"));
        assertEquals(0, allowlist.hits("bad-instant"));
    }

    @Test
    void pointerAllowlistWildcardsMatchOneSegmentOnly() throws Exception {
        Allowlist allowlist = new Allowlist(List.of(pointerEntry("timestamps", "/items/*/updatedAt", "ignore", null)));
        CaseResult shallow = diff(contractCase("list", "/accounts", "anonymous"),
            response(200, "application/json",
                "{\"items\":[{\"id\":1,\"updatedAt\":\"a\"},{\"id\":2,\"updatedAt\":\"b\"}]}"),
            response(200, "application/json",
                "{\"items\":[{\"id\":1,\"updatedAt\":\"c\"},{\"id\":2,\"updatedAt\":\"d\"}]}"),
            allowlist, false);
        assertEquals(CaseResult.Outcome.CLEAN, shallow.outcome());
        assertEquals(2, shallow.differences().size());
        assertEquals(2, allowlist.hits("timestamps"));

        CaseResult deep = diff(contractCase("list", "/accounts", "anonymous"),
            response(200, "application/json", "{\"items\":[{\"meta\":{\"updatedAt\":\"a\"}}]}"),
            response(200, "application/json", "{\"items\":[{\"meta\":{\"updatedAt\":\"b\"}}]}"),
            allowlist, false);
        assertEquals(CaseResult.Outcome.DIFF, deep.outcome());
        assertEquals("/items/0/meta/updatedAt", deep.differences().getFirst().pointer());
        assertFalse(deep.differences().getFirst().allowed());
    }

    @Test
    void enforcedCasesFailWhenAuthenticationWasUnavailable() {
        CaseResult pending = result("pending", List.of("spring auth unavailable (login returned 401)"));
        assertSame(pending, pending.failIfEnforcedAuthUnavailable());
        assertFalse(pending.enforcedFailure());

        CaseResult enforcedClean = result("enforced", List.of("rails login took 2 retries"));
        assertSame(enforcedClean, enforcedClean.failIfEnforcedAuthUnavailable());
        assertFalse(enforcedClean.enforcedFailure());

        CaseResult enforced = result("enforced", List.of("rails auth unavailable (CSRF token missing)"))
            .failIfEnforcedAuthUnavailable();
        assertEquals(CaseResult.Outcome.ERROR, enforced.outcome());
        assertEquals("Enforced case authentication unavailable: rails auth unavailable (CSRF token missing)",
            enforced.error());
        assertTrue(enforced.enforcedFailure());
        assertEquals(List.of("rails auth unavailable (CSRF token missing)"), enforced.notes());
    }

    private CaseResult diff(
        ContractCase contractCase,
        CapturedResponse rails,
        CapturedResponse spring,
        Allowlist allowlist,
        boolean springAuthenticated
    ) {
        return differ.diff(contractCase, rails, spring, allowlist, JSON.createObjectNode(),
            "http://rails" + contractCase.rails().path(), "http://spring" + contractCase.spring().path(), List.of(),
            springAuthenticated);
    }

    private static CaseResult result(String status, List<String> notes) {
        ContractCase contractCase = new ContractCase("case", "AB-266", status, "GET", "/accounts",
            new ContractCase.SideRequest("/accounts.json", ContractCase.Target.RAILS),
            new ContractCase.SideRequest("/api/v1/accounts", ContractCase.Target.SPRING),
            JSON.createObjectNode(), null, "alice", JSON.createObjectNode(), "");
        CapturedResponse response = new CapturedResponse(200, "application/json", "[]", JSON.createArrayNode());
        return new CaseResult(contractCase, CaseResult.Outcome.CLEAN, "http://rails/accounts.json",
            "http://spring/api/v1/accounts", response, response, notes, List.of(), null);
    }

    private static ContractCase contractCase(String id, String path, String auth) {
        return new ContractCase(id, "AB-266", "pending", "GET", path,
            new ContractCase.SideRequest(path + ".json", ContractCase.Target.RAILS),
            new ContractCase.SideRequest("/api/v1" + path, ContractCase.Target.SPRING),
            JSON.createObjectNode(), null, auth, JSON.createObjectNode(), "");
    }

    private static CapturedResponse response(int status, String mediaType, String body) {
        JsonNode json = null;
        if (mediaType.equals("application/json") || mediaType.endsWith("+json")) {
            try {
                json = JSON.readTree(body);
            } catch (Exception ignored) {
                json = null;
            }
        }
        return new CapturedResponse(status, mediaType, body, json);
    }

    private static AllowlistEntry statusEntry(String id, int rails, int spring) throws Exception {
        return new AllowlistEntry(id, "*", "**", null, true, "status", "reason", "ref",
            JSON.readTree("{\"status\":{\"rails\":" + rails + ",\"spring\":" + spring + "}}"));
    }

    private static AllowlistEntry errorBodyEntry() {
        return new AllowlistEntry("error-body-problem-json", "*", "**", null, null, "errorBody", "reason", "ref",
            JSON.createObjectNode());
    }

    private static AllowlistEntry pointerEntry(String id, String pointer, String rule, String transform)
        throws Exception {
        String definition = "{\"pointer\":\"" + pointer + "\",\"rule\":\"" + rule + "\""
            + (transform == null ? "" : ",\"transform\":\"" + transform + "\"") + "}";
        return new AllowlistEntry(id, "*", "**", null, null, "pointer", "reason", "ref", JSON.readTree(definition));
    }
}
