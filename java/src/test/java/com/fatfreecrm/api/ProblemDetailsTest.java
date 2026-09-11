package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** {@link ProblemDetails#of} populates every RFC 9457 field the API promises. */
class ProblemDetailsTest {

    @Test
    void populatesTypeTitleStatusDetailAndInstance() {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.NOT_FOUND, "Account with id 1 not found", "/api/v1/accounts/1");

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getTitle()).isEqualTo("Not Found");
        assertThat(problem.getDetail()).isEqualTo("Account with id 1 not found");
        assertThat(problem.getType()).hasToString("https://fatfreecrm.com/problems/not-found");
        assertThat(problem.getInstance()).hasToString("/api/v1/accounts/1");
    }

    @Test
    void multiWordReasonPhrasesAreSluggedWithHyphens() {
        assertThat(ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, "boom", "/x").getType())
                .hasToString("https://fatfreecrm.com/problems/internal-server-error");
        assertThat(ProblemDetails.of(HttpStatus.UNAUTHORIZED, "no", "/x").getType())
                .hasToString("https://fatfreecrm.com/problems/unauthorized");
        assertThat(ProblemDetails.of(HttpStatus.BAD_REQUEST, "no", "/x").getType())
                .hasToString("https://fatfreecrm.com/problems/bad-request");
    }

    @Test
    void nullInstancePathLeavesInstanceUnset() {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.FORBIDDEN, "denied", null);

        assertThat(problem.getInstance()).isNull();
        assertThat(problem.getStatus()).isEqualTo(403);
        assertThat(problem.getTitle()).isEqualTo("Forbidden");
    }
}
