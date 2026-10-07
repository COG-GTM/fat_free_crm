package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaseResultTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void enforcedFailureRequiresEnforcedStatusAndNonCleanOutcome() {
        assertTrue(result("enforced", CaseResult.Outcome.DIFF, List.of()).enforcedFailure());
        assertTrue(result("enforced", CaseResult.Outcome.ERROR, List.of()).enforcedFailure());
        assertFalse(result("enforced", CaseResult.Outcome.CLEAN, List.of()).enforcedFailure());
        assertFalse(result("pending", CaseResult.Outcome.DIFF, List.of()).enforcedFailure());
        assertFalse(result("pending", CaseResult.Outcome.ERROR, List.of()).enforcedFailure());
    }

    @Test
    void pendingCasesAreNeverFailedForUnavailableAuth() {
        CaseResult pending = result("pending", CaseResult.Outcome.CLEAN,
            List.of("spring auth unavailable (login returned 404)"));
        assertSame(pending, pending.failIfEnforcedAuthUnavailable());
        assertFalse(pending.enforcedFailure());
    }

    @Test
    void enforcedCasesWithoutUnavailableNotesAreUnchanged() {
        CaseResult enforced = result("enforced", CaseResult.Outcome.CLEAN, List.of("some other note"));
        assertSame(enforced, enforced.failIfEnforcedAuthUnavailable());
        assertNull(enforced.error());
    }

    @Test
    void enforcedCasesWithUnavailableAuthBecomeErrorsAndKeepTheirContext() {
        CaseResult enforced = result("enforced", CaseResult.Outcome.CLEAN, List.of(
            "rails auth unavailable (CSRF token missing)",
            "spring auth unavailable (login returned 404)"));
        CaseResult failed = enforced.failIfEnforcedAuthUnavailable();
        assertEquals(CaseResult.Outcome.ERROR, failed.outcome());
        assertEquals("Enforced case authentication unavailable: rails auth unavailable (CSRF token missing)",
            failed.error());
        assertTrue(failed.enforcedFailure());
        assertSame(enforced.contractCase(), failed.contractCase());
        assertEquals(enforced.notes(), failed.notes());
        assertEquals(enforced.differences(), failed.differences());
        assertEquals(enforced.railsUrl(), failed.railsUrl());
        assertEquals(enforced.springUrl(), failed.springUrl());
        assertSame(enforced.rails(), failed.rails());
        assertSame(enforced.spring(), failed.spring());
    }

    @Test
    void notesAndDifferencesAreDefensivelyCopied() {
        List<String> notes = new ArrayList<>(List.of("note"));
        List<Difference> differences = new ArrayList<>(List.of(
            new Difference("/id", Difference.Kind.VALUE, IntNode.valueOf(1), IntNode.valueOf(2), List.of())));
        CaseResult result = new CaseResult(contractCase("pending"), CaseResult.Outcome.DIFF, "http://rails",
            "http://spring", null, null, notes, differences, null);
        notes.add("late");
        differences.clear();
        assertEquals(List.of("note"), result.notes());
        assertEquals(1, result.differences().size());
        assertThrows(UnsupportedOperationException.class, () -> result.notes().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> result.differences().clear());
    }

    private static CaseResult result(String status, CaseResult.Outcome outcome, List<String> notes) {
        CapturedResponse response = new CapturedResponse(200, "application/json", "{}", JSON.createObjectNode());
        return new CaseResult(contractCase(status), outcome, "http://rails/accounts.json",
            "http://spring/api/v1/accounts", response, response, notes, List.of(
                new Difference("/id", Difference.Kind.VALUE, IntNode.valueOf(1), IntNode.valueOf(2), List.of())),
            null);
    }

    private static ContractCase contractCase(String status) {
        return new ContractCase("accounts-index", "AB-266", status, "GET", "/accounts",
            new ContractCase.SideRequest("/accounts.json", ContractCase.Target.RAILS),
            new ContractCase.SideRequest("/api/v1/accounts", ContractCase.Target.SPRING),
            JSON.createObjectNode(), null, "alice", JSON.createObjectNode(), "");
    }
}
