package com.fatfreecrm.api;

import com.fatfreecrm.service.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps exceptions to RFC 9457 {@code application/problem+json} responses
 * (docs/migration/target-architecture.md §2.6).
 *
 * <ul>
 *   <li>401 — unauthenticated. Normally emitted by the security entry point before a controller
 *       runs; handled here too for {@link AuthenticationException}s raised from service code.</li>
 *   <li>403 — {@link AccessDeniedException}. NOTE: the Rails app renders CanCan denials as
 *       HTTP 401 with the text "You are not authorized to take this action"; the rewrite
 *       deliberately uses 403. Clients migrating from Rails must accept both during transition.</li>
 *   <li>404 — {@link ResourceNotFoundException} (Rails: plain-text flash message).</li>
 *   <li>400 — type mismatches on path/query params, Bean Validation violations, missing
 *       required query params.</li>
 * </ul>
 *
 * <p>Ordered ahead of Spring's built-in {@code ProblemDetailsExceptionHandler}
 * ({@code spring.mvc.problemdetails.enabled=true}), which covers the remaining framework
 * exceptions (unmapped path → 404, unsupported method → 405, …) in the same media type.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleUnauthenticated(AuthenticationException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.UNAUTHORIZED, "Authentication required", request.getRequestURI());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return ProblemDetails.of(
                HttpStatus.FORBIDDEN, "You are not authorized to take this action", request.getRequestURI());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String expected = ex.getRequiredType() == null ? "expected type" : ex.getRequiredType().getSimpleName();
        String detail = "Parameter '" + ex.getName() + "' has invalid value '" + ex.getValue()
                + "' (expected " + expected + ")";
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, detail, request.getRequestURI());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, detail, request.getRequestURI());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }
}
