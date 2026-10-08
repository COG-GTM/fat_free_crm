package com.fatfreecrm.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import com.fatfreecrm.security.authz.AdminOnly;
import com.fatfreecrm.service.read.AdminReadService;
import com.fatfreecrm.service.read.UsersReadService;
import com.fatfreecrm.service.query.ListResult;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@AdminOnly
@RestController
@RequestMapping("/api/v1/admin")
public class AdminReadController {

    private final UsersReadService usersReadService;
    private final AdminReadService adminReadService;

    public AdminReadController(UsersReadService usersReadService, AdminReadService adminReadService) {
        this.usersReadService = usersReadService;
        this.adminReadService = adminReadService;
    }

    @GetMapping("/users")
    public ListResult<ObjectNode> users(
        Authentication authentication,
        @RequestParam MultiValueMap<String, String> params
    ) {
        return usersReadService.adminList(
            ((FfcrmAuthenticationToken) authentication).getAuthenticatedUser(), params);
    }

    @GetMapping("/users/{id:\\d+}")
    public List<String> user(@PathVariable long id) {
        return List.of(usersReadService.name(id));
    }

    @GetMapping("/groups/{id:\\d+}")
    public ObjectNode group(@PathVariable long id) {
        return adminReadService.group(id);
    }

    @GetMapping("/fields/{id:\\d+}")
    public ObjectNode field(@PathVariable long id) {
        return adminReadService.field(id);
    }

    @GetMapping("/tags")
    public List<ObjectNode> tags() {
        return adminReadService.tags();
    }

    @GetMapping("/research_tools")
    public List<ObjectNode> researchTools() {
        return adminReadService.researchTools();
    }
}
