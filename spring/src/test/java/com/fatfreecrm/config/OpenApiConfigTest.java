package com.fatfreecrm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.servers.Server;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void customizerReplacesEverythingSpringdocGeneratedWithTheFrozenContract() throws IOException {
        OpenAPI frozen = Yaml31.mapper().readValue(
            new ClassPathResource("static/openapi.yaml").getInputStream(), OpenAPI.class
        );
        OpenAPI generated = new OpenAPI()
            .openapi("3.0.1")
            .info(new Info().title("generated-by-springdoc").version("9.9.9"))
            .servers(java.util.List.of(new Server().url("http://localhost:8080")))
            .paths(new Paths().addPathItem("/generated-only", new PathItem()))
            .components(new Components().addSchemas("GeneratedOnly", new StringSchema()));

        OpenApiCustomizer customizer = config.frozenOpenApiCustomizer(new DefaultResourceLoader());
        customizer.customise(generated);

        assertThat(generated.getOpenapi()).isEqualTo("3.1.0");
        assertThat(generated.getInfo()).isEqualTo(frozen.getInfo());
        assertThat(generated.getServers()).isEqualTo(frozen.getServers());
        assertThat(generated.getSecurity()).isEqualTo(frozen.getSecurity());
        assertThat(generated.getTags()).isEqualTo(frozen.getTags());
        assertThat(generated.getPaths()).isEqualTo(frozen.getPaths()).doesNotContainKey("/generated-only");
        assertThat(generated.getComponents()).isEqualTo(frozen.getComponents());
        assertThat(generated.getComponents().getSchemas()).doesNotContainKey("GeneratedOnly");
        assertThat(generated.getExternalDocs()).isEqualTo(frozen.getExternalDocs());
    }

    @Test
    void frozenContractDescribesTheRailsApiSurface() throws IOException {
        OpenAPI frozen = Yaml31.mapper().readValue(
            new ClassPathResource("static/openapi.yaml").getInputStream(), OpenAPI.class
        );

        assertThat(frozen.getInfo().getTitle()).isEqualTo("Fat Free CRM — Current JSON/XML API Surface");
        assertThat(frozen.getSecurity()).singleElement()
            .satisfies(requirement -> assertThat(requirement).containsOnlyKeys("cookieAuth"));
        assertThat(frozen.getComponents().getSecuritySchemes().get("cookieAuth").getName())
            .isEqualTo("_ffcrm_session");
        assertThat(frozen.getComponents().getResponses()).containsKeys("NotFound", "AccessDenied", "ValidationErrors");
        assertThat(frozen.getPaths()).containsKeys(
            "/accounts", "/accounts/{id}", "/campaigns", "/contacts", "/leads", "/opportunities", "/tasks"
        );
    }

    @Test
    void startupFailsLoudlyWhenTheFrozenContractIsMissing() {
        DefaultResourceLoader missingContract = new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return super.getResource("classpath:static/does-not-exist.yaml");
            }
        };

        assertThatThrownBy(() -> config.frozenOpenApiCustomizer(missingContract)).isInstanceOf(IOException.class);
    }
}
