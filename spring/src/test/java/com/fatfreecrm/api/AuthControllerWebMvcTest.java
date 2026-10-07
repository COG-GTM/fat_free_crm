package com.fatfreecrm.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.api.dto.TokenResponse;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.SecurityConfig;
import com.fatfreecrm.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * HTTP-layer contract of {@link AuthController} with the service mocked: request/response field names and
 * the uniform 401 problem produced by {@link ApiExceptionHandler#handleAuthenticationFailure}.
 */
@WebMvcTest(controllers = AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerWebMvcTest {

    private static final TokenResponse TOKENS = new TokenResponse("access-token", "refresh-token", "Bearer", 900);

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginSerializesTheTokenPairWithCamelCaseFieldNames() throws Exception {
        when(authService.login(eq("legacy_plain"), eq("PlainAsciiPassword42"), any(HttpServletRequest.class)))
            .thenReturn(TOKENS);

        login("{\"username\":\"legacy_plain\",\"password\":\"PlainAsciiPassword42\"}")
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.accessToken").value("access-token"))
            .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void loginPassesTheRawLoginThroughSoTheServiceLayerOwnsNormalization() throws Exception {
        when(authService.login(any(), any(), any(HttpServletRequest.class))).thenReturn(TOKENS);

        login("{\"username\":\"  Legacy_MixedCase  \",\"password\":\" spaced \"}").andExpect(status().isOk());

        verify(authService).login(eq("  Legacy_MixedCase  "), eq(" spaced "), any(HttpServletRequest.class));
    }

    @Test
    void everyAuthenticationFailureProducesTheSameUnauthorizedProblem() throws Exception {
        List<AuthenticationException> failures = List.of(
            new BadCredentialsException("Bad credentials"),
            new UsernameNotFoundException("legacy_plain"),
            new DisabledException("User is disabled"),
            new LockedException("User account is locked")
        );
        for (AuthenticationException failure : failures) {
            when(authService.login(any(), any(), any(HttpServletRequest.class))).thenThrow(failure);

            login("{\"username\":\"legacy_plain\",\"password\":\"wrong\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Invalid credentials."))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/login"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                    org.hamcrest.Matchers.containsString(failure.getMessage()))));
        }
    }

    @Test
    void refreshReturnsANewPairAndMapsFailuresToTheSameProblem() throws Exception {
        when(authService.refresh("refresh-token")).thenReturn(TOKENS);
        when(authService.refresh("stale")).thenThrow(new BadCredentialsException("Invalid credentials."));

        refresh("{\"refreshToken\":\"refresh-token\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("access-token"));
        refresh("{\"refreshToken\":\"stale\"}")
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.detail").value("Invalid credentials."))
            .andExpect(jsonPath("$.instance").value("/api/v1/auth/refresh"));
    }

    @Test
    void validationFailuresNeverReachTheServiceOrEchoThePassword() throws Exception {
        login("{\"username\":\"\",\"password\":\"SuperSecretValue\"}")
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("SuperSecretValue"))));
        login("{\"username\":\"legacy_plain\"}").andExpect(status().isBadRequest());
        login("not json").andExpect(status().isBadRequest());
        refresh("{\"refreshToken\":\"   \"}").andExpect(status().isBadRequest());
        refresh("{}").andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    private ResultActions login(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions refresh(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
