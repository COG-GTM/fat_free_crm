package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ContractDiffer {
    private static final ObjectMapper JSON = new ObjectMapper();

    public CaseResult diff(
        ContractCase contractCase,
        CapturedResponse rails,
        CapturedResponse spring,
        Allowlist allowlist,
        JsonNode globalNormalize,
        String railsUrl,
        String springUrl,
        List<String> notes
    ) {
        return diff(contractCase, rails, spring, allowlist, globalNormalize, railsUrl, springUrl, notes, false);
    }

    public CaseResult diff(
        ContractCase contractCase,
        CapturedResponse rails,
        CapturedResponse spring,
        Allowlist allowlist,
        JsonNode globalNormalize,
        String railsUrl,
        String springUrl,
        List<String> notes,
        boolean springAuthenticated
    ) {
        return diff(contractCase, rails, spring, allowlist, globalNormalize, railsUrl, springUrl,
            notes, springAuthenticated, List.of());
    }

    public CaseResult diff(
        ContractCase contractCase,
        CapturedResponse rails,
        CapturedResponse spring,
        Allowlist allowlist,
        JsonNode globalNormalize,
        String railsUrl,
        String springUrl,
        List<String> notes,
        boolean springAuthenticated,
        List<Difference> dbDifferences
    ) {
        List<Difference> differences = new ArrayList<>(dbDifferences);
        List<String> resultNotes = new ArrayList<>(notes);
        boolean authenticated = springAuthenticated && !contractCase.auth().equals("anonymous");
        List<AllowlistEntry> matching = allowlist.matching(contractCase, authenticated);
        boolean problemBodyAllowed = matching.stream().anyMatch(entry ->
            entry.kind().equals("errorBody") && springBodyAllowed(entry, rails, spring));
        if (rails.status() != spring.status()) {
            JsonNode left = JSON.getNodeFactory().numberNode(rails.status());
            JsonNode right = JSON.getNodeFactory().numberNode(spring.status());
            add(differences, Difference.Kind.STATUS, "", left, right, matching, allowlist, rails, spring,
                contractCase, false);
        }
        if (!rails.mediaType().equals(spring.mediaType())) {
            List<String> applied = new ArrayList<>();
            if (problemBodyAllowed) {
                matching.stream().filter(entry -> entry.kind().equals("errorBody")).forEach(entry -> {
                    allowlist.hit(entry);
                    applied.add(entry.id());
                });
            }
            differences.add(new Difference("", Difference.Kind.CONTENT_TYPE, text(rails.mediaType()),
                text(spring.mediaType()), applied));
        }
        PointerBody railsBody = pointedBody(contractCase.rails(), rails, "rails", resultNotes, differences);
        PointerBody springBody = pointedBody(contractCase.spring(), spring, "spring", resultNotes, differences);
        boolean invalidJson = malformedJson(rails) || malformedJson(spring)
            || (rails.json() != null) != (spring.json() != null);
        if (invalidJson) {
            List<String> applied = new ArrayList<>();
            if (rails.json() == null && spring.json() != null && problemBodyAllowed) {
                matching.stream().filter(entry -> entry.kind().equals("errorBody")).forEach(entry -> {
                    allowlist.hit(entry);
                    applied.add(entry.id());
                });
            }
            differences.add(new Difference("", Difference.Kind.INVALID_JSON, rawBody(rails), rawBody(spring),
                applied));
        } else if (!railsBody.invalid() && !springBody.invalid()
            && railsBody.json() != null && springBody.json() != null) {
            JsonNode options = JsonNormalizer.merge(globalNormalize, contractCase.normalize());
            JsonNormalizer.NormalizationResult left = JsonNormalizer.normalizeWithDiagnostics(
                railsBody.json(), options, "rails");
            JsonNormalizer.NormalizationResult right = JsonNormalizer.normalizeWithDiagnostics(
                springBody.json(), options, "spring");
            matching.stream().filter(entry -> entry.kind().equals("yamlKeysRemoved")).forEach(entry -> {
                int removed = JsonNormalizer.removeYamlKeys(left.json(), entry.pointer(),
                    Pattern.compile(entry.keyPattern()));
                if (removed > 0) {
                    allowlist.hit(entry);
                }
            });
            left.missingKeys().forEach(missing ->
                add(differences, Difference.Kind.MISSING_KEY, missing.pointer(), missing.value(), null, matching,
                    allowlist, rails, spring, contractCase, problemBodyAllowed));
            right.missingKeys().forEach(missing ->
                add(differences, Difference.Kind.MISSING_KEY, missing.pointer(), null, missing.value(), matching,
                    allowlist, rails, spring, contractCase, problemBodyAllowed));
            if (!JsonNormalizer.ignored("", options)) {
                compare(left.json(), right.json(), "", differences, matching, allowlist, rails, spring, contractCase,
                    problemBodyAllowed, options);
            }
        }
        if (problemBodyAllowed) {
            validateProblemBody(spring, differences, matching, allowlist);
        }
        addExpectations(contractCase.expect(), rails, "rails-side", differences);
        addExpectations(contractCase.expect(), spring, "spring-side", differences);
        boolean clean = differences.stream().allMatch(Difference::allowed);
        return new CaseResult(contractCase, clean ? CaseResult.Outcome.CLEAN : CaseResult.Outcome.DIFF,
            railsUrl, springUrl, rails, spring, resultNotes, differences, null);
    }

    private static PointerBody pointedBody(
        ContractCase.SideRequest request,
        CapturedResponse response,
        String side,
        List<String> notes,
        List<Difference> differences
    ) {
        String pointer = request.bodyPointer();
        if (pointer == null) {
            return new PointerBody(response.json(), false);
        }
        if (response.status() < 200 || response.status() >= 300) {
            notes.add(side + " bodyPointer " + pointer + " ignored for HTTP " + response.status());
            return new PointerBody(response.json(), false);
        }
        if (response.json() == null) {
            notes.add(side + " bodyPointer " + pointer + " not applied because the response has no JSON");
            return new PointerBody(null, false);
        }
        JsonNode selected = response.json().at(pointer);
        if (selected.isMissingNode()) {
            String message = "JSON Pointer " + pointer + " is missing from the 2xx JSON response";
            notes.add(side + " bodyPointer " + pointer + " missing from 2xx JSON response");
            differences.add(new Difference(pointer, Difference.Kind.INVALID_JSON,
                side.equals("rails") ? TextNode.valueOf(message) : null,
                side.equals("spring") ? TextNode.valueOf(message) : null, List.of()));
            return new PointerBody(null, true);
        }
        notes.add(side + " bodyPointer " + pointer + " applied");
        return new PointerBody(selected, false);
    }

    private static void addExpectations(
        JsonNode expectation,
        CapturedResponse response,
        String side,
        List<Difference> differences
    ) {
        if (expectation == null) {
            return;
        }
        JsonNode expectedStatus = expectation.get("status");
        if (expectedStatus != null && response.status() != expectedStatus.asInt()) {
            differences.add(Difference.expectation(side, "",
                JSON.getNodeFactory().numberNode(response.status()), expectedStatus));
        }
        JsonNode expectedJson = expectation.get("json");
        if (expectedJson != null) {
            expectedJson.properties().forEach(entry -> {
                JsonNode actual = response.json() == null ? MissingNode.getInstance()
                    : response.json().at(entry.getKey());
                if (actual.isMissingNode() || !actual.equals(entry.getValue())) {
                    differences.add(Difference.expectation(side, entry.getKey(), actual, entry.getValue()));
                }
            });
        }
    }

    private void compare(
        JsonNode railsValue,
        JsonNode springValue,
        String pointer,
        List<Difference> differences,
        List<AllowlistEntry> matching,
        Allowlist allowlist,
        CapturedResponse rails,
        CapturedResponse spring,
        ContractCase contractCase,
        boolean problemBodyAllowed,
        JsonNode normalize
    ) {
        if (timestampEqual(railsValue, springValue, pointer, normalize)) {
            return;
        }
        if (railsValue == null || railsValue.isMissingNode()) {
            add(differences, Difference.Kind.EXTRA_IN_SPRING, pointer, null, springValue, matching, allowlist, rails,
                spring, contractCase, problemBodyAllowed);
            return;
        }
        if (springValue == null || springValue.isMissingNode()) {
            add(differences, Difference.Kind.MISSING_IN_SPRING, pointer, railsValue, null, matching, allowlist, rails,
                spring, contractCase, problemBodyAllowed);
            return;
        }
        if (railsValue.getNodeType() != springValue.getNodeType()) {
            add(differences, Difference.Kind.TYPE, pointer, railsValue, springValue, matching, allowlist, rails,
                spring, contractCase, problemBodyAllowed);
            return;
        }
        if (railsValue.isObject()) {
            List<String> names = new ArrayList<>();
            railsValue.fieldNames().forEachRemaining(names::add);
            springValue.fieldNames().forEachRemaining(name -> {
                if (!names.contains(name)) {
                    names.add(name);
                }
            });
            for (String name : names) {
                compare(railsValue.get(name), springValue.get(name), pointer + "/" + escape(name), differences,
                    matching, allowlist, rails, spring, contractCase, problemBodyAllowed, normalize);
            }
        } else if (railsValue.isArray()) {
            int length = Math.max(railsValue.size(), springValue.size());
            for (int index = 0; index < length; index++) {
                compare(index < railsValue.size() ? railsValue.get(index) : null,
                    index < springValue.size() ? springValue.get(index) : null, pointer + "/" + index,
                    differences, matching, allowlist, rails, spring, contractCase, problemBodyAllowed, normalize);
            }
        } else if (!railsValue.equals(springValue)) {
            add(differences, Difference.Kind.VALUE, pointer, railsValue, springValue, matching, allowlist, rails,
                spring, contractCase, problemBodyAllowed);
        }
    }

    private void add(
        List<Difference> differences,
        Difference.Kind kind,
        String pointer,
        JsonNode railsValue,
        JsonNode springValue,
        List<AllowlistEntry> matching,
        Allowlist allowlist,
        CapturedResponse rails,
        CapturedResponse spring,
        ContractCase contractCase,
        boolean problemBodyAllowed
    ) {
        List<String> allowedBy = new ArrayList<>();
        for (AllowlistEntry entry : matching) {
            boolean allowed = false;
            if (entry.kind().equals("status") && kind == Difference.Kind.STATUS) {
                allowed = railsValue.asInt(-1) == entry.definition().path("status").path("rails")
                    .asInt(Integer.MIN_VALUE)
                    && springValue.asInt(-1) == entry.definition().path("status").path("spring")
                    .asInt(Integer.MIN_VALUE);
            } else if (entry.kind().equals("pointer") && pointerMatches(entry.pointer(), pointer)) {
                String rule = entry.definition().path("rule").asText();
                allowed = rule.equals("ignore") || equalsAfter(entry, railsValue, springValue);
            } else if (entry.kind().equals("errorBody") && problemBodyAllowed
                && (kind == Difference.Kind.CONTENT_TYPE || isBodyDifference(kind))) {
                allowed = true;
            }
            if (allowed) {
                allowlist.hit(entry);
                allowedBy.add(entry.id());
            }
        }
        differences.add(new Difference(pointer, kind, railsValue, springValue, allowedBy));
    }

    private static boolean springBodyAllowed(AllowlistEntry entry, CapturedResponse rails, CapturedResponse spring) {
        return rails.status() >= 400 && spring.status() >= 400
            && spring.mediaType().equals("application/problem+json");
    }

    private static void validateProblemBody(
        CapturedResponse spring,
        List<Difference> differences,
        List<AllowlistEntry> matching,
        Allowlist allowlist
    ) {
        JsonNode problem = spring.json();
        if (problem == null || !problem.path("title").isTextual() || problem.path("title").asText().isBlank()) {
            differences.add(new Difference("/title", Difference.Kind.VALUE, null, problem == null ? null
                : problem.get("title"), List.of()));
        }
        if (problem == null || !problem.path("status").canConvertToInt()
            || problem.path("status").asInt() != spring.status()) {
            differences.add(new Difference("/status", Difference.Kind.VALUE,
                JSON.getNodeFactory().numberNode(spring.status()), problem == null ? null : problem.get("status"),
                List.of()));
        }
    }

    private static boolean isBodyDifference(Difference.Kind kind) {
        return kind != Difference.Kind.STATUS && kind != Difference.Kind.CONTENT_TYPE;
    }

    private static boolean equalsAfter(AllowlistEntry entry, JsonNode left, JsonNode right) {
        JsonNode definition = entry.definition();
        if (definition.path("rule").asText().equals("ignore")) {
            return true;
        }
        String transform = definition.path("transform").asText("string");
        String leftValue = transform(left, transform);
        String rightValue = transform(right, transform);
        return leftValue != null && leftValue.equals(rightValue);
    }

    private static String transform(JsonNode value, String transform) {
        if (value == null || value.isNull()) {
            return "null";
        }
        String text = value.isTextual() ? value.asText() : value.toString();
        return switch (transform) {
            case "instant" -> {
                try {
                    yield Instant.parse(text).toString();
                } catch (RuntimeException exception) {
                    yield null;
                }
            }
            case "trim" -> text.trim();
            case "lowercase" -> text.toLowerCase(Locale.ROOT);
            case "number" -> {
                try {
                    yield new BigDecimal(text).stripTrailingZeros().toPlainString();
                } catch (NumberFormatException exception) {
                    yield null;
                }
            }
            case "string" -> text;
            default -> null;
        };
    }

    private static boolean timestampEqual(JsonNode left, JsonNode right, String pointer, JsonNode normalize) {
        if (left == null || right == null || !left.isTextual() || !right.isTextual()) {
            return false;
        }
        for (JsonNode pattern : normalize.path("timestamps")) {
            if (pointerMatches(pattern.asText(), pointer)) {
                try {
                    return Instant.parse(left.asText()).equals(Instant.parse(right.asText()));
                } catch (RuntimeException exception) {
                    return false;
                }
            }
        }
        return false;
    }

    private static JsonNode text(String value) {
        return JSON.getNodeFactory().textNode(value);
    }

    private static boolean malformedJson(CapturedResponse response) {
        String mediaType = response.mediaType();
        boolean jsonMediaType = mediaType.equals("application/json") || mediaType.endsWith("+json");
        return jsonMediaType && !response.rawBody().isBlank() && response.json() == null;
    }

    private static JsonNode rawBody(CapturedResponse response) {
        String body = response.rawBody();
        return text(body.substring(0, Math.min(200, body.length())));
    }

    private static boolean pointerMatches(String pattern, String pointer) {
        String[] expected = pattern.split("/", -1);
        String[] actual = pointer.split("/", -1);
        if (expected.length != actual.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (!expected[index].equals("*") && !expected[index].equals(actual[index])) {
                return false;
            }
        }
        return true;
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private record PointerBody(JsonNode json, boolean invalid) {
    }
}
