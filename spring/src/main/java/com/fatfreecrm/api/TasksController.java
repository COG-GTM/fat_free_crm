package com.fatfreecrm.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.api.dto.AutocompleteResponse;
import com.fatfreecrm.api.dto.TaskBucketsResponse;
import com.fatfreecrm.api.support.CrmApiRequestSupport;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.read.TaskReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "tasks", description = "Read operations for tasks.")
public class TasksController {

    private final TaskReadService taskReadService;
    private final CrmApiRequestSupport requestSupport;

    public TasksController(TaskReadService taskReadService, CrmApiRequestSupport requestSupport) {
        this.taskReadService = taskReadService;
        this.requestSupport = requestSupport;
    }

    @GetMapping
    @Operation(summary = "List tasks by bucket",
        description = "Returns the Rails pending, assigned or completed task buckets in the requested time zone.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Task buckets in Rails Setting order."),
        @ApiResponse(responseCode = "400", description = "Unknown time zone."),
        @ApiResponse(responseCode = "401", description = "Authentication is required.")
    })
    public TaskBucketsResponse index(
        Authentication authentication,
        @Parameter(description = "pending (default), assigned or completed")
        @RequestParam(required = false) String view,
        @Parameter(description = "IANA zone for bucket boundaries; defaults to ffcrm.time-zone (UTC)")
        @RequestParam(required = false) String timeZone
    ) {
        AuthenticatedUser user = requestSupport.authenticatedUser(authentication);
        return new TaskBucketsResponse(taskReadService.buckets(user, view, taskReadService.zone(timeZone)));
    }

    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Task', 'read')")
    @Operation(summary = "Show a task", description = "Returns a task the user created or is assigned to.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The requested task."),
        @ApiResponse(responseCode = "401", description = "Authentication is required."),
        @ApiResponse(responseCode = "403", description = "The task is outside the user's access scope."),
        @ApiResponse(responseCode = "404", description = "The task does not exist or is not tracked by the user.")
    })
    public ObjectNode show(Authentication authentication, @PathVariable long id) {
        return taskReadService.show(requestSupport.authenticatedUser(authentication), id);
    }

    @GetMapping("/autocomplete")
    @Operation(summary = "Autocomplete tasks", description = "Returns up to ten of the user's tasks by name.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Autocomplete results."),
        @ApiResponse(responseCode = "401", description = "Authentication is required.")
    })
    public AutocompleteResponse autocomplete(
        Authentication authentication,
        @RequestParam(defaultValue = "") String term,
        @RequestParam(required = false) String excludeRelated,
        @RequestParam(required = false) String related
    ) {
        String exclusion = excludeRelated != null ? excludeRelated : related;
        return new AutocompleteResponse(taskReadService.autocomplete(
            requestSupport.authenticatedUser(authentication), term, taskReadService.excludedIds(exclusion)
        ).results());
    }
}
