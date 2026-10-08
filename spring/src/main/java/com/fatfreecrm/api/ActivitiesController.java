package com.fatfreecrm.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import com.fatfreecrm.service.read.ActivitiesReadService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/api/v1/activities")
public class ActivitiesController {

    private final ActivitiesReadService activitiesReadService;

    public ActivitiesController(ActivitiesReadService activitiesReadService) {
        this.activitiesReadService = activitiesReadService;
    }

    @GetMapping
    public List<ObjectNode> list(
        Authentication authentication,
        @RequestParam MultiValueMap<String, String> parameters
    ) {
        return activitiesReadService.list(
            ((FfcrmAuthenticationToken) authentication).getAuthenticatedUser(), parameters);
    }
}
