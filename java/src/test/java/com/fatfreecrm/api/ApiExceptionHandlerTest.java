package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.security.UnauthenticatedException;
import com.fatfreecrm.service.ResourceNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Each {@link ApiExceptionHandler} mapping yields the status, title, type and detail promised by
 * docs/migration/target-architecture.md §2.6, including the deliberate Rails-401 → Java-403
 * change for CanCan-style denials.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/7");

    @Test
    void authenticationExceptionIs401() {
        ProblemDetail problem = handler.handleUnauthenticated(new UnauthenticatedException("nope"), request);

        assertProblem(problem, HttpStatus.UNAUTHORIZED);
        assertThat(problem.getDetail()).isEqualTo("Authentication required");
    }

    @Test
    void accessDeniedIs403WithTheRailsCanCanMessage() {
        ProblemDetail problem = handler.handleAccessDenied(new AccessDeniedException("denied"), request);

        assertProblem(problem, HttpStatus.FORBIDDEN);
        assertThat(problem.getDetail()).isEqualTo("You are not authorized to take this action");
    }

    @Test
    void resourceNotFoundIs404WithTheExceptionMessage() {
        ProblemDetail problem = handler.handleNotFound(new ResourceNotFoundException("Account", 7L), request);

        assertProblem(problem, HttpStatus.NOT_FOUND);
        assertThat(problem.getDetail()).isEqualTo("Account with id 7 not found");
    }

    @Test
    void resourceNotFoundPlainMessageConstructorIsPassedThrough() {
        ProblemDetail problem = handler.handleNotFound(new ResourceNotFoundException("gone"), request);

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getDetail()).isEqualTo("gone");
    }

    @Test
    void typeMismatchIs400NamingParameterValueAndExpectedType() throws Exception {
        Method method = ApiExceptionHandlerTest.class.getDeclaredMethod("sample", Long.class);
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "id", new MethodParameter(method, 0), new NumberFormatException("abc"));

        ProblemDetail problem = handler.handleTypeMismatch(ex, request);

        assertProblem(problem, HttpStatus.BAD_REQUEST);
        assertThat(problem.getDetail()).isEqualTo("Parameter 'id' has invalid value 'abc' (expected Long)");
    }

    @Test
    void typeMismatchWithoutRequiredTypeFallsBackToGenericWording() throws Exception {
        Method method = ApiExceptionHandlerTest.class.getDeclaredMethod("sample", Long.class);
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", null, "id", new MethodParameter(method, 0), null);

        ProblemDetail problem = handler.handleTypeMismatch(ex, request);

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Parameter 'id' has invalid value 'abc' (expected expected type)");
    }

    @Test
    void constraintViolationsAre400SortedAndJoinedWithSemicolons() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Set<ConstraintViolation<PageQuery>> violations = validator.validate(new PageQuery(0, 500));
        assertThat(violations).hasSize(2);

        ProblemDetail problem = handler.handleConstraintViolation(new ConstraintViolationException(violations), request);

        assertProblem(problem, HttpStatus.BAD_REQUEST);
        assertThat(problem.getDetail())
                .isEqualTo("page must be greater than or equal to 1; perPage must be less than or equal to 200");
    }

    @Test
    void missingRequiredParameterIs400() {
        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("query", "String");

        ProblemDetail problem = handler.handleMissingParameter(ex, request);

        assertProblem(problem, HttpStatus.BAD_REQUEST);
        assertThat(problem.getDetail()).contains("query");
    }

    private static void assertProblem(ProblemDetail problem, HttpStatus status) {
        assertThat(problem.getStatus()).isEqualTo(status.value());
        assertThat(problem.getTitle()).isEqualTo(status.getReasonPhrase());
        assertThat(problem.getType()).hasToString(
                ProblemDetails.TYPE_BASE + status.getReasonPhrase().toLowerCase().replace(' ', '-'));
        assertThat(problem.getInstance()).hasToString("/api/v1/accounts/7");
    }

    @SuppressWarnings("unused")
    void sample(Long id) {
    }

    record PageQuery(@Min(1) int page, @Max(200) int perPage) {
    }
}
