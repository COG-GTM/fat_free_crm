package com.fatfreecrm.security;

import com.fatfreecrm.api.ProblemDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Writes a 401 RFC 9457 {@code application/problem+json} body for unauthenticated requests,
 * both when the security chain rejects a request and when
 * {@link TrustedHeaderAuthenticationFilter} cannot resolve the header to a user.
 */
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ProblemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        ProblemDetail problem = ProblemDetails.of(
                HttpStatus.UNAUTHORIZED,
                "Authentication required: missing or invalid " + TrustedHeaderAuthenticationFilter.HEADER + " header",
                request.getRequestURI());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
