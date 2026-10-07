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
