package com.fatfreecrm.api;

import com.fatfreecrm.api.dto.CurrentUserResponse;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import com.fatfreecrm.service.AuthService;
import com.fatfreecrm.service.read.AutocompleteResult;
import com.fatfreecrm.service.read.UsersReadService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/api/v1/users")
public class UsersController {

    private final AuthService authService;
    private final UsersReadService usersReadService;

    public UsersController(AuthService authService, UsersReadService usersReadService) {
        this.authService = authService;
        this.usersReadService = usersReadService;
    }

    @GetMapping("/me")
    public CurrentUserResponse me(Authentication authentication) {
        return authService.currentUser(((FfcrmAuthenticationToken) authentication).getAuthenticatedUser());
    }

    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'User', 'read')")
    public java.util.List<String> show(@PathVariable long id) {
        return java.util.List.of(usersReadService.name(id));
    }

    @GetMapping("/autocomplete")
    public AutocompleteResult autocomplete(Authentication authentication, @RequestParam(required = false) String term) {
        return usersReadService.autocomplete(
            ((FfcrmAuthenticationToken) authentication).getAuthenticatedUser(), term);
    }
}
