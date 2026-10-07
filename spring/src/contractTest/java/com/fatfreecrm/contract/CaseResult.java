package com.fatfreecrm.contract;

import java.util.List;

public record CaseResult(
    ContractCase contractCase,
    Outcome outcome,
    String railsUrl,
    String springUrl,
    CapturedResponse rails,
    CapturedResponse spring,
    List<String> notes,
    List<Difference> differences,
    String error
) {
    public enum Outcome {
        CLEAN,
        DIFF,
        ERROR
    }

    public CaseResult {
        notes = List.copyOf(notes);
        differences = List.copyOf(differences);
    }

    public boolean enforcedFailure() {
        return contractCase.status().equals("enforced") && outcome != Outcome.CLEAN;
    }

    public CaseResult failIfEnforcedAuthUnavailable() {
        if (!contractCase.status().equals("enforced")) {
            return this;
        }
        String unavailable = notes.stream().filter(note -> note.contains("auth unavailable")).findFirst().orElse(null);
        if (unavailable == null) {
            return this;
        }
        return new CaseResult(contractCase, Outcome.ERROR, railsUrl, springUrl, rails, spring, notes, differences,
            "Enforced case authentication unavailable: " + unavailable);
    }
}
