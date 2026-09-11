package com.fatfreecrm.api;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Factory for RFC 9457 {@link ProblemDetail} bodies with the fields this API always populates:
 * {@code type}, {@code title}, {@code status}, {@code detail}, {@code instance}.
 */
public final class ProblemDetails {

    /** Base URI for problem {@code type}s; the status reason phrase is appended as a slug. */
    public static final String TYPE_BASE = "https://fatfreecrm.com/problems/";

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatus status, String detail, String instancePath) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + slug(status)));
        problem.setTitle(status.getReasonPhrase());
        if (instancePath != null) {
            problem.setInstance(URI.create(instancePath));
        }
        return problem;
    }

    private static String slug(HttpStatus status) {
        return status.getReasonPhrase().toLowerCase().replace(' ', '-');
    }
}
