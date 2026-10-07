package com.fatfreecrm.api;

import com.fatfreecrm.api.dto.CurrentUserResponse;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import com.fatfreecrm.service.AuthService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/api/v1/users")
public class UsersController {

    private final AuthService authService;

    public UsersController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/me")
    public CurrentUserResponse me(Authentication authentication) {
        return authService.currentUser(((FfcrmAuthenticationToken) authentication).getAuthenticatedUser());
    }
}
