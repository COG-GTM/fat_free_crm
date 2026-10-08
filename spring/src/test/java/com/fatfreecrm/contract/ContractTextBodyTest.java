package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

/** AB-273 harness extension: per-side {@code accept} and case-level {@code compare: text}. */
class ContractTextBodyTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final ContractDiffer differ = new ContractDiffer();

    @Test
    void parsesPerSideAcceptAndTextCompare() throws Exception {
        List<ContractCase> cases = CaseLoader.parse(YAML.readTree("""
            - id: export
              path: /accounts
              compare: text
              rails: {path: /accounts.csv}
              spring: {accept: text/csv}
            - id: json
              path: /accounts
            """));

        ContractCase export = cases.getFirst();
        assertTrue(export.textBody());
        assertEquals("/accounts.csv", export.rails().path());
        assertNull(export.rails().accept());
        assertEquals("/api/v1/accounts", export.spring().path());
        assertEquals("text/csv", export.spring().accept());
        assertFalse(cases.get(1).textBody());
        assertNull(cases.get(1).spring().accept());
    }

    @Test
    void rejectsUnknownCompareMode() throws Exception {
        var yaml = YAML.readTree("""
            - id: bad
              path: /accounts
              compare: bytes
            """);

        assertThrows(IllegalArgumentException.class, () -> CaseLoader.parse(yaml));
    }

    @Test
    void identicalTextBodiesAreClean() throws Exception {
        CaseResult result = diff(textCase(), csv("Id,Name\n1,A\n"), csv("Id,Name\n1,A\n"));

        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
        assertTrue(result.differences().isEmpty());
    }

    @Test
    void textBodyDifferenceReportsFirstDifferingLine() throws Exception {
        CaseResult result = diff(textCase(), csv("Id,Name\n1,A\n2,B\n"), csv("Id,Name\n1,A\n2,C\n"));

        assertEquals(CaseResult.Outcome.DIFF, result.outcome());
        Difference difference = result.differences().getFirst();
        assertEquals(Difference.Kind.TEXT_BODY, difference.kind());
        assertEquals("/line/3", difference.pointer());
        assertEquals("2,B", difference.railsValue().asText());
        assertEquals("2,C", difference.springValue().asText());
    }

    @Test
    void missingTrailingLinesAreReported() throws Exception {
        CaseResult result = diff(textCase(), csv("Id\n1\n"), csv(""));

        Difference difference = result.differences().getFirst();
        assertEquals(Difference.Kind.TEXT_BODY, difference.kind());
        assertEquals("/line/1", difference.pointer());
        assertEquals("", difference.springValue().asText());
    }

    @Test
    void withoutTextCompareNonJsonBodiesStillCompareOnlyStatusAndMediaType() throws Exception {
        ContractCase jsonCase = CaseLoader.parse(YAML.readTree("""
            - id: default
              path: /accounts
            """)).getFirst();

        CaseResult result = diff(jsonCase, csv("a\n"), csv("b\n"));

        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
    }

    private ContractCase textCase() throws Exception {
        return CaseLoader.parse(YAML.readTree("""
            - id: export
              path: /accounts
              compare: text
            """)).getFirst();
    }

    private CaseResult diff(ContractCase contractCase, CapturedResponse rails, CapturedResponse spring) {
        return differ.diff(contractCase, rails, spring, new Allowlist(List.of()), YAML.createObjectNode(),
            "http://rails/accounts.csv", "http://spring/api/v1/accounts", List.of());
    }

    private static CapturedResponse csv(String body) {
        return new CapturedResponse(200, "text/csv", body, null);
    }
}
