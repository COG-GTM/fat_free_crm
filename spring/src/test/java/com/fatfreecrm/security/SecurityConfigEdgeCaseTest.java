package com.fatfreecrm.security;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Deny-by-default, statelessness and operational-endpoint exposure of {@link SecurityConfig} beyond the
 * GET happy paths: write verbs, unexposed actuator endpoints, problem metadata and gateway forwarded headers.
 */
class SecurityConfigEdgeCaseTest extends AbstractPostgresIntegrationTest {

    @Test
    void unauthenticatedProblemCarriesRfc9457Metadata() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/42"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("about:blank"))
            .andExpect(jsonPath("$.title").value("Unauthorized"))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.detail").value("Authentication is required to access this resource."))
            .andExpect(jsonPath("$.instance").value("/api/v1/accounts/42"))
            .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    void writeVerbsAreDeniedWithUnauthorizedNotCsrfForbidden() throws Exception {
        MockHttpServletRequestBuilder[] requests = {
            post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"),
            put("/api/v1/accounts/1").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"),
            patch("/api/v1/accounts/1").contentType(MediaType.APPLICATION_JSON).content("{}"),
            delete("/api/v1/accounts/1"),
        };
        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
        }
    }

    @Test
    void publicEndpointsOnlyAcceptReads() throws Exception {
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
            post("/actuator/health"), post("/actuator/info"), delete("/v3/api-docs"), put("/openapi.yaml")}) {
            mockMvc.perform(request)
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405));
        }
    }

    @Test
    void responsesNeverCreateASession() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mockMvc.perform(get("/api/v1/accounts"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @WithMockUser
    void authenticatedRequestsPassSecurityAndReachTheApiErrorHandler() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.instance").value("/api/v1/accounts"));
    }

    @Test
    void unexposedActuatorEndpointsAreNotReachableAnonymously() throws Exception {
        for (String path : new String[] {"/actuator", "/actuator/env", "/actuator/beans", "/actuator/metrics",
            "/actuator/mappings", "/actuator/flyway", "/actuator/loggers"}) {
            mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @Test
    @WithMockUser
    void unexposedActuatorEndpointsDoNotExistEvenWhenAuthenticated() throws Exception {
        for (String path : new String[] {"/actuator/env", "/actuator/beans", "/actuator/flyway"}) {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void healthProbesArePublicAndHideComponentDetails() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components").doesNotExist())
            .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void infoExposesBuildMetadataButNotEnvironment() throws Exception {
        mockMvc.perform(get("/actuator/info"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.build.artifact").value("fat-free-crm-api"))
            .andExpect(jsonPath("$.build.group").value("com.fatfreecrm"))
            .andExpect(jsonPath("$.env").doesNotExist());
    }

    @Test
    void swaggerUiShortcutIsPublicAndRedirectsToTheUi() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/swagger-ui/index.html")));
    }

    @Test
    void redirectsHonourGatewayForwardedPrefix() throws Exception {
        mockMvc.perform(get("/swagger-ui.html")
                .header("X-Forwarded-Host", "crm.example.com")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Prefix", "/gateway"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string(HttpHeaders.LOCATION, startsWith("/gateway/")))
            .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/swagger-ui/index.html")));
    }

    @Test
    void forwardedHeadersDoNotChangeTheProblemInstancePath() throws Exception {
        mockMvc.perform(get("/api/v1/leads")
                .header("X-Forwarded-Host", "crm.example.com")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Prefix", "/gateway"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.instance").value("/gateway/api/v1/leads"));
    }
}
