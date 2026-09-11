package com.fatfreecrm.api;

import com.fatfreecrm.security.CurrentUser;
import com.fatfreecrm.security.CurrentUserProvider;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Smoke-test endpoint for the authentication shim: echoes the resolved {@link CurrentUser}.
 * Not part of the frozen OpenAPI contract; may be removed once real resources exist.
 */
@RestController
@RequestMapping("/api/v1/ping")
public class PingController {

    private final CurrentUserProvider currentUserProvider;

    public PingController(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public PingResponse ping() {
        CurrentUser user = currentUserProvider.requireCurrentUser();
        return new PingResponse(user.id(), user.admin(), user.groupIds());
    }

    public record PingResponse(Long userId, boolean admin, Set<Long> groupIds) {
    }
}
