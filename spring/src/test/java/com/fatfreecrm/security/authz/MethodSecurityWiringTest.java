package com.fatfreecrm.security.authz;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.SecurityConfig;
import jakarta.persistence.EntityNotFoundException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pins the wiring added by AB-268 without a database: {@link MethodSecurityConfig} routes
 * {@code hasPermission(...)} to the {@link PermissionEvaluator} bean, {@link AdminOnly} is a
 * {@code hasRole('ADMIN')} check, and {@code ApiExceptionHandler} turns {@link AccessDeniedException}
 * into a 403 problem and the evaluator's {@link EntityNotFoundException} into a 404 problem.
 */
@WebMvcTest(controllers = MethodSecurityWiringTest.ProbeController.class)
@Import({SecurityConfig.class, MethodSecurityConfig.class, MethodSecurityWiringTest.ProbeController.class})
class MethodSecurityWiringTest {

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private PermissionEvaluator permissionEvaluator;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    void hasPermissionDelegatesTargetIdTypeAndActionToThePermissionEvaluatorBean() throws Exception {
        when(permissionEvaluator.hasPermission(any(Authentication.class), eq(7L), eq("Account"), eq("read")))
            .thenReturn(true);

        mockMvc.perform(get("/test/records/Account/7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(7));

        verify(permissionEvaluator).hasPermission(any(Authentication.class), eq(7L), eq("Account"), eq("read"));
    }

    @Test
    @WithMockUser
    void deniedEvaluationIsAForbiddenProblem() throws Exception {
        when(permissionEvaluator.hasPermission(any(Authentication.class), eq(7L), eq("Account"), eq("read")))
            .thenReturn(false);

        assertProblem(mockMvc.perform(get("/test/records/Account/7")), 403)
            .andExpect(jsonPath("$.detail").value("You are not allowed to access this resource."));
    }

    @Test
    @WithMockUser
    void missingRowReportedByTheEvaluatorIsANotFoundProblemWithoutTheInternalMessage() throws Exception {
        when(permissionEvaluator.hasPermission(any(Authentication.class), eq(7L), eq("Account"), eq("read")))
            .thenThrow(new EntityNotFoundException("Account 7 not found (internal)"));

        assertProblem(mockMvc.perform(get("/test/records/Account/7")), 404)
            .andExpect(content().string(not(containsString("internal"))));
    }

    @Test
    void unauthenticatedRequestsAreRejectedBeforeTheEvaluatorRuns() throws Exception {
        assertProblem(mockMvc.perform(get("/test/records/Account/7")), 401);
        assertProblem(mockMvc.perform(get("/test/admin-only")), 401);

        verifyNoInteractions(permissionEvaluator);
    }

    @Test
    @WithMockUser(roles = "USER")
    void adminOnlyDeniesUsersWithoutRoleAdmin() throws Exception {
        assertProblem(mockMvc.perform(get("/test/admin-only")), 403)
            .andExpect(jsonPath("$.detail").value("You are not allowed to access this resource."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminOnlyAllowsRoleAdmin() throws Exception {
        mockMvc.perform(get("/test/admin-only"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.admin").value(true));
    }

    @Test
    @WithMockUser
    void accessDeniedExceptionsFromControllersAreForbiddenProblemsWithoutTheInternalMessage() throws Exception {
        assertProblem(mockMvc.perform(get("/test/access-denied")), 403)
            .andExpect(jsonPath("$.detail").value("You are not allowed to access this resource."))
            .andExpect(content().string(not(containsString("internal rule"))));
    }

    private static ResultActions assertProblem(ResultActions result, int expectedStatus) throws Exception {
        return result.andExpect(status().is(expectedStatus))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/test/records/{type}/{id}")
        @PreAuthorize("hasPermission(#id, #type, 'read')")
        Map<String, Object> fetch(@PathVariable("type") String type, @PathVariable("id") Long id) {
            return Map.of("type", type, "id", id);
        }

        @GetMapping("/test/admin-only")
        @AdminOnly
        Map<String, Object> adminOnly() {
            return Map.of("admin", true);
        }

        @GetMapping("/test/access-denied")
        String accessDenied() {
            throw new AccessDeniedException("internal rule name");
        }
    }
}
