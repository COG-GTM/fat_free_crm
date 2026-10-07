package com.fatfreecrm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

/**
 * The published document must be the frozen Rails contract field-for-field, not just the same path
 * and schema names: operations, response codes, security scheme and error responses all pin the Rails
 * JSON/XML behaviour recorded in docs/migration/openapi.yaml.
 */
class OpenApiContractParityTest extends AbstractPostgresIntegrationTest {

    private static final Set<String> HTTP_METHODS =
        Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode published;
    private JsonNode frozen;

    @BeforeEach
    void loadDocuments() throws Exception {
        byte[] publishedBytes = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        published = objectMapper.readTree(publishedBytes);
        OpenAPI frozenContract = Yaml31.mapper().readValue(
            new ClassPathResource("static/openapi.yaml").getInputStream(), OpenAPI.class
        );
        frozen = Json31.mapper().valueToTree(frozenContract);
    }

    @Test
    void publishedDocumentEqualsTheFrozenContract() {
        assertThat(canonical(published)).isEqualTo(canonical(frozen));
    }

    @Test
    void everyOperationAndResponseCodeIsPreserved() {
        List<String> publishedOperations = operations(published);
        List<String> frozenOperations = operations(frozen);

        assertThat(publishedOperations).containsExactlyInAnyOrderElementsOf(frozenOperations);
        assertThat(frozenOperations).contains(
            "GET /accounts -> 200,401", "GET /accounts/{id} -> 200,404,401", "PUT /home/timezone -> 200"
        );
    }

    @Test
    void railsAuthenticationAndErrorContractIsPublished() {
        assertThat(published.path("security").get(0).has("cookieAuth")).isTrue();
        JsonNode cookieAuth = published.path("components").path("securitySchemes").path("cookieAuth");
        assertThat(cookieAuth.path("type").asText()).isEqualTo("apiKey");
        assertThat(cookieAuth.path("in").asText()).isEqualTo("cookie");
        assertThat(cookieAuth.path("name").asText()).isEqualTo("_ffcrm_session");

        JsonNode responses = published.path("components").path("responses");
        assertThat(responses.path("AccessDenied").path("description").asText())
            .contains("returns HTTP 401 (not 403)")
            .contains("You are not authorized to take this action.");
        assertThat(responses.path("NotFound").path("content").has("text/plain")).isTrue();
        assertThat(responses.path("ValidationErrors").path("content").path("application/json")
            .path("schema").path("additionalProperties").path("type").asText()).isEqualTo("array");
    }

    @Test
    void springdocDoesNotAddItsOwnServersOrPaths() {
        assertThat(published.path("servers")).hasSize(1);
        assertThat(published.path("servers").get(0).path("url").asText()).isEqualTo("/");
        assertThat(published.path("paths").has("/actuator/health")).isFalse();
        assertThat(published.path("paths").has("/v3/api-docs")).isFalse();
        assertThat(published.path("paths").has("/error")).isFalse();
    }

    private static List<String> operations(JsonNode document) {
        List<String> operations = new ArrayList<>();
        document.path("paths").properties().forEach(path ->
            path.getValue().properties().stream()
                .filter(operation -> HTTP_METHODS.contains(operation.getKey()))
                .forEach(operation -> {
                    List<String> codes = new ArrayList<>();
                    operation.getValue().path("responses").fieldNames().forEachRemaining(codes::add);
                    operations.add(operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey()
                        + " -> " + String.join(",", codes));
                })
        );
        return operations;
    }

    /**
     * swagger-core materialises numeric bounds as BigDecimal (200.0) while springdoc writes them as
     * integers (200); both mean the same contract, so numbers are compared by value.
     */
    private static JsonNode canonical(JsonNode node) {
        if (node.isNumber()) {
            return TextNode.valueOf(node.decimalValue().stripTrailingZeros().toPlainString());
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(field -> copy.set(field.getKey(), canonical(field.getValue())));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> copy.add(canonical(element)));
            return copy;
        }
        return node;
    }
}
