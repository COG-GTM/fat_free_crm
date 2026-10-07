package com.fatfreecrm.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.api.support.CrmApiRequestSupport;
import com.fatfreecrm.service.read.CommentReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/comments")
@Tag(name = "comments", description = "Read operations for comments.")
public class CommentsController {

    private final CommentReadService commentReadService;
    private final CrmApiRequestSupport requestSupport;

    public CommentsController(CommentReadService commentReadService, CrmApiRequestSupport requestSupport) {
        this.commentReadService = commentReadService;
        this.requestSupport = requestSupport;
    }

    @GetMapping
    @Operation(summary = "List comments",
        description = "Lists a commentable's comments newest first (account_id, campaign_id, contact_id, lead_id, "
            + "opportunity_id, task_id or user_id), or the user's own comments without a commentable.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Comments."),
        @ApiResponse(responseCode = "400", description = "Unknown commentable parameter."),
        @ApiResponse(responseCode = "401", description = "Authentication is required."),
        @ApiResponse(responseCode = "404", description = "The commentable does not exist or is not visible.")
    })
    public List<ObjectNode> index(
        Authentication authentication,
        @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params
    ) {
        return commentReadService.list(requestSupport.authenticatedUser(authentication), params);
    }
}
