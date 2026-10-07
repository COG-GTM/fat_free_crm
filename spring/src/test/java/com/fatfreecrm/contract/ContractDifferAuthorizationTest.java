package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractDifferAuthorizationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ContractDiffer differ = new ContractDiffer();

    @Test
    void anonymousCasesNeverUseAuthenticatedOnlyAllowlistEntriesEvenWhenSpringReportsAuthentication()
        throws Exception {
        Allowlist allowlist = deniedStatusAllowlist();
        CaseResult result = differ.diff(contractCase("anonymous"), response(401), response(403), allowlist,
            JSON.createObjectNode(), "http://rails/accounts/106.json", "http://spring/api/v1/accounts/106",
            List.of(), true);
        assertEquals(CaseResult.Outcome.DIFF, result.outcome());
        Difference status = result.differences().getFirst();
        assertEquals(Difference.Kind.STATUS, status.kind());
        assertFalse(status.allowed());
        assertEquals(0, allowlist.hits("authz-denied-401-vs-403"));
    }

    @Test
    void diffWithoutAnAuthenticationFlagTreatsTheSpringSideAsUnauthenticated() throws Exception {
        Allowlist allowlist = deniedStatusAllowlist();
        CaseResult result = differ.diff(contractCase("alice"), response(401), response(403), allowlist,
            JSON.createObjectNode(), "http://rails/accounts/106.json", "http://spring/api/v1/accounts/106",
            List.of());
        assertEquals(CaseResult.Outcome.DIFF, result.outcome());
        assertFalse(result.differences().getFirst().allowed());
        assertEquals(0, allowlist.hits("authz-denied-401-vs-403"));

        CaseResult authenticated = differ.diff(contractCase("alice"), response(401), response(403), allowlist,
            JSON.createObjectNode(), "http://rails/accounts/106.json", "http://spring/api/v1/accounts/106",
            List.of(), true);
        assertEquals(CaseResult.Outcome.CLEAN, authenticated.outcome());
        assertEquals(List.of("authz-denied-401-vs-403"), authenticated.differences().getFirst().allowedBy());
        assertEquals(1, allowlist.hits("authz-denied-401-vs-403"));
    }

    @Test
    void statusAllowlistOnlyCoversTheExactStatusPairItDeclares() throws Exception {
        Allowlist allowlist = deniedStatusAllowlist();
        CaseResult notFound = differ.diff(contractCase("alice"), response(404), response(403), allowlist,
            JSON.createObjectNode(), "http://rails/accounts/999.json", "http://spring/api/v1/accounts/999",
            List.of(), true);
        assertEquals(CaseResult.Outcome.DIFF, notFound.outcome());
        assertFalse(notFound.differences().getFirst().allowed());

        CaseResult reversed = differ.diff(contractCase("alice"), response(403), response(401), allowlist,
            JSON.createObjectNode(), "http://rails/accounts/106.json", "http://spring/api/v1/accounts/106",
            List.of(), true);
        assertEquals(CaseResult.Outcome.DIFF, reversed.outcome());
        assertFalse(reversed.differences().getFirst().allowed());
        assertEquals(0, allowlist.hits("authz-denied-401-vs-403"));
    }

    @Test
    void matchingDenialsOnBothSidesStayCleanWithoutAnyAllowlist() throws Exception {
        CaseResult result = differ.diff(contractCase("carol"), response(403), response(403),
            new Allowlist(List.of()), JSON.createObjectNode(), "http://rails/accounts/102.json",
            "http://spring/api/v1/accounts/102", List.of(), true);
        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
        assertTrue(result.differences().isEmpty());
    }

    private static Allowlist deniedStatusAllowlist() throws Exception {
        return new Allowlist(List.of(new AllowlistEntry("authz-denied-401-vs-403", "*", "**", null, Boolean.TRUE,
            "status", "Spring authorization denials use 403 while Rails returns 401.", "ref", JSON.readTree("""
                {"status":{"rails":401,"spring":403}}
                """))));
    }

    private static ContractCase contractCase(String auth) {
        return new ContractCase("accounts-show-denied", "AB-266", "pending", "GET", "/accounts/106",
            new ContractCase.SideRequest("/accounts/106.json", ContractCase.Target.RAILS),
            new ContractCase.SideRequest("/api/v1/accounts/106", ContractCase.Target.SPRING),
            JSON.createObjectNode(), null, auth, JSON.createObjectNode(), "");
    }

    private static CapturedResponse response(int status) throws Exception {
        JsonNode body = JSON.readTree("{\"error\":\"access denied\"}");
        return new CapturedResponse(status, "application/json", body.toString(), body);
    }
}
