package com.fatfreecrm.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.api.dto.AutocompleteResponse;
import com.fatfreecrm.api.support.CrmApiRequestSupport;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.query.ListQuery;
import com.fatfreecrm.service.query.ListResult;
import com.fatfreecrm.service.read.CrmReadService;
import com.fatfreecrm.service.vcard.VCardReadService;
import com.fatfreecrm.service.vcard.VCardResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/leads")
@Tag(name = "leads", description = "Read operations for CRM leads.")
public class LeadsController {

    private final CrmReadService crmReadService;
    private final VCardReadService vCardReadService;
    private final RailsResources railsResources;
    private final CrmApiRequestSupport requestSupport;

    public LeadsController(
        CrmReadService crmReadService,
        VCardReadService vCardReadService,
        RailsResources railsResources,
        CrmApiRequestSupport requestSupport
    ) {
        this.crmReadService = crmReadService;
        this.vCardReadService = vCardReadService;
        this.railsResources = railsResources;
        this.requestSupport = requestSupport;
    }

    @GetMapping
    @Operation(summary = "List leads", description = "Returns a paginated, access-scoped lead list.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lead list and pagination metadata."),
        @ApiResponse(responseCode = "401", description = "Authentication is required.")
    })
    public ListResult<ObjectNode> index(
        Authentication authentication,
        @RequestParam MultiValueMap<String, String> params,
        @RequestParam(required = false) String status
    ) {
        AuthenticatedUser user = requestSupport.authenticatedUser(authentication);
        MultiValueMap<String, String> railsParams = new LinkedMultiValueMap<>(params);
        railsParams.remove("per_page");
        ListQuery query = requestSupport.listQuery(user, railsResources.lead, railsParams, status);
        return crmReadService.list(user, railsResources.lead, query);
    }

    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Lead', 'read')")
    @Operation(summary = "Show a lead", description = "Returns a lead and records a view event.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The requested lead."),
        @ApiResponse(responseCode = "401", description = "Authentication is required."),
        @ApiResponse(responseCode = "403", description = "The lead is outside the user's access scope."),
        @ApiResponse(responseCode = "404", description = "The lead does not exist.")
    })
    public ObjectNode show(Authentication authentication, @PathVariable long id) {
        return crmReadService.show(requestSupport.authenticatedUser(authentication), railsResources.lead, id);
    }

    @GetMapping("/{id:\\d+}.vcf")
    @PreAuthorize("hasPermission(#id, 'Lead', 'read')")
    @Operation(summary = "Export a lead vCard", description = "Returns a Rails-compatible vCard attachment.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The requested lead vCard."),
        @ApiResponse(responseCode = "401", description = "Authentication is required."),
        @ApiResponse(responseCode = "403", description = "The lead is outside the user's access scope."),
        @ApiResponse(responseCode = "404", description = "The lead does not exist.")
    })
    public ResponseEntity<String> vcard(Authentication authentication, @PathVariable long id) {
        VCardResponse response = vCardReadService.leadVCard(
            requestSupport.authenticatedUser(authentication), id);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/x-vcard")
            .header(HttpHeaders.CONTENT_DISPOSITION, response.contentDisposition())
            .body(response.body());
    }

    @GetMapping("/autocomplete")
    @Operation(summary = "Autocomplete leads", description = "Returns up to ten accessible leads.")
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
            requestSupport.authenticatedUser(authentication), railsResources.lead, term, exclusion
        ).results());
    }
}
