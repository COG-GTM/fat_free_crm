package com.fatfreecrm.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.security.SecurityConfig;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pins the RFC 9457 serialization of {@link ApiExceptionHandler} and the error paths that
 * {@code ResponseEntityExceptionHandler} routes through {@code handleExceptionInternal}.
 */
@WebMvcTest(controllers = ApiExceptionHandlerEdgeCaseTest.EdgeCaseController.class)
@Import({SecurityConfig.class, ApiExceptionHandlerEdgeCaseTest.EdgeCaseController.class})
class ApiExceptionHandlerEdgeCaseTest {

    private static final Set<String> PROBLEM_FIELDS = Set.of("type", "title", "status", "detail", "instance");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser
    void notFoundProblemCarriesOnlyRfc9457FieldsWithReasonPhraseTitleAndRequestUriInstance() throws Exception {
        MvcResult result = mockMvc.perform(get("/edge/missing/42"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("about:blank"))
            .andExpect(jsonPath("$.title").value(HttpStatus.NOT_FOUND.getReasonPhrase()))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.detail").value("The requested resource was not found."))
            .andExpect(jsonPath("$.instance").value("/edge/missing/42"))
            .andReturn();

        assertOnlyProblemFields(result);
    }

    @Test
    @WithMockUser
    void entityNotFoundUsesGenericDetailAndDoesNotLeakTheExceptionMessage() throws Exception {
        mockMvc.perform(get("/edge/entity-not-found"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value(HttpStatus.NOT_FOUND.getReasonPhrase()))
            .andExpect(jsonPath("$.detail").value("The requested entity was not found."))
            .andExpect(jsonPath("$.instance").value("/edge/entity-not-found"))
            .andExpect(content().string(not(containsString("contacts.id=7"))));
    }

    @Test
    @WithMockUser
    void responseStatusExceptionWithoutReasonFallsBackToGenericDetail() throws Exception {
        mockMvc.perform(get("/edge/conflict-without-reason"))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.title").value(HttpStatus.CONFLICT.getReasonPhrase()))
            .andExpect(jsonPath("$.detail").value("The request could not be processed."));
    }

    @Test
    @WithMockUser
    void responseStatusExceptionWithBlankReasonFallsBackToGenericDetail() throws Exception {
        mockMvc.perform(get("/edge/bad-request-blank-reason"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("The request could not be processed."));
    }

    @Test
    @WithMockUser
    void serverSideResponseStatusExceptionNeverExposesItsReason() throws Exception {
        mockMvc.perform(get("/edge/bad-gateway-with-reason"))
            .andExpect(status().isBadGateway())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(502))
            .andExpect(jsonPath("$.title").value(HttpStatus.BAD_GATEWAY.getReasonPhrase()))
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
            .andExpect(content().string(not(containsString("upstream host db-primary"))));
    }

    @Test
    @WithMockUser
    void beanValidationFailureReturnsBadRequestProblem() throws Exception {
        MvcResult result = mockMvc.perform(post("/edge/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.title").value(HttpStatus.BAD_REQUEST.getReasonPhrase()))
            .andExpect(jsonPath("$.detail").value("The request could not be processed."))
            .andExpect(jsonPath("$.instance").value("/edge/validated"))
            .andReturn();

        assertOnlyProblemFields(result);
    }

    @Test
    @WithMockUser
    void emptyRequestBodyReturnsBadRequestProblem() throws Exception {
        mockMvc.perform(post("/edge/validated").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("The request body is invalid."));
    }

    @Test
    @WithMockUser
    void missingRequiredParameterReturnsBadRequestProblem() throws Exception {
        mockMvc.perform(get("/edge/required-param"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value("The request could not be processed."));
    }

    @Test
    @WithMockUser
    void unsupportedMethodReturnsProblemAndKeepsTheAllowHeader() throws Exception {
        mockMvc.perform(delete("/edge/validated"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().string("Allow", containsString("POST")))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(405))
            .andExpect(jsonPath("$.title").value(HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase()))
            .andExpect(jsonPath("$.instance").value("/edge/validated"));
    }

    @Test
    @WithMockUser
    void unsupportedMediaTypeReturnsProblem() throws Exception {
        mockMvc.perform(post("/edge/validated")
                .contentType(MediaType.TEXT_PLAIN)
                .content("name=x"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(415))
            .andExpect(jsonPath("$.title").value(HttpStatus.UNSUPPORTED_MEDIA_TYPE.getReasonPhrase()));
    }

    @Test
    @WithMockUser
    void internalErrorProblemCarriesOnlyRfc9457Fields() throws Exception {
        MvcResult result = mockMvc.perform(get("/edge/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.type").value("about:blank"))
            .andExpect(jsonPath("$.title").value(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase()))
            .andExpect(jsonPath("$.instance").value("/edge/boom"))
            .andExpect(content().string(not(containsString("NullPointerException"))))
            .andReturn();

        assertOnlyProblemFields(result);
    }

    private void assertOnlyProblemFields(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        Set<String> fields = new HashSet<>();
        body.fieldNames().forEachRemaining(fields::add);
        if (!PROBLEM_FIELDS.containsAll(fields)) {
            throw new AssertionError("Problem body exposes non-RFC 9457 fields: " + fields);
        }
    }

    @RestController
    static class EdgeCaseController {

        @GetMapping("/edge/entity-not-found")
        String entityNotFound() {
            throw new EntityNotFoundException("contacts.id=7");
        }

        @GetMapping("/edge/conflict-without-reason")
        String conflictWithoutReason() {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }

        @GetMapping("/edge/bad-request-blank-reason")
        String badRequestBlankReason() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "   ");
        }

        @GetMapping("/edge/bad-gateway-with-reason")
        String badGatewayWithReason() {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream host db-primary unreachable");
        }

        @PostMapping("/edge/validated")
        String validated(@Valid @RequestBody NamedBody body) {
            return body.name();
        }

        @GetMapping("/edge/required-param")
        String requiredParam(@RequestParam String value) {
            return value;
        }

        @GetMapping("/edge/boom")
        String boom() {
            throw new NullPointerException("null pointer in service");
        }
    }

    record NamedBody(@NotBlank String name) {
    }
}
