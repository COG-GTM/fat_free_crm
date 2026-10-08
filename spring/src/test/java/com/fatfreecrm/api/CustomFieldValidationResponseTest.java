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
 * Rails answers an invalid custom-field assignment with {@code 422 Unprocessable Entity} and a plain
 * {@code {"errors": {attribute: [messages]}}} body (not an RFC 7807 problem document).
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
    void customFieldValidationFailuresRenderRailsStyle422Errors() throws Exception {
        mockMvc.perform(post("/test/custom-fields"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.errors.cf_region[0]").value("Region is required"))
            .andExpect(jsonPath("$.errors.cf_name[0]").value("Name is too short"))
            .andExpect(jsonPath("$.errors.cf_name[1]").value("Name is invalid"))
            .andExpect(jsonPath("$.status").doesNotExist())
            .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void anonymousCallersAreRejectedBeforeCustomFieldValidationRuns() throws Exception {
        mockMvc.perform(post("/test/custom-fields"))
            .andExpect(status().isUnauthorized());
    }

    @RestController
    static class TestController {

        @PostMapping("/test/custom-fields")
        String customFields() {
            Map<String, List<String>> errors = new LinkedHashMap<>();
            errors.put("cf_region", List.of("Region is required"));
            errors.put("cf_name", List.of("Name is too short", "Name is invalid"));
            throw new CustomFieldValidationException(errors);
        }
    }
}
