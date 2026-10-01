package com.fatfreecrm.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the frozen Rails contract (`docs/migration/openapi.yaml`, tag
 * {@code openapi-baseline-v1}) at {@code /openapi.yaml}; springdoc's own
 * {@code /v3/api-docs} describes what this service actually implements, so the
 * two can be diffed as endpoints are ported.
 */
@Configuration
public class OpenApiConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/openapi.yaml")
            .addResourceLocations("classpath:/static/openapi.yaml");
    }
}
