package com.fatfreecrm.api;

import com.fatfreecrm.security.FfcrmAuthenticationToken;
import com.fatfreecrm.service.read.UsersReadService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UsersReadService usersReadService;

    public MeController(UsersReadService usersReadService) {
        this.usersReadService = usersReadService;
    }

    @GetMapping
    public List<String> me(Authentication authentication) {
        return List.of(usersReadService.currentName(
            ((FfcrmAuthenticationToken) authentication).getAuthenticatedUser()));
    }
}
