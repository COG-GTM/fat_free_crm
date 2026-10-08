package com.fatfreecrm.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * A {@link CustomFieldValidationException} escaping a controller must render the Rails
 * {@code respond_with ... status: :unprocessable_entity} shape: HTTP 422, {@code application/json} (not
 * RFC 7807 problem+json) and a body of {@code {"errors": {"cf_x": ["message", ...]}}} with one entry per
 * failing field (key order is not part of the contract: the exception snapshots errors with {@code Map.copyOf}).
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
    void rendersRails422ErrorsHashThroughTheAdvice() throws Exception {
        mockMvc.perform(get("/test/custom-field-validation"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string("Content-Type", not(containsString("problem"))))
            .andExpect(jsonPath("$.errors.cf_required[0]").value("can't be blank"))
            .andExpect(jsonPath("$.errors.cf_short[0]").value("is too short (minimum is 3 characters)"))
            .andExpect(jsonPath("$.errors.cf_short[1]").value("is invalid"))
            .andExpect(jsonPath("$.errors.length()").value(2))
            .andExpect(jsonPath("$.status").doesNotExist())
            .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void stillRequiresAuthenticationBeforeReachingTheController() throws Exception {
        mockMvc.perform(get("/test/custom-field-validation")).andExpect(status().isUnauthorized());
    }

    @RestController
    static class TestController {

        @GetMapping("/test/custom-field-validation")
        String customFieldValidation() {
            Map<String, List<String>> errors = new LinkedHashMap<>();
            errors.put("cf_required", List.of("can't be blank"));
            errors.put("cf_short", List.of("is too short (minimum is 3 characters)", "is invalid"));
            throw new CustomFieldValidationException(errors);
        }
    }
}
