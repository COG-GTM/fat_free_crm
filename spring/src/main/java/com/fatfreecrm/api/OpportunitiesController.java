package com.fatfreecrm.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.api.dto.AutocompleteResponse;
import com.fatfreecrm.api.support.CrmApiRequestSupport;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.query.ListResult;
import com.fatfreecrm.service.read.CrmReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/opportunities")
@Tag(name = "opportunities", description = "Read operations for CRM opportunities.")
public class OpportunitiesController {

    private final CrmReadService crmReadService;
    private final RailsResources railsResources;
    private final CrmApiRequestSupport requestSupport;

    public OpportunitiesController(
        CrmReadService crmReadService,
        RailsResources railsResources,
        CrmApiRequestSupport requestSupport
    ) {
        this.crmReadService = crmReadService;
        this.railsResources = railsResources;
        this.requestSupport = requestSupport;
    }

    @GetMapping
    @Operation(summary = "List opportunities", description = "Returns a paginated, access-scoped opportunity list.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Opportunity list and pagination metadata."),
        @ApiResponse(responseCode = "401", description = "Authentication is required.")
    })
    public ListResult<ObjectNode> index(
        Authentication authentication,
        @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params,
        @RequestParam(required = false) String stage
    ) {
        AuthenticatedUser user = requestSupport.authenticatedUser(authentication);
        return crmReadService.list(
            user,
            railsResources.opportunity,
            requestSupport.listQuery(user, railsResources.opportunity, params, stage)
        );
    }

    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Opportunity', 'read')")
    @Operation(summary = "Show an opportunity", description = "Returns an opportunity and records a view event.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The requested opportunity."),
        @ApiResponse(responseCode = "401", description = "Authentication is required."),
        @ApiResponse(responseCode = "403", description = "The opportunity is outside the user's access scope."),
        @ApiResponse(responseCode = "404", description = "The opportunity does not exist.")
    })
    public ObjectNode show(Authentication authentication, @PathVariable long id) {
        return crmReadService.show(requestSupport.authenticatedUser(authentication), railsResources.opportunity, id);
    }

    @GetMapping("/autocomplete")
    @Operation(summary = "Autocomplete opportunities", description = "Returns up to ten accessible opportunities.")
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
        return new AutocompleteResponse(crmReadService.autocomplete(
            requestSupport.authenticatedUser(authentication), railsResources.opportunity, term, exclusion
        ).results());
    }
}
