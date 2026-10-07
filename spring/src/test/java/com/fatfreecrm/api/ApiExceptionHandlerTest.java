package com.fatfreecrm.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.security.SecurityConfig;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

@WebMvcTest(controllers = ApiExceptionHandlerTest.TestController.class)
@Import({SecurityConfig.class, ApiExceptionHandlerTest.TestController.class})
class ApiExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    void unknownPathReturnsNotFoundProblem() throws Exception {
        assertProblem(mockMvc.perform(get("/not-found")), 404);
    }

    @Test
    @WithMockUser
    void entityNotFoundReturnsNotFoundProblem() throws Exception {
        assertProblem(mockMvc.perform(get("/test/entity-not-found")), 404);
    }

    @Test
    @WithMockUser
    void typeMismatchReturnsBadRequestProblem() throws Exception {
        assertProblem(mockMvc.perform(get("/test/number").param("value", "not-a-number")), 400);
    }

    @Test
    @WithMockUser
    void unreadableBodyReturnsBadRequestProblem() throws Exception {
        assertProblem(
            mockMvc.perform(post("/test/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{malformed")),
            400
        );
    }

    @Test
    @WithMockUser
    void unexpectedExceptionReturnsGenericInternalErrorProblem() throws Exception {
        mockMvc.perform(get("/test/exception"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("sensitive exception message")
            )));
    }

    private void assertProblem(
        org.springframework.test.web.servlet.ResultActions result,
        int expectedStatus
    ) throws Exception {
        result.andExpect(status().is(expectedStatus))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/entity-not-found")
        String entityNotFound() {
            throw new EntityNotFoundException("internal database key");
        }

        @GetMapping("/test/number")
        int number(@RequestParam int value) {
            return value;
        }

        @PostMapping("/test/body")
        String body(@RequestBody TestBody body) {
            return body.value();
        }

        @GetMapping("/test/exception")
        String exception() {
            throw new IllegalStateException("sensitive exception message");
        }
    }

    record TestBody(String value) {
    }
}
