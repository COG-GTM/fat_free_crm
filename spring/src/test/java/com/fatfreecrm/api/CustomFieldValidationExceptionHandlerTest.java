package com.fatfreecrm.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.customfields.CustomFieldValidationException;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.SecurityConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pins the HTTP shape of a custom-field validation failure to Rails' {@code render json: {errors: ...},
 * status: :unprocessable_entity} convention: 422, plain JSON (not problem+json) and the per-field
 * message lists untouched.
 */
@WebMvcTest(controllers = CustomFieldValidationExceptionHandlerTest.TestController.class)
@Import({SecurityConfig.class, CustomFieldValidationExceptionHandlerTest.TestController.class})
class CustomFieldValidationExceptionHandlerTest {

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    void customFieldValidationFailureIsA422WithRailsStyleErrorsBody() throws Exception {
        mockMvc.perform(post("/test/custom-field-validation"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.errors.cf_age[0]").value("is not a number"))
            .andExpect(jsonPath("$.errors.cf_name[0]").value("can't be blank"))
            .andExpect(jsonPath("$.errors.cf_name[1]").value("is too short (minimum is 3 characters)"))
            .andExpect(jsonPath("$.status").doesNotExist())
            .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void customFieldValidationEndpointStillRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/test/custom-field-validation"))
            .andExpect(status().isUnauthorized());
    }

    @RestController
    static class TestController {

        @PostMapping("/test/custom-field-validation")
        String customFieldValidation() {
            Map<String, List<String>> errors = new LinkedHashMap<>();
            errors.put("cf_age", List.of("is not a number"));
            errors.put("cf_name", List.of("can't be blank", "is too short (minimum is 3 characters)"));
            throw new CustomFieldValidationException(errors);
        }
    }
}
