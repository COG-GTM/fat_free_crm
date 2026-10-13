package com.fatfreecrm.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.api.dto.TaskWriteRequest;
import com.fatfreecrm.api.support.CrmApiRequestSupport;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.read.TaskReadService;
import com.fatfreecrm.service.write.RailsParams;
import com.fatfreecrm.service.write.TaskWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rails {@code TasksController} JSON writes (AB-272). Create carries no authorization like Rails;
 * update/destroy/complete/uncomplete apply the same {@code hasPermission} check as the AB-270 show
 * endpoint, then the {@code Task.tracked_by} scope inside the service (404 outside the scope).
 */
@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "tasks", description = "Write operations for tasks.")
public class TasksWriteController {

    private final TaskWriteService taskWriteService;
    private final TaskReadService taskReadService;
    private final CrmApiRequestSupport requestSupport;

    public TasksWriteController(
        TaskWriteService taskWriteService,
        TaskReadService taskReadService,
        CrmApiRequestSupport requestSupport
    ) {
        this.taskWriteService = taskWriteService;
        this.taskReadService = taskReadService;
        this.requestSupport = requestSupport;
    }

    @PostMapping
    @Operation(summary = "Create a task", description = "Mirrors Rails TasksController#create.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Task created."),
        @ApiResponse(responseCode = "401", description = "Authentication is required."),
        @ApiResponse(responseCode = "422", description = "Validation failed.")
    })
    public ResponseEntity<ObjectNode> create(
        Authentication authentication,
        @RequestBody TaskWriteRequest request,
        @RequestParam(required = false) String timeZone
    ) {
        AuthenticatedUser user = requestSupport.authenticatedUser(authentication);
        RailsParams params = RailsParams.of(request == null ? null : request.task());
        ObjectNode body = taskWriteService.create(user, params, taskReadService.zone(timeZone));
        return ResponseEntity.created(URI.create("/api/v1/tasks/" + body.path("id").asLong()))
            .body(body);
    }

    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Task', 'update')")
    @Operation(summary = "Update a task", description = "Partial update scoped to tracked tasks.")
    public ResponseEntity<Void> update(
        Authentication authentication,
        @PathVariable long id,
        @RequestBody TaskWriteRequest request,
        @RequestParam(required = false) String timeZone
    ) {
        taskWriteService.update(requestSupport.authenticatedUser(authentication), id,
            RailsParams.of(request == null ? null : request.task()), taskReadService.zone(timeZone));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Task', 'destroy')")
    @Operation(summary = "Delete a task")
    public ResponseEntity<Void> destroy(Authentication authentication, @PathVariable long id) {
        taskWriteService.destroy(requestSupport.authenticatedUser(authentication), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id:\\d+}/complete")
    @PreAuthorize("hasPermission(#id, 'Task', 'update')")
    @Operation(summary = "Complete a task")
    public ResponseEntity<Void> complete(Authentication authentication, @PathVariable long id) {
        taskWriteService.complete(requestSupport.authenticatedUser(authentication), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id:\\d+}/uncomplete")
    @PreAuthorize("hasPermission(#id, 'Task', 'update')")
    @Operation(summary = "Uncomplete a task")
    public ResponseEntity<Void> uncomplete(
        Authentication authentication,
        @PathVariable long id,
        @RequestParam(required = false) String timeZone
    ) {
        taskWriteService.uncomplete(requestSupport.authenticatedUser(authentication), id,
            taskReadService.zone(timeZone));
        return ResponseEntity.noContent().build();
    }
}
