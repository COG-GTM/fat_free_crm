package com.fatfreecrm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

class OpenApiContractTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void publishedDocumentUsesTheFrozenContract() throws Exception {
        byte[] publishedBytes = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        JsonNode published = objectMapper.readTree(publishedBytes);
        OpenAPI frozen = Yaml31.mapper().readValue(
            new ClassPathResource("static/openapi.yaml").getInputStream(),
            OpenAPI.class
        );

        assertThat(published.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(published.path("info").path("title").asText()).isEqualTo(frozen.getInfo().getTitle());
        assertThat(fieldNames(published.path("paths"))).containsExactlyInAnyOrderElementsOf(frozen.getPaths().keySet());
        assertThat(fieldNames(published.path("components").path("schemas")))
            .containsExactlyInAnyOrderElementsOf(frozen.getComponents().getSchemas().keySet());
    }

    @Test
    void yamlEndpointMatchesThePackagedContractBytes() throws Exception {
        byte[] published = mockMvc.perform(get("/openapi.yaml"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        byte[] packaged = new ClassPathResource("static/openapi.yaml").getInputStream().readAllBytes();

        assertThat(published).containsExactly(packaged);
    }

    private java.util.Set<String> fieldNames(JsonNode node) {
        java.util.Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
