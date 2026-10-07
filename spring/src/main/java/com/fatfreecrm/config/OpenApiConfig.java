package com.fatfreecrm.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.OpenAPI;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springdoc.core.customizers.OpenApiCustomizer;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenApiCustomizer frozenOpenApiCustomizer(ResourceLoader resourceLoader) throws IOException {
        Resource frozenContract = resourceLoader.getResource("classpath:static/openapi.yaml");
        ObjectMapper yamlMapper = Yaml31.mapper();
        OpenAPI frozen;
        try (var contractStream = frozenContract.getInputStream()) {
            frozen = yamlMapper.readValue(contractStream, OpenAPI.class);
        }
        return generated -> {
            generated.setOpenapi(frozen.getOpenapi());
            generated.setInfo(frozen.getInfo());
            generated.setServers(frozen.getServers());
            generated.setSecurity(frozen.getSecurity());
            generated.setTags(frozen.getTags());
            generated.setPaths(frozen.getPaths());
            generated.setComponents(frozen.getComponents());
            generated.setExternalDocs(frozen.getExternalDocs());
        };
    }
}
