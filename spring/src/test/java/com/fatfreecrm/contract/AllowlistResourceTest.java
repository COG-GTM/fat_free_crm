package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AllowlistResourceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void loadsTheCheckedInAllowlistWithItsMatchFieldsAndDefaults() throws Exception {
        Allowlist allowlist = Allowlist.load();
        assertEquals(List.of("authz-denied-401-vs-403", "error-body-problem-json"),
            allowlist.entries().stream().map(AllowlistEntry::id).toList());

        AllowlistEntry status = allowlist.entries().get(0);
        assertEquals("status", status.kind());
        assertEquals("*", status.method());
        assertEquals("**", status.path());
        assertNull(status.caseId());
        assertEquals(Boolean.TRUE, status.authenticated());
        assertEquals(401, status.definition().path("status").path("rails").asInt());
        assertEquals(403, status.definition().path("status").path("spring").asInt());
        assertFalse(status.reason().isBlank());
        assertTrue(status.reference().contains("target-architecture.md"));

        AllowlistEntry errorBody = allowlist.entries().get(1);
        assertEquals("errorBody", errorBody.kind());
        assertNull(errorBody.authenticated());
        assertFalse(errorBody.reason().isBlank());

        assertEquals(0, allowlist.hits("authz-denied-401-vs-403"));
        assertEquals(0, allowlist.hits("error-body-problem-json"));
    }

    @Test
    void theCheckedInStatusRuleOnlyAppliesToAuthenticatedRequests() throws Exception {
        Allowlist allowlist = Allowlist.load();
        ContractCase anonymous = contractCase("GET", "/accounts", "anonymous");
        ContractCase authenticated = contractCase("GET", "/accounts/102", "bob");

        assertEquals(List.of("error-body-problem-json"), ids(allowlist.matching(anonymous)));
        assertEquals(List.of("error-body-problem-json"), ids(allowlist.matching(authenticated)));
        assertEquals(List.of("authz-denied-401-vs-403", "error-body-problem-json"),
            ids(allowlist.matching(authenticated, true)));
    }

    @Test
    void matchesMethodsCaseInsensitivelyAndRestrictsByCaseId() {
        Allowlist allowlist = new Allowlist(List.of(
            entry("post-only", "post", "**", null, null),
            entry("one-case", "*", "**", "only-this", null)
        ));
        assertEquals(List.of("post-only"), ids(allowlist.matching(contractCase("POST", "/accounts", "anonymous"))));
        assertTrue(allowlist.matching(contractCase("GET", "/accounts", "anonymous")).isEmpty());
        assertEquals(List.of("one-case"), ids(allowlist.matching(
            new ContractCase("only-this", "", "pending", "GET", "/accounts", side("/accounts.json"),
                side("/api/v1/accounts"), JSON.createObjectNode(), null, "anonymous", JSON.createObjectNode(), ""))));
    }

    @Test
    void authenticatedFlagDefaultsToUnauthenticatedAndMatchesExactly() {
        Allowlist allowlist = new Allowlist(List.of(
            entry("auth-only", "*", "**", null, true),
            entry("anon-only", "*", "**", null, false),
            entry("either", "*", "**", null, null)
        ));
        ContractCase contractCase = contractCase("GET", "/accounts", "alice");
        assertEquals(List.of("anon-only", "either"), ids(allowlist.matching(contractCase)));
        assertEquals(List.of("anon-only", "either"), ids(allowlist.matching(contractCase, false)));
        assertEquals(List.of("auth-only", "either"), ids(allowlist.matching(contractCase, true)));
    }

    @Test
    void countsHitsOnlyForKnownEntriesAndExposesAnImmutableEntryList() {
        AllowlistEntry known = entry("known", "*", "**", null, null);
        AllowlistEntry stranger = entry("stranger", "*", "**", null, null);
        Allowlist allowlist = new Allowlist(List.of(known));

        allowlist.hit(known);
        allowlist.hit(known);
        allowlist.hit(stranger);
        assertEquals(2, allowlist.hits("known"));
        assertEquals(0, allowlist.hits("stranger"));
        assertThrows(UnsupportedOperationException.class, () -> allowlist.entries().add(stranger));
    }

    @Test
    void globsHandleRootPathsAndTrailingRecursiveSegments() {
        Allowlist root = new Allowlist(List.of(entry("root", "*", "/", null, null)));
        assertEquals(1, root.matching(contractCase("GET", "/", "anonymous")).size());
        assertTrue(root.matching(contractCase("GET", "/accounts", "anonymous")).isEmpty());

        Allowlist everything = new Allowlist(List.of(entry("all", "*", "**", null, null)));
        assertEquals(1, everything.matching(contractCase("GET", "/", "anonymous")).size());

        Allowlist trailing = new Allowlist(List.of(entry("trailing", "*", "/accounts/**", null, null)));
        assertEquals(1, trailing.matching(contractCase("GET", "/accounts", "anonymous")).size());
        assertEquals(1, trailing.matching(contractCase("GET", "/accounts/101/contacts/5", "anonymous")).size());
        assertTrue(trailing.matching(contractCase("GET", "/contacts", "anonymous")).isEmpty());

        Allowlist single = new Allowlist(List.of(entry("single", "*", "/accounts/*", null, null)));
        assertTrue(single.matching(contractCase("GET", "/accounts/101/contacts", "anonymous")).isEmpty());
    }

    private static List<String> ids(List<AllowlistEntry> entries) {
        return entries.stream().map(AllowlistEntry::id).toList();
    }

    private static AllowlistEntry entry(String id, String method, String path, String caseId, Boolean authenticated) {
        return new AllowlistEntry(id, method, path, caseId, authenticated, "pointer", "reason", "reference",
            JSON.createObjectNode());
    }

    private static ContractCase contractCase(String method, String path, String auth) {
        return new ContractCase("case-" + path, "AB-266", "pending", method, path, side(path + ".json"),
            side("/api/v1" + path), JSON.createObjectNode(), null, auth, JSON.createObjectNode(), "");
    }

    private static ContractCase.SideRequest side(String path) {
        return new ContractCase.SideRequest(path, ContractCase.Target.RAILS);
    }
}
