package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.api.PingController.PingResponse;
import java.time.LocalDateTime;
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

/** End-to-end behaviour of {@link TrustedHeaderAuthenticationFilter} and the security chain. */
class TrustedHeaderAuthTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void missingHeaderIsRejectedWith401ProblemJson() {
        ResponseEntity<ProblemDetail> response = get("/api/v1/ping", null, ProblemDetail.class);

        assertProblem(response, HttpStatus.UNAUTHORIZED, "/api/v1/ping");
    }

    @Test
    void unknownUserIdIsRejectedWith401() {
        ResponseEntity<ProblemDetail> response = get("/api/v1/ping", "9999", ProblemDetail.class);

        assertProblem(response, HttpStatus.UNAUTHORIZED, "/api/v1/ping");
    }

    @Test
    void nonNumericHeaderIsRejectedWith401() {
        ResponseEntity<ProblemDetail> response = get("/api/v1/ping", "not-a-number", ProblemDetail.class);

        assertProblem(response, HttpStatus.UNAUTHORIZED, "/api/v1/ping");
    }

    @Test
    void softDeletedOrSuspendedUsersAreRejected() {
        seeder.insertUser(7, "gone", false);
        seeder.softDeleteUser(7);
        seeder.insertUser(8, "suspended", false, LocalDateTime.now().minusDays(1));

        assertThat(get("/api/v1/ping", "7", ProblemDetail.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/ping", "8", ProblemDetail.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownApiPathWithoutHeaderIsStill401NotFoundLeaks() {
        ResponseEntity<ProblemDetail> response = get("/api/v1/does-not-exist", null, ProblemDetail.class);

        assertProblem(response, HttpStatus.UNAUTHORIZED, "/api/v1/does-not-exist");
    }

    @Test
    void unknownApiPathWithValidHeaderIs404ProblemJson() {
        seeder.insertUser(1, "alice", false);

        ResponseEntity<ProblemDetail> response = get("/api/v1/does-not-exist", "1", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(404);
    }

    @Test
    void validHeaderResolvesCurrentUserWithAdminFlagAndGroups() {
        seeder.insertUser(1, "alice", false);
        seeder.insertUser(2, "admin", true);
        seeder.insertGroup(100, "sales");
        seeder.insertGroup(101, "support");
        seeder.addUserToGroup(1, 100);
        seeder.addUserToGroup(1, 101);

        ResponseEntity<PingResponse> alice = get("/api/v1/ping", "1", PingResponse.class);
        assertThat(alice.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(alice.getBody()).isNotNull();
        assertThat(alice.getBody().userId()).isEqualTo(1L);
        assertThat(alice.getBody().admin()).isFalse();
        assertThat(alice.getBody().groupIds()).containsExactlyInAnyOrder(100L, 101L);

        ResponseEntity<PingResponse> admin = get("/api/v1/ping", "2", PingResponse.class);
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(admin.getBody()).isNotNull();
        assertThat(admin.getBody().admin()).isTrue();
        assertThat(admin.getBody().groupIds()).isEmpty();
    }

    @Test
    void openApiAndHealthArePublic() {
        assertThat(rest.getForEntity(url("/openapi.yaml"), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity(url("/v3/api-docs"), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity(url("/actuator/health"), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity(url("/swagger-ui.html"), String.class).getStatusCode().is3xxRedirection()
                || rest.getForEntity(url("/swagger-ui.html"), String.class).getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void openApiYamlIsTheFrozenContract() {
        String yaml = rest.getForEntity(url("/openapi.yaml"), String.class).getBody();
        assertThat(yaml).contains("openapi:").contains("/accounts/auto_complete");
    }

    @Test
    void corsPreflightIsPermissive() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://example.org");
        headers.setAccessControlRequestMethod(HttpMethod.GET);
        headers.setAccessControlRequestHeaders(java.util.List.of(TrustedHeaderAuthenticationFilter.HEADER));

        ResponseEntity<Void> response = rest.exchange(
                url("/api/v1/ping"), HttpMethod.OPTIONS, new HttpEntity<>(headers), Void.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("https://example.org");
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
        assertThat(body.getType()).hasToString("https://fatfreecrm.com/problems/" + status.getReasonPhrase().toLowerCase().replace(' ', '-'));
        assertThat(body.getDetail()).isNotBlank();
        assertThat(body.getInstance()).hasToString(instance);
    }
}
