package com.fatfreecrm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

/**
 * The published document must carry every top-level section of the frozen Rails contract, not only
 * its paths and schemas, so a Spring client sees the same servers, security requirements, tags, and
 * reusable responses the Rails API documents.
 */
class OpenApiContractParityTest extends AbstractPostgresIntegrationTest {

    private static final Set<String> HTTP_METHODS =
        Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode published;
    private OpenAPI frozen;

    @BeforeEach
    void loadDocuments() throws Exception {
        byte[] publishedBytes = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        published = objectMapper.readTree(publishedBytes);
        frozen = Yaml31.mapper().readValue(
            new ClassPathResource("static/openapi.yaml").getInputStream(),
            OpenAPI.class
        );
    }

    @Test
    void infoMatchesTheFrozenContractIncludingVersionAndDescription() {
        assertThat(published.path("info").path("version").asText()).isEqualTo(frozen.getInfo().getVersion());
        assertThat(published.path("info").path("description").asText())
            .isEqualTo(frozen.getInfo().getDescription());
        assertThat(published.path("info").path("version").asText()).isEqualTo("0.1.0");
    }

    @Test
    void serversMatchTheFrozenContractInsteadOfTheGeneratedLocalhostServer() {
        assertThat(published.path("servers")).hasSize(frozen.getServers().size());
        assertThat(published.path("servers").get(0).path("url").asText())
            .isEqualTo(frozen.getServers().get(0).getUrl());
        assertThat(published.path("servers").get(0).path("url").asText()).isEqualTo("/");
    }

    @Test
    void globalSecurityRequirementMatchesTheRailsCookieAuthScheme() {
        assertThat(published.path("security")).hasSize(1);
        assertThat(fieldNames(published.path("security").get(0))).containsExactly("cookieAuth");
        assertThat(fieldNames(published.path("components").path("securitySchemes")))
            .containsExactlyInAnyOrderElementsOf(frozen.getComponents().getSecuritySchemes().keySet());
    }

    @Test
    void tagsMatchTheFrozenContractInOrder() {
        Set<String> publishedTags = new java.util.LinkedHashSet<>();
        published.path("tags").forEach(tag -> publishedTags.add(tag.path("name").asText()));
        assertThat(publishedTags)
            .containsExactlyElementsOf(frozen.getTags().stream().map(Tag::getName).toList());
    }

    @Test
    void reusableResponsesAndParametersMatchTheFrozenContract() {
        assertThat(fieldNames(published.path("components").path("responses")))
            .containsExactlyInAnyOrderElementsOf(frozen.getComponents().getResponses().keySet())
            .contains("NotFound", "AccessDenied", "ValidationErrors");
        assertThat(fieldNames(published.path("components").path("parameters")))
            .containsExactlyInAnyOrderElementsOf(frozen.getComponents().getParameters().keySet());
    }

    @Test
    void everyPublishedOperationMatchesTheFrozenOperationsPerPath() {
        frozen.getPaths().forEach((path, item) -> {
            Set<String> frozenMethods = new HashSet<>();
            item.readOperationsMap().keySet().forEach(method -> frozenMethods.add(method.name().toLowerCase()));
            Set<String> publishedMethods = fieldNames(published.path("paths").path(path));
            publishedMethods.retainAll(HTTP_METHODS);
            assertThat(publishedMethods)
                .as("operations for %s", path)
                .containsExactlyInAnyOrderElementsOf(frozenMethods);
        });
    }

    @Test
    void publishedDocumentDoesNotAdvertiseSpringOnlyEndpoints() {
        Set<String> paths = fieldNames(published.path("paths"));
        assertThat(paths).noneMatch(path -> path.startsWith("/actuator"));
        assertThat(paths).noneMatch(path -> path.startsWith("/v3/api-docs"));
        assertThat(paths).noneMatch(path -> path.startsWith("/swagger-ui"));
        assertThat(paths).doesNotContain("/error", "/openapi.yaml");
    }

    @Test
    void yamlEndpointIsServedAsYamlAndParsesToTheFrozenContract() throws Exception {
        byte[] yaml = mockMvc.perform(get("/openapi.yaml"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        OpenAPI served = Yaml31.mapper().readValue(yaml, OpenAPI.class);
        assertThat(served.getOpenapi()).isEqualTo("3.1.0");
        assertThat(served.getPaths().keySet()).containsExactlyInAnyOrderElementsOf(frozen.getPaths().keySet());
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
