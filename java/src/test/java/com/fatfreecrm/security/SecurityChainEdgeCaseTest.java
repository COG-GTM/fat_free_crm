package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.api.PingController.PingResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Boundary and error paths of {@link TrustedHeaderAuthenticationFilter} and the
 * {@code SecurityConfig} authorization rules that the happy-path tests do not exercise:
 * header parsing limits, the {@code anyRequest().denyAll()} 403 handler, and request isolation.
 */
class SecurityChainEdgeCaseTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void zeroAndNegativeUserIdsAreRejectedWith401() {
        seeder.insertUser(1, "alice", false);

        assertProblem(get("/api/v1/ping", "0", ProblemDetail.class), HttpStatus.UNAUTHORIZED, "/api/v1/ping");
        assertProblem(get("/api/v1/ping", "-1", ProblemDetail.class), HttpStatus.UNAUTHORIZED, "/api/v1/ping");
    }

    @Test
    void headerLargerThanLongIsRejectedWith401NotServerError() {
        ResponseEntity<ProblemDetail> response = get("/api/v1/ping", "99999999999999999999999", ProblemDetail.class);

        assertProblem(response, HttpStatus.UNAUTHORIZED, "/api/v1/ping");
    }

    @Test
    void decimalAndEmbeddedWhitespaceIdsAreRejectedWith401() {
        seeder.insertUser(1, "alice", false);

        assertThat(get("/api/v1/ping", "1.0", ProblemDetail.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/ping", "1 2", ProblemDetail.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/ping", "0x1", ProblemDetail.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void surroundingWhitespaceInHeaderIsTrimmed() {
        seeder.insertUser(1, "alice", false);

        ResponseEntity<PingResponse> response = get("/api/v1/ping", "  1  ", PingResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().userId()).isEqualTo(1L);
    }

    @Test
    void blankHeaderIsTreatedAsMissingAndRejectedWith401() {
        seeder.insertUser(1, "alice", false);

        ResponseEntity<ProblemDetail> response = get("/api/v1/ping", "   ", ProblemDetail.class);

        assertProblem(response, HttpStatus.UNAUTHORIZED, "/api/v1/ping");
        assertThat(response.getBody().getDetail()).contains(TrustedHeaderAuthenticationFilter.HEADER);
    }

    @Test
    void unauthenticatedProblemDetailNamesTheHeader() {
        ResponseEntity<ProblemDetail> response = get("/api/v1/ping", null, ProblemDetail.class);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail())
                .isEqualTo("Authentication required: missing or invalid X-User-Id header");
    }

    @Test
    void authenticatedUserOnNonApiNonPublicPathGets403ProblemJson() {
        seeder.insertUser(1, "alice", false);
        seeder.insertUser(2, "admin", true);

        ResponseEntity<ProblemDetail> user = get("/internal/anything", "1", ProblemDetail.class);
        assertProblem(user, HttpStatus.FORBIDDEN, "/internal/anything");
        assertThat(user.getBody().getDetail()).isEqualTo("You are not authorized to take this action");

        ResponseEntity<ProblemDetail> admin = get("/internal/anything", "2", ProblemDetail.class);
        assertProblem(admin, HttpStatus.FORBIDDEN, "/internal/anything");
    }

    @Test
    void anonymousOnNonApiNonPublicPathGets401NotFoundLeaks() {
        ResponseEntity<ProblemDetail> response = get("/internal/anything", null, ProblemDetail.class);

        assertProblem(response, HttpStatus.UNAUTHORIZED, "/internal/anything");
    }

    @Test
    void unexposedActuatorEndpointsAreNotPublic() {
        assertThat(get("/actuator/env", null, ProblemDetail.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/actuator", null, ProblemDetail.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unsupportedMethodOnApiWithoutHeaderIs401Not405() {
        HttpHeaders headers = new HttpHeaders();
        ResponseEntity<ProblemDetail> response = rest.exchange(
                url("/api/v1/ping"), HttpMethod.POST, new HttpEntity<>(headers), ProblemDetail.class);

        assertProblem(response, HttpStatus.UNAUTHORIZED, "/api/v1/ping");
    }

    @Test
    void unsupportedMethodOnApiWithHeaderIs405ProblemJson() {
        seeder.insertUser(1, "alice", false);
        HttpHeaders headers = new HttpHeaders();
        headers.set(TrustedHeaderAuthenticationFilter.HEADER, "1");

        ResponseEntity<ProblemDetail> response = rest.exchange(
                url("/api/v1/ping"), HttpMethod.POST, new HttpEntity<>(headers), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(405);
    }

    @Test
    void validHeaderOnPublicPathStillServesThePublicResource() {
        seeder.insertUser(1, "alice", false);

        ResponseEntity<Map> response = get("/actuator/health", "1", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void securityContextDoesNotLeakBetweenRequests() {
        seeder.insertUser(1, "alice", false);
        seeder.insertUser(2, "admin", true);
        seeder.insertGroup(100, "sales");
        seeder.addUserToGroup(1, 100);

        ResponseEntity<PingResponse> first = get("/api/v1/ping", "2", PingResponse.class);
        ResponseEntity<PingResponse> second = get("/api/v1/ping", "1", PingResponse.class);
        ResponseEntity<ProblemDetail> third = get("/api/v1/ping", null, ProblemDetail.class);

        assertThat(first.getBody()).isNotNull();
        assertThat(first.getBody().userId()).isEqualTo(2L);
        assertThat(first.getBody().admin()).isTrue();
        assertThat(first.getBody().groupIds()).isEmpty();

        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().userId()).isEqualTo(1L);
        assertThat(second.getBody().admin()).isFalse();
        assertThat(second.getBody().groupIds()).containsExactly(100L);

        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void groupMembershipReflectsCurrentDatabaseStateOnEveryRequest() {
        seeder.insertUser(1, "alice", false);
        seeder.insertGroup(100, "sales");

        assertThat(get("/api/v1/ping", "1", PingResponse.class).getBody().groupIds()).isEmpty();

        seeder.addUserToGroup(1, 100);

        assertThat(get("/api/v1/ping", "1", PingResponse.class).getBody().groupIds()).containsExactly(100L);
    }

    @Test
    void suspensionTakesEffectOnTheNextRequest() {
        seeder.insertUser(1, "alice", false);
        assertThat(get("/api/v1/ping", "1", PingResponse.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        seeder.jdbc().update("UPDATE users SET suspended_at = now() WHERE id = 1");

        assertThat(get("/api/v1/ping", "1", ProblemDetail.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void pingResponseUsesTheDocumentedFieldNames() {
        seeder.insertUser(1, "alice", false);

        ResponseEntity<Map> response = get("/api/v1/ping", "1", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(response.getBody()).containsOnlyKeys("userId", "admin", "groupIds");
    }

    @Test
    void corsResponseDoesNotAllowCredentials() {
        seeder.insertUser(1, "alice", false);
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://example.org");
        headers.set(TrustedHeaderAuthenticationFilter.HEADER, "1");

        ResponseEntity<PingResponse> response = rest.exchange(
                url("/api/v1/ping"), HttpMethod.GET, new HttpEntity<>(headers), PingResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("https://example.org");
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isFalse();
    }

    private <T> ResponseEntity<T> get(String path, String userIdHeader, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (userIdHeader != null) {
            headers.set(TrustedHeaderAuthenticationFilter.HEADER, userIdHeader);
        }
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), type);
    }

    private static void assertProblem(ResponseEntity<ProblemDetail> response, HttpStatus status, String instance) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(status.value());
        assertThat(body.getTitle()).isEqualTo(status.getReasonPhrase());
        assertThat(body.getType()).hasToString(
                "https://fatfreecrm.com/problems/" + status.getReasonPhrase().toLowerCase().replace(' ', '-'));
        assertThat(body.getInstance()).hasToString(instance);
    }
}
