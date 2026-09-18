package com.fatfreecrm.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI wiring. The Swagger UI is pointed (via {@code springdoc.swagger-ui.url} in
 * application.yml) at the FROZEN contract {@code /openapi.yaml}, a verbatim copy of
 * {@code docs/migration/openapi.yaml} served from {@code src/main/resources/static}. That file
 * documents the Rails paths; the Java service mounts the same resources under {@code /api/v1}.
 *
 * <p>The generated document at {@code /v3/api-docs} stays available for development but is not
 * the contract of record.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI generatedOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Fat Free CRM API (generated)")
                .version("v1")
                .description("Auto-generated from controllers. The contract of record is the frozen "
                        + "/openapi.yaml shown in Swagger UI."));
    }
}
