package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportWriterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void serializesErrorsWithoutResponsesAndCountsEnforcedFailures(@TempDir Path directory) throws Exception {
        CaseResult error = new CaseResult(contractCase("rails-down", "enforced"), CaseResult.Outcome.ERROR, null,
            null, null, null, List.of(), List.of(), "GET /users/sign_in failed: Connection refused");
        CaseResult clean = new CaseResult(contractCase("still-pending", "pending"), CaseResult.Outcome.CLEAN,
            "http://rails/accounts.json", "http://spring/api/v1/accounts", response(200), response(200), List.of(),
            List.of(), null);
        ReportWriter.write(directory, "http://rails", "http://spring", List.of(error, clean),
            new Allowlist(List.of()));

        JsonNode report = JSON.readTree(directory.resolve("report.json").toFile());
        Instant.parse(report.path("generatedAt").asText());
        assertEquals("http://rails", report.path("railsUrl").asText());
        assertEquals("http://spring", report.path("springUrl").asText());
        JsonNode summary = report.path("summary");
        assertEquals(2, summary.path("total").asInt());
        assertEquals(1, summary.path("clean").asInt());
        assertEquals(0, summary.path("diff").asInt());
        assertEquals(1, summary.path("error").asInt());
        assertEquals(1, summary.path("enforcedFailures").asInt());
        JsonNode errorCase = report.path("cases").get(0);
        assertEquals("rails-down", errorCase.path("id").asText());
        assertEquals("ERROR", errorCase.path("outcome").asText());
        assertEquals("enforced", errorCase.path("status").asText());
        assertEquals("GET /users/sign_in failed: Connection refused", errorCase.path("error").asText());
        assertTrue(errorCase.path("rails").isObject());
        assertTrue(errorCase.path("rails").isEmpty());
        assertTrue(errorCase.path("spring").isEmpty());
        assertTrue(errorCase.path("notes").isEmpty());
        assertTrue(errorCase.path("differences").isEmpty());
        assertFalse(report.path("cases").get(1).has("error"));
        assertTrue(report.path("allowlist").isEmpty());

        String markdown = Files.readString(directory.resolve("report.md"));
        assertTrue(markdown.startsWith("Contract diff: **2 cases** — 1 clean, 0 diff, 1 error, 1 enforced failures.\n"),
            markdown);
        assertTrue(markdown.contains("| rails-down | enforced | — | — | ERROR | 0 / 0 | AB-266 |\n"), markdown);
        assertTrue(markdown.contains(
            "| still-pending | pending | 200 application/json | 200 application/json | CLEAN | 0 / 0 | AB-266 |\n"),
            markdown);
        assertTrue(markdown.contains("\n### rails-down\n- Error: GET /users/sign_in failed: Connection refused\n"),
            markdown);
        assertFalse(markdown.contains("### still-pending"));
        assertTrue(markdown.endsWith("\n### Allow-list\n"), markdown);
    }

    @Test
    void serializesDifferencesAndNotesAndTruncatesLongMarkdownValues(@TempDir Path directory) throws Exception {
        AllowlistEntry entry = new AllowlistEntry("ignore-name", "*", "**", null, null, "pointer", "Expected rename",
            "AB-266", JSON.createObjectNode());
        Allowlist allowlist = new Allowlist(List.of(entry));
        allowlist.hit(entry);
        String longValue = "x".repeat(300);
        List<Difference> differences = new ArrayList<>();
        differences.add(new Difference("/name", Difference.Kind.VALUE, TextNode.valueOf("Rails"),
            TextNode.valueOf("Spring"), List.of("ignore-name")));
        differences.add(new Difference("/missing", Difference.Kind.MISSING_IN_SPRING, IntNode.valueOf(1),
            MissingNode.getInstance(), List.of()));
        differences.add(new Difference("", Difference.Kind.INVALID_JSON, null, null, List.of()));
        differences.add(Difference.expectation("spring", "/id", IntNode.valueOf(2), IntNode.valueOf(1)));
        differences.add(new Difference("/long", Difference.Kind.VALUE, TextNode.valueOf(longValue),
            TextNode.valueOf("short"), List.of()));
        for (int index = 0; index < 20; index++) {
            differences.add(new Difference("/items/" + index, Difference.Kind.EXTRA_IN_SPRING,
                MissingNode.getInstance(), IntNode.valueOf(index), List.of()));
        }
        CaseResult result = new CaseResult(contractCase("many-diffs", "pending"), CaseResult.Outcome.DIFF,
            "http://rails/accounts.json", "http://spring/api/v1/accounts", response(200),
            response(200, "application/problem+json"), List.of("spring auth unavailable (login returned 404)"),
            differences, null);
        ReportWriter.write(directory, "http://rails", "http://spring", List.of(result), allowlist);

        JsonNode item = JSON.readTree(directory.resolve("report.json").toFile()).path("cases").get(0);
        assertEquals("DIFF", item.path("outcome").asText());
        assertEquals("GET", item.path("method").asText());
        assertEquals("alice", item.path("auth").asText());
        assertEquals("AB-266", item.path("ticket").asText());
        assertEquals("http://rails/accounts.json", item.path("rails").path("url").asText());
        assertEquals(200, item.path("rails").path("status").asInt());
        assertEquals("application/problem+json", item.path("spring").path("contentType").asText());
        assertEquals("spring auth unavailable (login returned 404)", item.path("notes").get(0).asText());
        assertFalse(item.has("error"));
        JsonNode diffs = item.path("differences");
        assertEquals(25, diffs.size());
        assertEquals("VALUE", diffs.get(0).path("kind").asText());
        assertEquals("Rails", diffs.get(0).path("rails").asText());
        assertEquals("Spring", diffs.get(0).path("spring").asText());
        assertEquals("ignore-name", diffs.get(0).path("allowedBy").get(0).asText());
        assertEquals("<missing>", diffs.get(1).path("spring").asText());
        assertTrue(diffs.get(2).path("rails").isNull());
        assertTrue(diffs.get(2).path("spring").isNull());
        assertEquals("spring", diffs.get(3).path("side").asText());
        assertEquals(2, diffs.get(3).path("actual").asInt());
        assertEquals(1, diffs.get(3).path("expected").asInt());
        assertFalse(diffs.get(3).has("rails"));
        assertTrue(diffs.get(3).path("allowedBy").isEmpty());
        assertEquals(longValue, diffs.get(4).path("rails").asText());
        JsonNode allowlistItem = JSON.readTree(directory.resolve("report.json").toFile()).path("allowlist").get(0);
        assertEquals("ignore-name", allowlistItem.path("id").asText());
        assertEquals("pointer", allowlistItem.path("kind").asText());
        assertEquals(1, allowlistItem.path("hits").asInt());
        assertFalse(allowlistItem.path("stale").asBoolean());

        String markdown = Files.readString(directory.resolve("report.md"));
        assertTrue(markdown.contains("| many-diffs | pending | 200 application/json | 200 application/problem+json"
            + " | DIFF | 24 / 1 | AB-266 |\n"), markdown);
        assertTrue(markdown.contains("\n### many-diffs\n- Note: spring auth unavailable (login returned 404)\n"),
            markdown);
        assertTrue(markdown.contains(
            "- `VALUE` `/name`: Rails `\"Rails\"`, Spring `\"Spring\"` (allowed by ignore-name)\n"), markdown);
        assertTrue(markdown.contains("- `MISSING_IN_SPRING` `/missing`: Rails `1`, Spring `<missing>`\n"), markdown);
        assertTrue(markdown.contains("- `INVALID_JSON` ``: Rails `null`, Spring `null`\n"), markdown);
        assertTrue(markdown.contains("- `EXPECTATION` on spring `/id`: actual `2`, expected `1`\n"), markdown);
        assertTrue(markdown.contains("- `VALUE` `/long`: Rails `\"" + "x".repeat(176) + "...`, Spring `\"short\"`\n"),
            markdown);
        assertFalse(markdown.contains(longValue));
        assertTrue(markdown.contains("- `EXTRA_IN_SPRING` `/items/14`: Rails `<missing>`, Spring `14`\n"), markdown);
        assertFalse(markdown.contains("`/items/15`"));
        assertTrue(markdown.contains("- 5 more in report.json\n"), markdown);
        assertTrue(markdown.contains(
            "- `ignore-name` (pointer): 1 hits — applied in many-diffs: Expected rename (AB-266)\n"), markdown);
    }

    private static ContractCase contractCase(String id, String status) {
        return new ContractCase(id, "AB-266", status, "GET", "/accounts",
            new ContractCase.SideRequest("/accounts.json", ContractCase.Target.RAILS),
            new ContractCase.SideRequest("/api/v1/accounts", ContractCase.Target.SPRING),
            JSON.createObjectNode(), null, "alice", JSON.createObjectNode(), "");
    }

    private static CapturedResponse response(int status) {
        return response(status, "application/json");
    }

    private static CapturedResponse response(int status, String mediaType) {
        return new CapturedResponse(status, mediaType, "{}", JSON.createObjectNode());
    }
}
