package com.fatfreecrm.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.SecurityConfig;
import com.fatfreecrm.service.validation.RailsErrors;
import com.fatfreecrm.service.validation.RailsValidationException;
import com.fatfreecrm.service.write.RailsInternalError;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** AB-272: the two exception handlers added for the write foundation. */
@WebMvcTest(controllers = ApiExceptionHandlerWriteErrorsTest.TestController.class)
@Import({SecurityConfig.class, ApiExceptionHandlerWriteErrorsTest.TestController.class})
class ApiExceptionHandlerWriteErrorsTest {

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    void railsValidationRendersActiveModelErrorsAsJsonNotProblemDetail() throws Exception {
        mockMvc.perform(get("/test/rails-validation"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json("{\"errors\":{\"user\":[\"must exist\",\"can't be blank\"],"
                + "\"name\":[\"^Please specify task name.\"]}}", JsonCompareMode.STRICT))
            .andExpect(jsonPath("$.status").doesNotExist())
            .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    @WithMockUser
    void railsInternalErrorIsAnOpaque500Problem() throws Exception {
        mockMvc.perform(get("/test/rails-internal"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
            .andExpect(content().string(not(containsString("implicit conversion"))));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/rails-validation")
        String railsValidation() {
            RailsErrors errors = new RailsErrors()
                .add("user", "must exist")
                .add("user", "can't be blank")
                .add("name", "^Please specify task name.");
            throw new RailsValidationException(errors);
        }

        @GetMapping("/test/rails-internal")
        String railsInternal() {
            throw new RailsInternalError("no implicit conversion of nil into String");
        }
    }
}
