package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaseLoaderValidationTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void acceptsCasesWrapperAndRejectsNonListRoots() throws Exception {
        List<ContractCase> wrapped = CaseLoader.parse(YAML.readTree("""
            cases:
              - id: wrapped
                path: /accounts
            """));
        assertEquals(1, wrapped.size());
        assertEquals("wrapped", wrapped.getFirst().id());

        assertMessage("Contract case file must contain a list of cases.", "id: not-a-list\n");
        assertMessage("Contract case file must contain a list of cases.", "cases:\n  id: nested-object\n");
    }

    @Test
    void requiresANonBlankIdAndARecognisedStatus() throws Exception {
        assertMessage("Missing required contract case field: id", "- path: /accounts\n");
        assertMessage("Missing required contract case field: id", "- id: '   '\n  path: /accounts\n");
        assertMessage("Contract case status must be pending or enforced: flaky",
            "- id: flaky\n  path: /accounts\n  status: flaky\n");

        ContractCase enforced = CaseLoader.parse(YAML.readTree("- id: shout\n  path: /accounts\n  status: ENFORCED\n"))
            .getFirst();
        assertEquals("enforced", enforced.status());
    }

    @Test
    void appliesDefaultsForEveryOptionalField() throws Exception {
        ContractCase minimal = CaseLoader.parse(YAML.readTree("- id: minimal\n  path: /accounts\n")).getFirst();
        assertEquals("GET", minimal.method());
        assertEquals("pending", minimal.status());
        assertEquals("anonymous", minimal.auth());
        assertEquals("", minimal.ticket());
        assertEquals("", minimal.description());
        assertTrue(minimal.params().isObject());
        assertTrue(minimal.params().isEmpty());
        assertTrue(minimal.normalize().isObject());
        assertTrue(minimal.normalize().isEmpty());
        assertNull(minimal.body());
        assertNull(minimal.expect());
    }

    @Test
    void upperCasesTheMethodAndKeepsParamsBodyAndNormalizeVerbatim() throws Exception {
        ContractCase create = CaseLoader.parse(YAML.readTree("""
            - id: create
              ticket: AB-270
              path: /accounts
              method: post
              auth: alice
              description: Creates an account
              params:
                page: 2
                tags: [a, b]
              body:
                name: New account
              normalize:
                ignore: ["/id"]
            """)).getFirst();
        assertEquals("POST", create.method());
        assertEquals("AB-270", create.ticket());
        assertEquals("alice", create.auth());
        assertEquals("Creates an account", create.description());
        assertEquals(2, create.params().path("page").asInt());
        assertEquals(2, create.params().path("tags").size());
        assertEquals("New account", create.body().path("name").asText());
        assertEquals("/id", create.normalize().path("ignore").get(0).asText());
    }

    @Test
    void derivesLogicalPathAndTargetsFromSideOverrides() throws Exception {
        List<ContractCase> cases = CaseLoader.parse(YAML.readTree("""
            - id: textual-sides
              rails: /accounts/101.json
              spring: /api/v1/accounts/101
            - id: targeted-sides
              path: /users/me
              rails:
                path: /api/v1/users/me
                target: spring
              spring:
                target: Spring
            - id: partial-override
              path: /accounts
              rails:
                target: rails
            """));
        ContractCase textual = cases.get(0);
        assertEquals("/accounts/101", textual.path());
        assertEquals("/accounts/101.json", textual.rails().path());
        assertEquals(ContractCase.Target.RAILS, textual.rails().target());
        assertEquals("/api/v1/accounts/101", textual.spring().path());
        assertEquals(ContractCase.Target.SPRING, textual.spring().target());

        ContractCase targeted = cases.get(1);
        assertEquals("/users/me", targeted.path());
        assertEquals("/api/v1/users/me", targeted.rails().path());
        assertEquals(ContractCase.Target.SPRING, targeted.rails().target());
        assertEquals("/api/v1/users/me", targeted.spring().path());
        assertEquals(ContractCase.Target.SPRING, targeted.spring().target());

        ContractCase partial = cases.get(2);
        assertEquals("/accounts.json", partial.rails().path());
        assertEquals(ContractCase.Target.RAILS, partial.rails().target());

        assertThrows(IllegalArgumentException.class, () -> CaseLoader.parse(YAML.readTree("""
            - id: bad-target
              path: /accounts
              spring:
                target: mainframe
            """)));
    }

    @Test
    void validatesExpectationShapeAndPointers() throws Exception {
        assertMessage("Contract case expect must be an object.", "- id: x\n  path: /a\n  expect: [1]\n");
        assertMessage("Contract case expectation status must be an integer.",
            "- id: x\n  path: /a\n  expect:\n    status: '200'\n");
        assertMessage("Contract case expectation status must be an integer.",
            "- id: x\n  path: /a\n  expect:\n    status: 200.5\n");
        assertMessage("Contract case expectation json must map pointers to values.",
            "- id: x\n  path: /a\n  expect:\n    json: [1]\n");
        assertMessage("Invalid expectation JSON pointer: name",
            "- id: x\n  path: /a\n  expect:\n    json:\n      name: alice\n");

        ContractCase explicitNull = CaseLoader.parse(YAML.readTree("- id: x\n  path: /a\n  expect: ~\n")).getFirst();
        assertNull(explicitNull.expect());
    }

    @Test
    void expectationsAreDetachedCopiesOfTheSourceDocument() throws Exception {
        JsonNode document = YAML.readTree("""
            - id: copy
              path: /accounts
              expect:
                status: 200
                json:
                  /id: 1
            """);
        ContractCase parsed = CaseLoader.parse(document).getFirst();
        ((ObjectNode) document.get(0).get("expect")).put("status", 500);
        assertEquals(200, parsed.expect().path("status").asInt());
        assertEquals(1, parsed.expect().path("json").path("/id").asInt());
    }

    private static void assertMessage(String expected, String yaml) throws Exception {
        JsonNode root = YAML.readTree(yaml);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> CaseLoader.parse(root));
        assertEquals(expected, error.getMessage());
    }
}
