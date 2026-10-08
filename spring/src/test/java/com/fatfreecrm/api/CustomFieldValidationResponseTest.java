package com.fatfreecrm.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A {@link CustomFieldValidationException} escaping a controller must render like a Rails
 * {@code render json: { errors: ... }, status: :unprocessable_entity}: HTTP 422, plain JSON (not
 * problem+json), field-name keys with message arrays, and nothing else leaked from the exception.
 */
@WebMvcTest(controllers = CustomFieldValidationResponseTest.TestController.class)
@Import({SecurityConfig.class, CustomFieldValidationResponseTest.TestController.class})
class CustomFieldValidationResponseTest {

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    void customFieldValidationFailureRendersRailsStyleErrorsEnvelopeWith422() throws Exception {
        mockMvc.perform(get("/test/custom-field-validation"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.errors.cf_tier[0]").value("can't be blank"))
            .andExpect(jsonPath("$.errors.cf_code[0]").value("is too short (minimum is 3 characters)"))
            .andExpect(jsonPath("$.errors.cf_code[1]").value("is invalid"))
            .andExpect(jsonPath("$.errors.length()").value(2))
            .andExpect(jsonPath("$.status").doesNotExist())
            .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void customFieldValidationEndpointStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/test/custom-field-validation")).andExpect(status().isUnauthorized());
    }

    @RestController
    static class TestController {

        @GetMapping("/test/custom-field-validation")
        String failValidation() {
            Map<String, List<String>> errors = new LinkedHashMap<>();
            errors.put("cf_tier", List.of("can't be blank"));
            errors.put("cf_code", List.of("is too short (minimum is 3 characters)", "is invalid"));
            throw new CustomFieldValidationException(errors);
        }
    }
}
