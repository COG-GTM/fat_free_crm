package com.fatfreecrm.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.security.TrustedHeaderAuthenticationFilter;
import jakarta.servlet.Filter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Configuration that the scaffold relies on but that no request-level test proves: bound
 * pagination properties, the CORS policy object, and the invariant that the trusted-header
 * filter is not also registered as a plain servlet filter (which would run it twice).
 */
class ApplicationWiringTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private PaginationProperties paginationProperties;

    @Autowired
    private CorsConfigurationSource corsConfigurationSource;

    @Test
    void paginationPropertiesAreBoundFromApplicationYml() {
        assertThat(paginationProperties.defaultPageSize()).isEqualTo(20);
        assertThat(paginationProperties.maxPageSize()).isEqualTo(200);
        assertThat(paginationProperties.clampPageSize(null)).isEqualTo(20);
        assertThat(paginationProperties.clampPageSize(1_000)).isEqualTo(200);
    }

    @Test
    void corsPolicyMatchesTheRailsAnyOriginBehaviour() {
        assertThat(corsConfigurationSource).isInstanceOf(UrlBasedCorsConfigurationSource.class);
        Map<String, CorsConfiguration> configs =
                ((UrlBasedCorsConfigurationSource) corsConfigurationSource).getCorsConfigurations();

        assertThat(configs).containsOnlyKeys("/**");
        CorsConfiguration config = configs.get("/**");
        assertThat(config.getAllowedOriginPatterns()).containsExactly("*");
        assertThat(config.getAllowedOrigins()).isNullOrEmpty();
        assertThat(config.getAllowedMethods()).containsExactly("*");
        assertThat(config.getAllowedHeaders()).containsExactly("*");
        assertThat(config.getExposedHeaders()).containsExactly("*");
        assertThat(config.getAllowCredentials()).isFalse();
        assertThat(config.getMaxAge()).isEqualTo(3600L);
    }

    @Test
    void trustedHeaderFilterIsNotRegisteredAsAStandaloneServletFilter() {
        assertThat(context.getBeansOfType(TrustedHeaderAuthenticationFilter.class)).isEmpty();
        assertThat(context.getBeansOfType(Filter.class).values())
                .noneMatch(TrustedHeaderAuthenticationFilter.class::isInstance);
        assertThat(context.getBeansOfType(FilterRegistrationBean.class).values())
                .noneMatch(reg -> reg.getFilter() instanceof TrustedHeaderAuthenticationFilter);
    }

    @Test
    void problemDetailsAndReadOnlyDatabaseSettingsAreEnabled() {
        assertThat(environment.getProperty("spring.mvc.problemdetails.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
    }
}
