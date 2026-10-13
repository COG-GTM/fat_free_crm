package com.fatfreecrm.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.api.dto.CommentWriteRequest;
import com.fatfreecrm.api.support.CrmApiRequestSupport;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.write.CommentWriteService;
import com.fatfreecrm.service.write.RailsParams;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Rails {@code CommentsController} JSON writes (AB-272). Create authorizes inside the service via
 * the commentable's {@code .my} scope (404); update/destroy use CanCan owner-or-admin via
 * {@code hasPermission} (Rails 401 ≙ Spring 403, allow-listed globally).
 */
@RestController
@RequestMapping("/api/v1/comments")
@Tag(name = "comments", description = "Write operations for comments.")
public class CommentsWriteController {

    private final CommentWriteService commentWriteService;
    private final CrmApiRequestSupport requestSupport;

    public CommentsWriteController(
        CommentWriteService commentWriteService,
        CrmApiRequestSupport requestSupport
    ) {
        this.commentWriteService = commentWriteService;
        this.requestSupport = requestSupport;
    }

    @PostMapping
    @Operation(summary = "Create a comment",
        description = "Mirrors Rails CommentsController#create incl. mention subscriptions.")
    public ResponseEntity<ObjectNode> create(
        Authentication authentication,
        @RequestBody CommentWriteRequest request
    ) {
        AuthenticatedUser user = requestSupport.authenticatedUser(authentication);
        ObjectNode body = commentWriteService.create(user,
            RailsParams.of(request == null ? null : request.comment()));
        return ResponseEntity.created(URI.create("/api/v1/comments/" + body.path("id").asLong()))
            .body(body);
    }

    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Comment', 'update')")
    @Operation(summary = "Update a comment")
    public ResponseEntity<Void> update(
        Authentication authentication,
        @PathVariable long id,
        @RequestBody CommentWriteRequest request
    ) {
        commentWriteService.update(requestSupport.authenticatedUser(authentication), id,
            RailsParams.of(request == null ? null : request.comment()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Comment', 'destroy')")
    @Operation(summary = "Delete a comment")
    public ResponseEntity<Void> destroy(Authentication authentication, @PathVariable long id) {
        commentWriteService.destroy(requestSupport.authenticatedUser(authentication), id);
        return ResponseEntity.noContent().build();
    }
}
