package com.fatfreecrm.security;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Deny-by-default behaviour of {@link SecurityConfig} beyond the GET happy paths: every HTTP method,
 * actuator endpoints that are not exposed, stateless sessions, and the shape of the 401 problem body.
 */
class SecurityConfigAccessControlTest extends AbstractPostgresIntegrationTest {

    private static final List<HttpMethod> MUTATING_METHODS =
        List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private static final List<String> UNEXPOSED_ACTUATOR_PATHS = List.of(
        "/actuator/env", "/actuator/beans", "/actuator/flyway", "/actuator/mappings",
        "/actuator/configprops", "/actuator/loggers", "/actuator/threaddump"
    );

    @Test
    void unauthenticatedProblemFollowsRfc9457AndNamesTheRequestedResource() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/7"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("about:blank"))
            .andExpect(jsonPath("$.title").value(HttpStatus.UNAUTHORIZED.getReasonPhrase()))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.detail").value("Authentication is required to access this resource."))
            .andExpect(jsonPath("$.instance").value("/api/v1/accounts/7"));
    }

    /**
     * Rails answers a CanCanCan denial with 401 (see {@code ApplicationController#respond_to_access_denied}
     * and the {@code AccessDenied} response in {@code docs/migration/openapi.yaml}); the Spring side must not
     * downgrade an unauthenticated contract path to 403 or 404.
     */
    @Test
    void contractPathsReturnTheSameStatusAsRailsAccessDeniedWhenUnauthenticated() throws Exception {
        for (String path : List.of("/accounts", "/accounts/1", "/contacts", "/leads/3/promote", "/tasks")) {
            mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @Test
    void mutatingRequestsWithoutCredentialsAreUnauthorizedNotCsrfForbidden() throws Exception {
        for (HttpMethod method : MUTATING_METHODS) {
            mockMvc.perform(request(method, "/api/v1/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Acme\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/v1/accounts"));
        }
    }

    @Test
    void mutatingRequestsToPublicPathsStillPassTheFilterChain() throws Exception {
        for (HttpMethod method : MUTATING_METHODS) {
            mockMvc.perform(request(method, "/actuator/health"))
                .andExpect(status().isMethodNotAllowed());
        }
    }

    @Test
    void unexposedActuatorEndpointsAreNeverPublic() throws Exception {
        mockMvc.perform(get("/actuator"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        for (String path : UNEXPOSED_ACTUATOR_PATHS) {
            mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @Test
    void unexposedActuatorEndpointsStayHiddenFromAuthenticatedUsers() throws Exception {
        for (String path : UNEXPOSED_ACTUATOR_PATHS) {
            mockMvc.perform(get(path).with(user("alice")))
                .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/actuator").with(user("alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$._links.health").exists())
            .andExpect(jsonPath("$._links.info").exists())
            .andExpect(jsonPath("$._links.env").doesNotExist())
            .andExpect(jsonPath("$._links.flyway").doesNotExist());
    }

    @Test
    void healthHidesComponentDetailsAndExposesKubernetesProbes() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist())
            .andExpect(jsonPath("$.details").doesNotExist());
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void infoDoesNotExposeEnvironmentButExposesBuildMetadata() throws Exception {
        mockMvc.perform(get("/actuator/info"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.build.name").value("fat-free-crm-api"))
            .andExpect(jsonPath("$.env").doesNotExist());
    }

    @Test
    void authenticatedRequestsPassTheFilterChainAndFallThroughToTheApiErrorHandler() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").with(user("alice")))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.instance").value("/api/v1/accounts"));
    }

    @Test
    void noSessionCookieIsIssuedOnPublicProtectedOrAuthenticatedResponses() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mockMvc.perform(get("/api/v1/accounts"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mockMvc.perform(get("/api/v1/accounts").with(user("alice")))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void unauthorizedResponseDoesNotChallengeWithBasicOrFormLogin() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
            .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    @Test
    void swaggerUiEntryPointRedirectsWithinThePublicSwaggerPaths() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/swagger-ui/index.html")));
    }

    @Test
    void publicPathsDoNotLeakIntoSiblingPrefixes() throws Exception {
        mockMvc.perform(get("/actuator/healthcheck")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/information")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/openapi.yaml.bak")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v3/api-docs-private")).andExpect(status().isUnauthorized());
    }
}
