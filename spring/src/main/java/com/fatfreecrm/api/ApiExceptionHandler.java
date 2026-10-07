package com.fatfreecrm.api;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

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
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        return problem;
    }

    private ResponseEntity<Object> problemResponse(HttpStatus status, String detail, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(createProblemDetail(status, detail, request), headers, status);
    }

    private String exceptionDetail(Exception exception) {
        return switch (exception) {
            case org.springframework.web.servlet.resource.NoResourceFoundException ignored ->
                "The requested resource was not found.";
            case org.springframework.http.converter.HttpMessageNotReadableException ignored ->
                "The request body is invalid.";
            case org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ignored ->
                "A request parameter has an invalid value.";
            default -> "The request could not be processed.";
        };
    }
}
