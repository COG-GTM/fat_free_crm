package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportWriterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LONG_NAME = "a".repeat(200);

    @TempDir
    Path directory;

    @Test
    void writesSummaryCaseDetailsAndAllowlistStateToJsonAndMarkdown() throws Exception {
        AllowlistEntry applied = new AllowlistEntry("applied", "*", "**", null, null, "pointer",
            "Names differ in case", "docs/ref.md", JSON.createObjectNode());
        AllowlistEntry unused = new AllowlistEntry("unused", "*", "**", null, null, "status", "", "",
            JSON.createObjectNode());
        Allowlist allowlist = new Allowlist(List.of(applied, unused));
        allowlist.hit(applied);

        CaseResult clean = new CaseResult(contractCase("clean-case", "enforced"), CaseResult.Outcome.CLEAN,
            "http://rails/accounts.json", "http://spring/api/v1/accounts", response(200), response(200),
            List.of(), List.of(), null);
        CaseResult diff = new CaseResult(contractCase("diff-case", "enforced"), CaseResult.Outcome.DIFF,
            "http://rails/accounts/1.json", "http://spring/api/v1/accounts/1", response(200), response(200),
            List.of(), differences(), null);
        CaseResult error = new CaseResult(contractCase("error-case", "pending"), CaseResult.Outcome.ERROR,
            null, "http://spring/api/v1/contacts", null, response(500),
            List.of("rails auth unavailable (CSRF token missing)"), List.of(), "Rails request failed");

        ReportWriter.write(directory, "http://rails", "http://spring", List.of(clean, diff, error), allowlist);

        JsonNode report = JSON.readTree(directory.resolve("report.json").toFile());
        assertEquals("http://rails", report.path("railsUrl").asText());
        assertEquals("http://spring", report.path("springUrl").asText());
        JsonNode summary = report.path("summary");
        assertEquals(3, summary.path("total").asInt());
        assertEquals(1, summary.path("clean").asInt());
        assertEquals(1, summary.path("diff").asInt());
        assertEquals(1, summary.path("error").asInt());
        assertEquals(1, summary.path("enforcedFailures").asInt());

        JsonNode diffCase = report.path("cases").get(1);
        assertEquals("diff-case", diffCase.path("id").asText());
        assertEquals("DIFF", diffCase.path("outcome").asText());
        assertEquals("AB-266", diffCase.path("ticket").asText());
        assertEquals("GET", diffCase.path("method").asText());
        assertEquals("admin", diffCase.path("auth").asText());
        assertEquals("http://rails/accounts/1.json", diffCase.path("rails").path("url").asText());
        assertEquals(200, diffCase.path("rails").path("status").asInt());
        assertEquals("application/json", diffCase.path("spring").path("contentType").asText());
        assertFalse(diffCase.has("error"));
        assertEquals(22, diffCase.path("differences").size());

        JsonNode valueDifference = diffCase.path("differences").get(0);
        assertEquals("/name", valueDifference.path("pointer").asText());
        assertEquals("VALUE", valueDifference.path("kind").asText());
        assertEquals(LONG_NAME, valueDifference.path("rails").asText());
        assertEquals("Alice", valueDifference.path("spring").asText());
        assertEquals("applied", valueDifference.path("allowedBy").get(0).asText());
        assertFalse(valueDifference.has("side"));

        JsonNode expectation = diffCase.path("differences").get(1);
        assertEquals("spring", expectation.path("side").asText());
        assertEquals("<missing>", expectation.path("actual").asText());
        assertEquals(1, expectation.path("expected").asInt());
        assertFalse(expectation.has("rails"));

        JsonNode missingInSpring = diffCase.path("differences").get(2);
        assertEquals("MISSING_IN_SPRING", missingInSpring.path("kind").asText());
        assertEquals("legacy", missingInSpring.path("rails").asText());
        assertTrue(missingInSpring.path("spring").isNull());
        assertTrue(missingInSpring.path("allowedBy").isEmpty());

        JsonNode errorCase = report.path("cases").get(2);
        assertEquals("Rails request failed", errorCase.path("error").asText());
        assertEquals("rails auth unavailable (CSRF token missing)", errorCase.path("notes").get(0).asText());
        assertTrue(errorCase.path("rails").isEmpty());
        assertEquals(500, errorCase.path("spring").path("status").asInt());

        JsonNode allowlistReport = report.path("allowlist");
        assertEquals("applied", allowlistReport.get(0).path("id").asText());
        assertEquals(1, allowlistReport.get(0).path("hits").asInt());
        assertFalse(allowlistReport.get(0).path("stale").asBoolean());
        assertEquals("unused", allowlistReport.get(1).path("id").asText());
        assertEquals(0, allowlistReport.get(1).path("hits").asInt());
        assertTrue(allowlistReport.get(1).path("stale").asBoolean());

        String markdown = Files.readString(directory.resolve("report.md"));
        assertTrue(markdown.startsWith(
            "Contract diff: **3 cases** — 1 clean, 1 diff, 1 error, 1 enforced failures.\n\n"), markdown);
        assertTrue(markdown.contains(
            "| clean-case | enforced | 200 application/json | 200 application/json | CLEAN | 0 / 0 | AB-266 |\n"),
            markdown);
        assertTrue(markdown.contains("| diff-case | enforced | 200 application/json | 200 application/json | DIFF | "
            + "21 / 1 | AB-266 |\n"), markdown);
        assertTrue(markdown.contains("| error-case | pending | — | 500 application/json | ERROR | 0 / 0 | AB-266 |\n"),
            markdown);
        assertFalse(markdown.contains("### clean-case"), markdown);
        assertTrue(markdown.contains("### diff-case\n- `VALUE` `/name`: Rails `\"" + "a".repeat(176)
            + "...`, Spring `\"Alice\"` (allowed by applied)\n"), markdown);
        assertFalse(markdown.contains("a".repeat(177)), markdown);
        assertTrue(markdown.contains("- `EXPECTATION` on spring `/id`: actual `<missing>`, expected `1`\n"), markdown);
        assertTrue(markdown.contains("- `MISSING_IN_SPRING` `/legacy`: Rails `\"legacy\"`, Spring `null`\n"
            + "- `VALUE` `/field0`: Rails `0`, Spring `1`\n"), markdown);
        assertTrue(markdown.contains("- 2 more in report.json\n"), markdown);
        assertTrue(markdown.contains("### error-case\n- Error: Rails request failed\n"
            + "- Note: rails auth unavailable (CSRF token missing)\n"), markdown);
        assertTrue(markdown.contains("### Allow-list\n"
            + "- `applied` (pointer): 1 hits — applied in diff-case: Names differ in case (docs/ref.md)\n"
            + "- `unused` (status): 0 hits — **stale**\n"), markdown);
    }

    private static List<Difference> differences() {
        List<Difference> differences = new ArrayList<>();
        differences.add(new Difference("/name", Difference.Kind.VALUE, JSON.getNodeFactory().textNode(LONG_NAME),
            JSON.getNodeFactory().textNode("Alice"), List.of("applied")));
        differences.add(Difference.expectation("spring", "/id", MissingNode.getInstance(),
            JSON.getNodeFactory().numberNode(1)));
        differences.add(new Difference("/legacy", Difference.Kind.MISSING_IN_SPRING,
            JSON.getNodeFactory().textNode("legacy"), null, List.of()));
        for (int index = 0; index < 19; index++) {
            differences.add(new Difference("/field" + index, Difference.Kind.VALUE,
                JSON.getNodeFactory().numberNode(index), JSON.getNodeFactory().numberNode(index + 1), List.of()));
        }
        return differences;
    }

    private static ContractCase contractCase(String id, String status) {
        return new ContractCase(id, "AB-266", status, "GET", "/accounts",
            new ContractCase.SideRequest("/accounts.json", ContractCase.Target.RAILS),
            new ContractCase.SideRequest("/api/v1/accounts", ContractCase.Target.SPRING),
            JSON.createObjectNode(), null, "admin", JSON.createObjectNode(), "");
    }

    private static CapturedResponse response(int status) {
        return new CapturedResponse(status, "application/json", "{}", JSON.createObjectNode());
    }
}
