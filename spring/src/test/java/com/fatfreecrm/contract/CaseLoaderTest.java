package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaseLoaderTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void appliesDefaultsForOptionalFields() throws Exception {
        ContractCase parsed = parseOne("""
            - id: minimal
              path: /accounts
            """);
        assertEquals("GET", parsed.method());
        assertEquals("pending", parsed.status());
        assertEquals("anonymous", parsed.auth());
        assertEquals("", parsed.ticket());
        assertEquals("", parsed.description());
        assertTrue(parsed.params().isObject());
        assertTrue(parsed.params().isEmpty());
        assertTrue(parsed.normalize().isObject());
        assertTrue(parsed.normalize().isEmpty());
        assertNull(parsed.body());
        assertNull(parsed.expect());
    }

    @Test
    void normalizesMethodAndStatusCasing() throws Exception {
        ContractCase parsed = parseOne("""
            - id: cased
              path: /accounts
              method: post
              status: Enforced
            """);
        assertEquals("POST", parsed.method());
        assertEquals("enforced", parsed.status());
    }

    @Test
    void acceptsCasesWrapperAndTextualSideShorthand() throws Exception {
        List<ContractCase> cases = CaseLoader.parse(YAML.readTree("""
            cases:
              - id: shorthand
                rails: /legacy/accounts.json
                spring: /api/v2/accounts
            """));
        assertEquals(1, cases.size());
        ContractCase parsed = cases.getFirst();
        assertEquals("/legacy/accounts.json", parsed.rails().path());
        assertEquals(ContractCase.Target.RAILS, parsed.rails().target());
        assertEquals("/api/v2/accounts", parsed.spring().path());
        assertEquals(ContractCase.Target.SPRING, parsed.spring().target());
        assertEquals("/legacy/accounts", parsed.path());
    }

    @Test
    void sideTargetIsCaseInsensitiveAndSidePathFallsBackToDefault() throws Exception {
        ContractCase parsed = parseOne("""
            - id: targeted
              path: /users/me
              rails:
                target: Spring
            """);
        assertEquals(ContractCase.Target.SPRING, parsed.rails().target());
        assertEquals("/users/me.json", parsed.rails().path());
        assertEquals(ContractCase.Target.SPRING, parsed.spring().target());
        assertEquals("/api/v1/users/me", parsed.spring().path());
        assertEquals("/users/me", parsed.path());
    }

    @Test
    void preservesBodyParamsNormalizeAndCopiesExpectations() throws Exception {
        JsonNode root = YAML.readTree("""
            - id: full
              ticket: AB-266
              path: /accounts
              method: POST
              auth: alice
              description: creates an account
              params:
                page: 2
              body:
                account:
                  name: Acme
              normalize:
                ignore: ["/updated_at"]
              expect:
                status: 201
                json:
                  /account/name: Acme
            """);
        ContractCase parsed = CaseLoader.parse(root).getFirst();
        assertEquals("AB-266", parsed.ticket());
        assertEquals("alice", parsed.auth());
        assertEquals("creates an account", parsed.description());
        assertEquals(2, parsed.params().path("page").asInt());
        assertEquals("Acme", parsed.body().path("account").path("name").asText());
        assertEquals("/updated_at", parsed.normalize().path("ignore").get(0).asText());
        assertEquals(201, parsed.expect().path("status").asInt());
        assertEquals("Acme", parsed.expect().path("json").path("/account/name").asText());
        assertNotSame(root.get(0).get("expect"), parsed.expect());
        assertEquals(root.get(0).get("expect"), parsed.expect());
    }

    @Test
    void nullExpectationIsTreatedAsAbsent() throws Exception {
        assertNull(parseOne("""
            - id: null-expect
              path: /accounts
              expect: null
            """).expect());
    }

    @Test
    void rejectsMissingOrBlankId() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> parseOne("""
            - path: /accounts
            """));
        assertEquals("Missing required contract case field: id", missing.getMessage());
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class, () -> parseOne("""
            - id: "  "
              path: /accounts
            """));
        assertEquals("Missing required contract case field: id", blank.getMessage());
    }

    @Test
    void rejectsUnknownStatusAndTarget() {
        IllegalArgumentException status = assertThrows(IllegalArgumentException.class, () -> parseOne("""
            - id: bad-status
              path: /accounts
              status: draft
            """));
        assertEquals("Contract case status must be pending or enforced: draft", status.getMessage());
        assertThrows(IllegalArgumentException.class, () -> parseOne("""
            - id: bad-target
              path: /accounts
              spring:
                target: nginx
            """));
    }

    @Test
    void rejectsFilesWithoutACaseList() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> CaseLoader.parse(YAML.readTree("""
                id: lonely
                path: /accounts
                """)));
        assertEquals("Contract case file must contain a list of cases.", exception.getMessage());
    }

    @Test
    void validatesExpectationShape() {
        assertEquals("Contract case expect must be an object.",
            assertThrows(IllegalArgumentException.class, () -> parseOne("""
                - id: scalar-expect
                  path: /accounts
                  expect: 200
                """)).getMessage());
        assertEquals("Contract case expectation status must be an integer.",
            assertThrows(IllegalArgumentException.class, () -> parseOne("""
                - id: text-status
                  path: /accounts
                  expect:
                    status: "200"
                """)).getMessage());
        assertEquals("Contract case expectation json must map pointers to values.",
            assertThrows(IllegalArgumentException.class, () -> parseOne("""
                - id: list-json
                  path: /accounts
                  expect:
                    json: [1, 2]
                """)).getMessage());
        IllegalArgumentException pointer = assertThrows(IllegalArgumentException.class, () -> parseOne("""
            - id: bad-pointer
              path: /accounts
              expect:
                json:
                  id: 1
            """));
        assertEquals("Invalid expectation JSON pointer: id", pointer.getMessage());
    }

    private static ContractCase parseOne(String yaml) throws Exception {
        return CaseLoader.parse(YAML.readTree(yaml)).getFirst();
    }
}
