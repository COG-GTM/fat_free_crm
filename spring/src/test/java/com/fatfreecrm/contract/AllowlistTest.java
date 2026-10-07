package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AllowlistTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void loadsShippedEntriesWithMatchDefaults() throws Exception {
        Allowlist allowlist = Allowlist.load();
        List<AllowlistEntry> entries = allowlist.entries();
        assertEquals(List.of("authz-denied-401-vs-403", "error-body-problem-json"), ids(entries));

        AllowlistEntry denied = entries.get(0);
        assertEquals("status", denied.kind());
        assertEquals("*", denied.method());
        assertEquals("**", denied.path());
        assertNull(denied.caseId());
        assertEquals(Boolean.TRUE, denied.authenticated());
        assertEquals(401, denied.definition().path("status").path("rails").asInt());
        assertEquals(403, denied.definition().path("status").path("spring").asInt());
        assertFalse(denied.reason().isBlank());
        assertFalse(denied.reference().isBlank());

        AllowlistEntry errorBody = entries.get(1);
        assertEquals("errorBody", errorBody.kind());
        assertNull(errorBody.authenticated());
        assertNull(errorBody.caseId());
        assertEquals("**", errorBody.path());
        entries.forEach(entry -> assertEquals(0, allowlist.hits(entry.id())));
    }

    @Test
    void shippedDenialEntryOnlyAppliesToAuthenticatedRequests() throws Exception {
        Allowlist allowlist = Allowlist.load();
        ContractCase contractCase = contractCase("accounts-show-alice", "GET", "/accounts/106", "alice");
        assertEquals(List.of("authz-denied-401-vs-403", "error-body-problem-json"),
            ids(allowlist.matching(contractCase, true)));
        assertEquals(List.of("error-body-problem-json"), ids(allowlist.matching(contractCase, false)));
        assertEquals(List.of("error-body-problem-json"), ids(allowlist.matching(contractCase)));
    }

    @Test
    void methodMatchingIsCaseInsensitiveAndWildcardAware() {
        Allowlist allowlist = new Allowlist(List.of(
            entry("post-only", "post", "**", null, null),
            entry("any-method", "*", "**", null, null)
        ));
        assertEquals(List.of("post-only", "any-method"),
            ids(allowlist.matching(contractCase("c", "POST", "/accounts", "anonymous"))));
        assertEquals(List.of("any-method"),
            ids(allowlist.matching(contractCase("c", "GET", "/accounts", "anonymous"))));
    }

    @Test
    void authenticatedFalseEntriesOnlyMatchUnauthenticatedRequests() {
        Allowlist allowlist = new Allowlist(List.of(entry("anon-only", "*", "**", null, Boolean.FALSE)));
        ContractCase contractCase = contractCase("c", "GET", "/accounts", "alice");
        assertEquals(1, allowlist.matching(contractCase, false).size());
        assertTrue(allowlist.matching(contractCase, true).isEmpty());
    }

    @Test
    void caseIdAndPathMustBothMatch() {
        Allowlist allowlist = new Allowlist(List.of(entry("scoped", "*", "/accounts/*", "selected", null)));
        assertEquals(1, allowlist.matching(contractCase("selected", "GET", "/accounts/1", "anonymous")).size());
        assertTrue(allowlist.matching(contractCase("selected", "GET", "/contacts/1", "anonymous")).isEmpty());
        assertTrue(allowlist.matching(contractCase("other", "GET", "/accounts/1", "anonymous")).isEmpty());
    }

    @Test
    void singleSegmentGlobDoesNotMatchEmptyOrNestedSegments() {
        Allowlist allowlist = new Allowlist(List.of(entry("one", "*", "/*", null, null)));
        assertEquals(1, allowlist.matching(contractCase("c", "GET", "/accounts", "anonymous")).size());
        assertTrue(allowlist.matching(contractCase("c", "GET", "/", "anonymous")).isEmpty());
        assertTrue(allowlist.matching(contractCase("c", "GET", "", "anonymous")).isEmpty());
        assertTrue(allowlist.matching(contractCase("c", "GET", "/accounts/1", "anonymous")).isEmpty());
        assertTrue(allowlist.matching(contractCase("c", "GET", "/accounts/", "anonymous")).isEmpty());
    }

    @Test
    void recursiveGlobMatchesTheRootPath() {
        Allowlist allowlist = new Allowlist(List.of(entry("all", "*", "**", null, null)));
        assertEquals(1, allowlist.matching(contractCase("c", "GET", "/", "anonymous")).size());
        assertEquals(1, allowlist.matching(contractCase("c", "GET", "", "anonymous")).size());
    }

    @Test
    void countsHitsPerEntryAndIgnoresUnknownEntries() {
        AllowlistEntry known = entry("known", "*", "**", null, null);
        Allowlist allowlist = new Allowlist(List.of(known));
        allowlist.hit(known);
        allowlist.hit(known);
        assertEquals(2, allowlist.hits("known"));
        allowlist.hit(entry("unknown", "*", "**", null, null));
        assertEquals(0, allowlist.hits("unknown"));
        assertEquals(2, allowlist.hits("known"));
    }

    @Test
    void entriesAreImmutable() {
        Allowlist allowlist = new Allowlist(List.of(entry("known", "*", "**", null, null)));
        assertThrows(UnsupportedOperationException.class, () -> allowlist.entries().clear());
    }

    private static List<String> ids(List<AllowlistEntry> entries) {
        return entries.stream().map(AllowlistEntry::id).toList();
    }

    private static AllowlistEntry entry(String id, String method, String path, String caseId, Boolean authenticated) {
        return new AllowlistEntry(id, method, path, caseId, authenticated, "pointer", "reason", "ref",
            JSON.createObjectNode());
    }

    private static ContractCase contractCase(String id, String method, String path, String auth) {
        return new ContractCase(id, "AB-266", "pending", method, path,
            new ContractCase.SideRequest(path + ".json", ContractCase.Target.RAILS),
            new ContractCase.SideRequest("/api/v1" + path, ContractCase.Target.SPRING),
            JSON.createObjectNode(), null, auth, JSON.createObjectNode(), "");
    }
}
