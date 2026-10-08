package com.fatfreecrm.api;

import com.fatfreecrm.customfields.CustomFieldValidationException;
import com.fatfreecrm.service.validation.RailsValidationException;
import com.fatfreecrm.service.write.RailsInternalError;
import com.fatfreecrm.service.query.InvalidPageException;
import com.fatfreecrm.service.query.InvalidSearchQueryException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(CustomFieldValidationException.class)
    public ResponseEntity<Object> handleCustomFieldValidation(CustomFieldValidationException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("errors", exception.errors()));
    }

    /** Rails 422 {@code {"errors": {attr: [messages]}}} from ActiveModel-style validations. */
    @ExceptionHandler(RailsValidationException.class)
    public ResponseEntity<Object> handleRailsValidation(RailsValidationException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("errors", exception.errors()));
    }

    /** Mirrored Rails runtime defects (e.g. {@code Time.parse(nil)} TypeError) → 500. */
    @ExceptionHandler(RailsInternalError.class)
    public ResponseEntity<Object> handleRailsInternalError(RailsInternalError exception,
        HttpServletRequest request) {
        return problemResponse(HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred.", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthenticationFailure(
        AuthenticationException exception,
        HttpServletRequest request
    ) {
        return problemResponse(HttpStatus.UNAUTHORIZED, "Invalid credentials.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return problemResponse(HttpStatus.FORBIDDEN, "You are not allowed to access this resource.", request);
    }

    @ExceptionHandler(InvalidPageException.class)
    public ResponseEntity<Object> handleInvalidPage(InvalidPageException exception, HttpServletRequest request) {
        return problemResponse(HttpStatus.NOT_FOUND, "The requested page is invalid.", request);
    }

    @ExceptionHandler(InvalidSearchQueryException.class)
    public ResponseEntity<Object> handleInvalidSearchQuery(
        InvalidSearchQueryException exception,
        HttpServletRequest request
    ) {
        ProblemDetail problem = createProblemDetail(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
        problem.setProperty("invalidParameters", exception.invalidParameters());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(problem, headers, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Object> handleEntityNotFound(
        EntityNotFoundException exception,
        HttpServletRequest request
    ) {
        return problemResponse(HttpStatus.NOT_FOUND, "The requested entity was not found.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unhandled API exception", exception);
        return problemResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
        Exception exception,
        Object body,
        HttpHeaders headers,
        HttpStatusCode statusCode,
        WebRequest request
    ) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        if (statusCode.is5xxServerError()) {
            LOGGER.error("Unhandled API exception", exception);
        }
        String detail = statusCode.is5xxServerError()
            ? "An unexpected error occurred."
            : exceptionDetail(exception);
        ProblemDetail problem = createProblemDetail(statusCode, detail, servletRequest);
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return super.handleExceptionInternal(exception, problem, responseHeaders, statusCode, request);
    }

    protected ProblemDetail createProblemDetail(
        HttpStatusCode status,
        String detail,
        HttpServletRequest request
    ) {
        HttpStatus httpStatus = HttpStatus.valueOf(status.value());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(httpStatus.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    private ResponseEntity<Object> problemResponse(HttpStatus status, String detail, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(createProblemDetail(status, detail, request), headers, status);
    }

    private String exceptionDetail(Exception exception) {
        if (exception instanceof ResponseStatusException responseStatusException
            && responseStatusException.getStatusCode().is4xxClientError()) {
            String reason = responseStatusException.getReason();
            if (reason != null && !reason.isBlank()) {
                return reason;
            }
        }
        return switch (exception) {
            case NoResourceFoundException ignored ->
                "The requested resource was not found.";
            case HttpMessageNotReadableException ignored ->
                "The request body is invalid.";
            case MethodArgumentTypeMismatchException ignored ->
                "A request parameter has an invalid value.";
            default -> "The request could not be processed.";
        };
    }
}
