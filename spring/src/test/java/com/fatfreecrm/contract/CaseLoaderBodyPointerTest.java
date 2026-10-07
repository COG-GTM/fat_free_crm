package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

class CaseLoaderBodyPointerTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void parsesBodyPointerAndKeepsSideDefaults() throws Exception {
        ContractCase contractCase = CaseLoader.parse(YAML.readTree("""
            - id: body-pointer
              path: /accounts
              spring:
                bodyPointer: /items
            """)).getFirst();

        assertEquals("/accounts.json", contractCase.rails().path());
        assertEquals("/api/v1/accounts", contractCase.spring().path());
        assertEquals("/items", contractCase.spring().bodyPointer());
    }

    @Test
    void rejectsBodyPointerThatDoesNotStartWithSlash() throws Exception {
        var yaml = YAML.readTree("""
            - id: invalid-body-pointer
              path: /accounts
              spring:
                bodyPointer: items
            """);

        assertThrows(IllegalArgumentException.class, () -> CaseLoader.parse(yaml));
    }

    @Test
    void rejectsMalformedJsonPointer() throws Exception {
        var yaml = YAML.readTree("""
            - id: malformed-body-pointer
              path: /accounts
              spring:
                bodyPointer: /items~2
            """);

        assertThrows(IllegalArgumentException.class, () -> CaseLoader.parse(yaml));
    }
}
