package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonNormalizerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ContractDiffer differ = new ContractDiffer();

    @Test
    void ignoresPointersWithObjectAndArrayWildcards() throws Exception {
        JsonNode options = JSON.readTree("""
            {"ignore":["/records/*/updated_at"]}
            """);
        CaseResult result = compare(
            JSON.readTree("""
                {"records":[{"id":1,"updated_at":"left"},{"id":2,"updated_at":"old"}]}
                """),
            JSON.readTree("""
                {"records":[{"id":1,"updated_at":"right"},{"id":2,"updated_at":"new"}]}
                """),
            options
        );
        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
    }

    @Test
    void removingIgnoredArrayItemsDoesNotIgnoreShiftedSuccessors() throws Exception {
        JsonNode options = JSON.readTree("""
            {"ignore":["/items/0"]}
            """);
        CaseResult result = compare(
            JSON.readTree("""
                {"items":["a","b"]}
                """),
            JSON.readTree("""
                {"items":["x","c"]}
                """),
            options
        );

        assertEquals(CaseResult.Outcome.DIFF, result.outcome());
        assertEquals(1, result.differences().size());
        assertEquals("/items/0", result.differences().getFirst().pointer());
        assertEquals(JSON.readTree("\"b\""), result.differences().getFirst().railsValue());
        assertEquals(JSON.readTree("\"c\""), result.differences().getFirst().springValue());
    }

    @Test
    void ignoresTheEntireBodyWhenTheRootPointerIsIgnored() throws Exception {
        JsonNode options = JSON.readTree("""
            {"ignore":[""]}
            """);
        CaseResult result = compare(
            JSON.readTree("""
                {"value":"rails"}
                """),
            JSON.readTree("""
                {"value":"spring"}
                """),
            options
        );

        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
        assertTrue(result.differences().isEmpty());
    }

    @Test
    void sortsUnorderedArraysCanonicallyAndPairsByDeclaredKey() throws Exception {
        JsonNode options = JSON.readTree("""
            {"unorderedArrays":["/tags","/values",{"pointer":"/items","key":"/id"}]}
            """);
        CaseResult result = compare(
            JSON.readTree("""
                {"tags":["z","a"],"values":[{"b":2,"a":1},{"a":0}],
                 "items":[{"id":2,"name":"two"},{"id":1,"name":"one"}]}
                """),
            JSON.readTree("""
                {"tags":["a","z"],"values":[{"a":0},{"a":1,"b":2}],
                 "items":[{"id":1,"name":"one"},{"id":2,"name":"two"}]}
                """),
            options
        );
        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
        assertTrue(result.differences().isEmpty());
    }

    @Test
    void duplicateKeysSortByCanonicalIgnoreStrippedElements() throws Exception {
        JsonNode options = JSON.readTree("""
            {
              "unorderedArrays":[{"pointer":"/items","key":"/id"}],
              "ignore":["/items/*/ignored"]
            }
            """);
        CaseResult result = compare(
            JSON.readTree("""
                {"items":[
                  {"id":1,"ignored":"a","value":"one"},
                  {"id":1,"ignored":"z","value":"two"}
                ]}
                """),
            JSON.readTree("""
                {"items":[
                  {"id":1,"ignored":"z","value":"one"},
                  {"id":1,"ignored":"a","value":"two"}
                ]}
                """),
            options
        );
        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
        assertTrue(result.differences().isEmpty());
    }

    @Test
    void duplicateKeysInReversedOrderSortByCanonicalElements() throws Exception {
        JsonNode options = JSON.readTree("""
            {"unorderedArrays":[{"pointer":"/items","key":"/id"}]}
            """);
        CaseResult result = compare(
            JSON.readTree("""
                {"items":[{"id":1,"value":"a"},{"id":1,"value":"b"}]}
                """),
            JSON.readTree("""
                {"items":[{"id":1,"value":"b"},{"id":1,"value":"a"}]}
                """),
            options
        );
        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
        assertTrue(result.differences().isEmpty());
    }

    @Test
    void ignoreRulesSuppressMissingKeyDiagnosticsAtIgnoredAncestors() throws Exception {
        JsonNode railsJson = JSON.readTree("""
            {"items":[{"name":"x"}]}
            """);
        JsonNode springJson = JSON.readTree("""
            {"items":[{"name":"x"}]}
            """);
        JsonNode ignoredOptions = JSON.readTree("""
            {
              "unorderedArrays":[{"pointer":"/items","key":"/id"}],
              "ignore":["/items"]
            }
            """);
        CaseResult ignored = compare(railsJson, springJson, ignoredOptions);
        assertEquals(CaseResult.Outcome.CLEAN, ignored.outcome());
        assertTrue(ignored.differences().isEmpty());

        JsonNode visibleOptions = JSON.readTree("""
            {"unorderedArrays":[{"pointer":"/items","key":"/id"}]}
            """);
        CaseResult visible = compare(railsJson, springJson, visibleOptions);
        assertEquals(CaseResult.Outcome.DIFF, visible.outcome());
        assertEquals(2, visible.differences().stream()
            .filter(difference -> difference.kind() == Difference.Kind.MISSING_KEY).count());
    }

    @Test
    void ignoringArrayItemKeysSuppressesMissingKeyDiagnostics() throws Exception {
        JsonNode options = JSON.readTree("""
            {
              "unorderedArrays":[{"pointer":"/items","key":"/id"}],
              "ignore":["/items/*/id"]
            }
            """);
        JsonNode body = JSON.readTree("""
            {"items":[{"name":"x"},{"name":"y"}]}
            """);
        JsonNormalizer.NormalizationResult normalized =
            JsonNormalizer.normalizeWithDiagnostics(body, options, "rails");
        assertTrue(normalized.missingKeys().isEmpty());

        CaseResult result = compare(body, body, options);
        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
        assertTrue(result.differences().isEmpty());
    }

    @Test
    void ignoredReceivedKeyPointersSuppressMissingKeyDiagnosticsAfterSorting() throws Exception {
        JsonNode options = JSON.readTree("""
            {
              "unorderedArrays":[{"pointer":"/items","key":"/id"}],
              "ignore":["/items/0/id"]
            }
            """);
        JsonNode body = JSON.readTree("""
            {"items":[{"id":1,"name":"a"},{"id":2,"name":"b"}]}
            """);

        JsonNormalizer.NormalizationResult normalized =
            JsonNormalizer.normalizeWithDiagnostics(body, options, "rails");
        assertEquals("""
            {"items":[{"id":2,"name":"b"},{"name":"a"}]}
            """.trim(), normalized.json().toString());
        assertTrue(normalized.missingKeys().isEmpty());

        CaseResult result = compare(body, body, options);
        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
        assertTrue(result.differences().isEmpty());
    }

    @Test
    void sortsKeylessItemsLastAndReportsMissingKeysForBothSides() throws Exception {
        JsonNode options = JSON.readTree("""
            {"unorderedArrays":[{"pointer":"/items","key":"/id"}]}
            """);
        JsonNode railsJson = JSON.readTree("""
            {"items":[{"id":2,"name":"two"},{"name":"z"},{"id":1,"name":"one"},{"name":"a"}]}
            """);
        JsonNode springJson = JSON.readTree("""
            {"items":[{"name":"b"},{"id":2,"name":"two"},{"id":1,"name":"one"}]}
            """);

        JsonNormalizer.NormalizationResult normalizedRails =
            JsonNormalizer.normalizeWithDiagnostics(railsJson, options, "rails");
        assertEquals("""
            {"items":[{"id":1,"name":"one"},{"id":2,"name":"two"},{"name":"a"},{"name":"z"}]}
            """.trim(), normalizedRails.json().toString());
        assertEquals(List.of("/items/2", "/items/3"), normalizedRails.missingKeys().stream()
            .map(JsonNormalizer.MissingKey::pointer).toList());

        CaseResult result = compare(railsJson, springJson, options);
        List<Difference> missingKeys = result.differences().stream()
            .filter(difference -> difference.kind() == Difference.Kind.MISSING_KEY).toList();
        assertEquals(3, missingKeys.size());
        assertEquals("/items/2", missingKeys.get(0).pointer());
        assertEquals(JSON.readTree("""
            {"name":"a"}
            """), missingKeys.get(0).railsValue());
        assertNull(missingKeys.get(0).springValue());
        assertEquals("/items/3", missingKeys.get(1).pointer());
        assertEquals(JSON.readTree("""
            {"name":"z"}
            """), missingKeys.get(1).railsValue());
        assertEquals("/items/2", missingKeys.get(2).pointer());
        assertEquals(JSON.readTree("""
            {"name":"b"}
            """), missingKeys.get(2).springValue());
        assertTrue(missingKeys.stream().noneMatch(Difference::allowed));
    }

    @Test
    void comparesTimestampsAsInstants() throws Exception {
        JsonNode options = JSON.readTree("""
            {"timestamps":["/created_at"]}
            """);
        CaseResult result = compare(
            JSON.readTree("""
                {"created_at":"2026-01-01T09:00:00.000Z"}
                """),
            JSON.readTree("""
                {"created_at":"2026-01-01T10:00:00+01:00"}
                """),
            options
        );
        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());
    }

    @Test
    void renamesOnlyTheDeclaredSideAndPointer() throws Exception {
        JsonNode options = JSON.readTree("""
            {"rename":[{"side":"spring","from":"/createdAt","to":"/created_at"}]}
            """);
        CaseResult result = compare(
            JSON.readTree("""
                {"items":[{"created_at":"2026-01-01"}]}
                """),
            JSON.readTree("""
                {"items":[{"createdAt":"2026-01-01"}]}
                """),
            JSON.readTree("""
                {"rename":[{"side":"spring","from":"/items/*/createdAt","to":"/items/*/created_at"}]}
                """)
        );
        assertEquals(CaseResult.Outcome.CLEAN, result.outcome());

        CaseResult undeclared = compare(
            JSON.readTree("""
                {"created_at":"2026-01-01"}
                """),
            JSON.readTree("""
                {"createdAt":"2026-01-01"}
                """),
            JSON.createObjectNode()
        );
        assertEquals(CaseResult.Outcome.DIFF, undeclared.outcome());
    }

    private CaseResult compare(JsonNode railsJson, JsonNode springJson, JsonNode normalize) {
        ContractCase contractCase = new ContractCase("normalizer-test", "AB-266", "pending", "GET", "/test",
            new ContractCase.SideRequest("/test.json", ContractCase.Target.RAILS),
            new ContractCase.SideRequest("/api/v1/test", ContractCase.Target.SPRING),
            JSON.createObjectNode(), null, "anonymous", normalize, "");
        CapturedResponse rails = new CapturedResponse(200, "application/json", railsJson.toString(), railsJson);
        CapturedResponse spring = new CapturedResponse(200, "application/json", springJson.toString(), springJson);
        return differ.diff(contractCase, rails, spring, new Allowlist(List.of()), JSON.createObjectNode(),
            "http://rails/test.json", "http://spring/api/v1/test", List.of());
    }
}
