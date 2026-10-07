package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ReportWriter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReportWriter() {
    }

    public static void write(
        Path directory,
        String railsUrl,
        String springUrl,
        List<CaseResult> cases,
        Allowlist allowlist
    ) throws IOException {
        Files.createDirectories(directory);
        ObjectNode report = JSON.createObjectNode();
        report.put("generatedAt", Instant.now().toString());
        report.put("railsUrl", railsUrl);
        report.put("springUrl", springUrl);
        ObjectNode summary = report.putObject("summary");
        summary.put("total", cases.size());
        summary.put("clean", count(cases, CaseResult.Outcome.CLEAN));
        summary.put("diff", count(cases, CaseResult.Outcome.DIFF));
        summary.put("error", count(cases, CaseResult.Outcome.ERROR));
        summary.put("enforcedFailures", cases.stream().filter(CaseResult::enforcedFailure).count());
        ArrayNode caseNodes = report.putArray("cases");
        cases.forEach(result -> caseNodes.add(caseJson(result)));
        ArrayNode allowlistNodes = report.putArray("allowlist");
        allowlist.entries().forEach(entry -> {
            ObjectNode item = allowlistNodes.addObject();
            item.put("id", entry.id());
            item.put("kind", entry.kind());
            item.put("hits", allowlist.hits(entry.id()));
            item.put("stale", allowlist.hits(entry.id()) == 0);
        });
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("report.json").toFile(), report);
        String markdown = markdown(cases, allowlist);
        Files.writeString(directory.resolve("report.md"), markdown);
        System.out.print(markdown);
    }

    private static ObjectNode caseJson(CaseResult result) {
        ObjectNode item = JSON.createObjectNode();
        ContractCase contractCase = result.contractCase();
        item.put("id", contractCase.id());
        item.put("ticket", contractCase.ticket());
        item.put("status", contractCase.status());
        item.put("outcome", result.outcome().name());
        item.put("method", contractCase.method());
        item.put("auth", contractCase.auth());
        sideJson(item.putObject("rails"), result.railsUrl(), result.rails());
        sideJson(item.putObject("spring"), result.springUrl(), result.spring());
        putBodyPointer(item.path("rails"), contractCase.rails().bodyPointer());
        putBodyPointer(item.path("spring"), contractCase.spring().bodyPointer());
        ArrayNode notes = item.putArray("notes");
        result.notes().forEach(notes::add);
        if (result.error() != null) {
            item.put("error", result.error());
        }
        ArrayNode differences = item.putArray("differences");
        result.differences().forEach(difference -> {
            ObjectNode detail = differences.addObject();
            detail.put("pointer", difference.pointer());
            detail.put("kind", difference.kind().name());
            if (difference.side() == null) {
                putJson(detail, "rails", difference.railsValue());
                putJson(detail, "spring", difference.springValue());
            } else {
                detail.put("side", difference.side());
                putJson(detail, "actual", difference.railsValue());
                putJson(detail, "expected", difference.springValue());
            }
            ArrayNode applied = detail.putArray("allowedBy");
            difference.allowedBy().forEach(applied::add);
        });
        return item;
    }

    private static void putBodyPointer(JsonNode node, String bodyPointer) {
        if (bodyPointer != null && node instanceof ObjectNode object) {
            object.put("bodyPointer", bodyPointer);
        }
    }

    private static void sideJson(ObjectNode node, String url, CapturedResponse response) {
        if (url != null) {
            node.put("url", url);
        }
        if (response != null) {
            node.put("status", response.status());
            node.put("contentType", response.mediaType());
        }
    }

    private static void putJson(ObjectNode node, String field, JsonNode value) {
        if (value == null) {
            node.putNull(field);
        } else if (value.isMissingNode()) {
            node.put(field, "<missing>");
        } else {
            node.set(field, value);
        }
    }

    private static String markdown(List<CaseResult> cases, Allowlist allowlist) {
        StringBuilder text = new StringBuilder();
        long clean = count(cases, CaseResult.Outcome.CLEAN);
        long diff = count(cases, CaseResult.Outcome.DIFF);
        long errors = count(cases, CaseResult.Outcome.ERROR);
        long enforced = cases.stream().filter(CaseResult::enforcedFailure).count();
        text.append("Contract diff: **").append(cases.size()).append(" cases** — ")
            .append(clean).append(" clean, ").append(diff).append(" diff, ").append(errors)
            .append(" error, ").append(enforced).append(" enforced failures.\n\n");
        text.append("| Case | Pointers | Mode | Rails | Spring | Outcome | Diffs (open/allowed) | Ticket |\n")
            .append("|---|---|---|---|---|---|---:|---|\n");
        for (CaseResult result : cases) {
            long open = result.differences().stream().filter(difference -> !difference.allowed()).count();
            long allowed = result.differences().size() - open;
            text.append("| ").append(result.contractCase().id()).append(" | ")
                .append(pointerSummary(result.contractCase())).append(" | ")
                .append(result.contractCase().status()).append(" | ").append(sideSummary(result.rails()))
                .append(" | ").append(sideSummary(result.spring())).append(" | ").append(result.outcome())
                .append(" | ").append(open).append(" / ").append(allowed).append(" | ")
                .append(result.contractCase().ticket()).append(" |\n");
        }
        for (CaseResult result : cases) {
            if (result.outcome() == CaseResult.Outcome.CLEAN) {
                continue;
            }
            text.append("\n### ").append(result.contractCase().id()).append("\n");
            if (result.error() != null) {
                text.append("- Error: ").append(result.error()).append('\n');
            }
            result.notes().forEach(note -> text.append("- Note: ").append(note).append('\n'));
            int shown = Math.min(20, result.differences().size());
            for (int index = 0; index < shown; index++) {
                Difference difference = result.differences().get(index);
                text.append("- `").append(difference.kind()).append('`');
                if (difference.side() != null) {
                    text.append(" on ").append(difference.side());
                }
                text.append(" `").append(difference.pointer()).append("`: ");
                if (difference.side() == null) {
                    text.append("Rails `").append(truncate(stringify(difference.railsValue())))
                        .append("`, Spring `").append(truncate(stringify(difference.springValue()))).append('`');
                } else {
                    text.append("actual `").append(truncate(stringify(difference.railsValue())))
                        .append("`, expected `").append(truncate(stringify(difference.springValue()))).append('`');
                }
                if (!difference.allowedBy().isEmpty()) {
                    text.append(" (allowed by ").append(String.join(", ", difference.allowedBy())).append(')');
                }
                text.append('\n');
            }
            if (result.differences().size() > shown) {
                text.append("- ").append(result.differences().size() - shown).append(" more in report.json\n");
            }
        }
        text.append("\n### Allow-list\n");
        allowlist.entries().forEach(entry -> {
            List<String> appliedCases = cases.stream()
                .filter(result -> result.differences().stream()
                    .anyMatch(difference -> difference.allowedBy().contains(entry.id())))
                .map(result -> result.contractCase().id())
                .distinct()
                .toList();
            text.append("- `").append(entry.id()).append("` (").append(entry.kind()).append("): ")
                .append(allowlist.hits(entry.id())).append(" hits");
            if (allowlist.hits(entry.id()) == 0) {
                text.append(" — **stale**");
            } else {
                text.append(" — applied in ").append(String.join(", ", appliedCases));
                if (!entry.reason().isBlank()) {
                    text.append(": ").append(entry.reason());
                }
                if (!entry.reference().isBlank()) {
                    text.append(" (").append(entry.reference()).append(')');
                }
            }
            text.append('\n');
        });
        return text.toString();
    }

    private static String pointerSummary(ContractCase contractCase) {
        List<String> pointers = new ArrayList<>();
        if (contractCase.rails().bodyPointer() != null) {
            pointers.add("Rails: `" + contractCase.rails().bodyPointer() + "`");
        }
        if (contractCase.spring().bodyPointer() != null) {
            pointers.add("Spring: `" + contractCase.spring().bodyPointer() + "`");
        }
        return String.join("<br>", pointers);
    }

    private static String sideSummary(CapturedResponse response) {
        return response == null ? "—" : response.status() + " " + response.mediaType();
    }

    private static String stringify(JsonNode value) {
        return value == null ? "null" : value.isMissingNode() ? "<missing>" : value.toString();
    }

    private static String truncate(String value) {
        return value.length() > 180 ? value.substring(0, 177) + "..." : value;
    }

    private static long count(List<CaseResult> cases, CaseResult.Outcome outcome) {
        return cases.stream().filter(result -> result.outcome() == outcome).count();
    }
}
