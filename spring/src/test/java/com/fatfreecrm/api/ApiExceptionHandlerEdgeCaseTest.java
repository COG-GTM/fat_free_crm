package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.security.SecurityConfig;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * RFC 9457 details and edge cases of {@link ApiExceptionHandler} that the happy-path tests leave open:
 * problem metadata, 405/415/missing-parameter mapping, reason/message leakage and error logging.
 */
@WebMvcTest(controllers = ApiExceptionHandlerEdgeCaseTest.EdgeCaseController.class)
@Import({SecurityConfig.class, ApiExceptionHandlerEdgeCaseTest.EdgeCaseController.class})
@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerEdgeCaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    void problemCarriesRfc9457TitleAndInstanceOfTheFailingRequest() throws Exception {
        mockMvc.perform(get("/edge/entity-not-found"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("about:blank"))
            .andExpect(jsonPath("$.title").value("Not Found"))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.detail").value("The requested entity was not found."))
            .andExpect(jsonPath("$.instance").value("/edge/entity-not-found"));
    }

    @Test
    @WithMockUser
    void entityNotFoundDoesNotLeakThePersistenceMessage() throws Exception {
        mockMvc.perform(get("/edge/entity-not-found"))
            .andExpect(status().isNotFound())
            .andExpect(content().string(not(containsString("internal database key"))));
    }

    @Test
    @WithMockUser
    void unknownPathProblemDescribesTheResourceAndRequestedPath() throws Exception {
        mockMvc.perform(get("/edge/missing/123"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("Not Found"))
            .andExpect(jsonPath("$.detail").value("The requested resource was not found."))
            .andExpect(jsonPath("$.instance").value("/edge/missing/123"));
    }

    @Test
    @WithMockUser
    void blankReasonFallsBackToTheGenericClientErrorDetail() throws Exception {
        mockMvc.perform(get("/edge/conflict-without-reason"))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Conflict"))
            .andExpect(jsonPath("$.detail").value("The request could not be processed."));
    }

    @Test
    @WithMockUser
    void serverSideResponseStatusReasonIsNeverExposed() throws Exception {
        mockMvc.perform(get("/edge/unavailable-with-reason"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Service Unavailable"))
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
            .andExpect(content().string(not(containsString("connection pool exhausted"))));
    }

    @Test
    @WithMockUser
    void unsupportedMethodReturnsProblemAndKeepsTheAllowHeader() throws Exception {
        mockMvc.perform(delete("/edge/body"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().string("Allow", containsString("POST")))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(405))
            .andExpect(jsonPath("$.title").value("Method Not Allowed"))
            .andExpect(jsonPath("$.detail").value("The request could not be processed."));
    }

    @Test
    @WithMockUser
    void unsupportedMediaTypeReturnsProblem() throws Exception {
        mockMvc.perform(post("/edge/body").contentType(MediaType.TEXT_PLAIN).content("value=1"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(415))
            .andExpect(jsonPath("$.title").value("Unsupported Media Type"));
    }

    @Test
    @WithMockUser
    void missingRequiredParameterReturnsBadRequestProblem() throws Exception {
        mockMvc.perform(get("/edge/number"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value("The request could not be processed."));
    }

    @Test
    @WithMockUser
    void typeMismatchDetailNamesTheParameterProblemWithoutEchoingInput() throws Exception {
        mockMvc.perform(get("/edge/number").param("value", "<script>"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("A request parameter has an invalid value."))
            .andExpect(content().string(not(containsString("<script>"))));
    }

    @Test
    @WithMockUser
    void emptyBodyReturnsBadRequestProblem() throws Exception {
        mockMvc.perform(post("/edge/body").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("The request body is invalid."));
    }

    @Test
    @WithMockUser
    void unexpectedExceptionsAreLoggedButClientErrorsAreNot(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/edge/entity-not-found")).andExpect(status().isNotFound());
        mockMvc.perform(get("/edge/missing")).andExpect(status().isNotFound());
        assertThat(output.getOut()).doesNotContain("Unhandled API exception");

        mockMvc.perform(get("/edge/boom")).andExpect(status().isInternalServerError());
        assertThat(output.getOut()).contains("Unhandled API exception").contains("sensitive exception message");
    }

    @Test
    void unauthenticatedRequestsNeverReachTheHandler(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/edge/boom"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("Authentication is required to access this resource."));
        assertThat(output.getOut()).doesNotContain("Unhandled API exception");
    }

    @RestController
    static class EdgeCaseController {

        @GetMapping("/edge/entity-not-found")
        String entityNotFound() {
            throw new EntityNotFoundException("internal database key");
        }

        @GetMapping("/edge/conflict-without-reason")
        String conflictWithoutReason() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "   ");
        }

        @GetMapping("/edge/unavailable-with-reason")
        String unavailableWithReason() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "connection pool exhausted");
        }

        @GetMapping("/edge/number")
        int number(@RequestParam int value) {
            return value;
        }

        @PostMapping(path = "/edge/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        String body(@RequestBody EdgeBody body) {
            return body.value();
        }

        @GetMapping("/edge/boom")
        String boom() {
            throw new IllegalStateException("sensitive exception message");
        }
    }

    record EdgeBody(String value) {
    }
}
