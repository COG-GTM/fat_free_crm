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
@RequestMapping("/api/v1/accounts")
@Tag(name = "accounts", description = "Read operations for CRM accounts.")
public class AccountsController {

    private final CrmReadService crmReadService;
    private final RailsResources railsResources;
    private final CrmApiRequestSupport requestSupport;

    public AccountsController(
        CrmReadService crmReadService,
        RailsResources railsResources,
        CrmApiRequestSupport requestSupport
    ) {
        this.crmReadService = crmReadService;
        this.railsResources = railsResources;
        this.requestSupport = requestSupport;
    }

    @GetMapping
    @Operation(summary = "List accounts", description = "Returns a paginated, access-scoped account list.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Account list and pagination metadata."),
        @ApiResponse(responseCode = "401", description = "Authentication is required.")
    })
    public ListResult<ObjectNode> index(
        Authentication authentication,
        @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params,
        @RequestParam(required = false) String category
    ) {
        AuthenticatedUser user = requestSupport.authenticatedUser(authentication);
        return crmReadService.list(
            user,
            railsResources.account,
            requestSupport.listQuery(user, railsResources.account, params, category)
        );
    }

    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Account', 'read')")
    @Operation(summary = "Show an account", description = "Returns an account and records a view event.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The requested account."),
        @ApiResponse(responseCode = "401", description = "Authentication is required."),
        @ApiResponse(responseCode = "403", description = "The account is outside the user's access scope."),
        @ApiResponse(responseCode = "404", description = "The account does not exist.")
    })
    public ObjectNode show(Authentication authentication, @PathVariable long id) {
        return crmReadService.show(requestSupport.authenticatedUser(authentication), railsResources.account, id);
    }

    @GetMapping("/autocomplete")
    @Operation(summary = "Autocomplete accounts", description = "Returns up to ten accessible accounts.")
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
            requestSupport.authenticatedUser(authentication), railsResources.account, term, exclusion
        ).results());
    }
}
