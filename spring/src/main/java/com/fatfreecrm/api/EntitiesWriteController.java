package com.fatfreecrm.api;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.api.dto.EntityWriteRequest;
import com.fatfreecrm.api.support.CrmApiRequestSupport;
import com.fatfreecrm.service.write.EntityWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rails {@code EntitiesController} JSON writes for the five CRM families (AB-272 Phase B):
 * create/update/destroy plus attach/discard/subscribe/unsubscribe; leads also expose
 * promote/reject/convert. Mirrors Rails member-write authorization via
 * {@code hasPermission(#id,'<Model>','update'|'destroy')}.
 */
@RestController
@RequestMapping("/api/v1")
@SuppressFBWarnings(value = "EI_EXPOSE_REP2",
    justification = "Spring-injected collaborators; mirrors TasksWriteController wiring")
@Tag(name = "entities", description = "Write operations for CRM entities.")
public class EntitiesWriteController {

    private final EntityWriteService service;
    private final CrmApiRequestSupport requestSupport;

    public EntitiesWriteController(EntityWriteService service, CrmApiRequestSupport requestSupport) {
        this.service = service;
        this.requestSupport = requestSupport;
    }

    private static JsonNode body(EntityWriteRequest request) {
        return request == null
            ? null
            : new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(request);
    }

    private ResponseEntity<ObjectNode> created(String path, ObjectNode body) {
        return ResponseEntity.created(URI.create(path)).body(body);
    }

    // ------------------------------------------------------------- accounts

    @PostMapping("/accounts")
    @Operation(summary = "Create an account", description = "Mirrors Rails AccountsController#create.")
    public ResponseEntity<ObjectNode> createAccount(
        Authentication authentication, @RequestBody EntityWriteRequest request) {
        ObjectNode result = service.createAccount(
            requestSupport.authenticatedUser(authentication), body(request));
        return created("/api/v1/accounts/" + result.path("id").asLong(), result);
    }

    @PutMapping("/accounts/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Account', 'update')")
    @Operation(summary = "Update an account")
    public ResponseEntity<Void> updateAccount(
        Authentication authentication, @PathVariable long id,
        @RequestBody EntityWriteRequest request) {
        service.updateAccount(requestSupport.authenticatedUser(authentication), id, body(request));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/accounts/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Account', 'destroy')")
    @Operation(summary = "Delete an account")
    public ResponseEntity<Void> destroyAccount(Authentication authentication, @PathVariable long id) {
        service.destroy(requestSupport.authenticatedUser(authentication), id, "accounts");
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- campaigns

    @PostMapping("/campaigns")
    @Operation(summary = "Create a campaign")
    public ResponseEntity<ObjectNode> createCampaign(
        Authentication authentication, @RequestBody EntityWriteRequest request) {
        ObjectNode result = service.createCampaign(
            requestSupport.authenticatedUser(authentication), body(request));
        return created("/api/v1/campaigns/" + result.path("id").asLong(), result);
    }

    @PutMapping("/campaigns/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Campaign', 'update')")
    @Operation(summary = "Update a campaign")
    public ResponseEntity<Void> updateCampaign(
        Authentication authentication, @PathVariable long id,
        @RequestBody EntityWriteRequest request) {
        service.updateCampaign(requestSupport.authenticatedUser(authentication), id, body(request));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/campaigns/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Campaign', 'destroy')")
    @Operation(summary = "Delete a campaign")
    public ResponseEntity<Void> destroyCampaign(Authentication authentication, @PathVariable long id) {
        service.destroy(requestSupport.authenticatedUser(authentication), id, "campaigns");
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- contacts

    @PostMapping("/contacts")
    @Operation(summary = "Create a contact")
    public ResponseEntity<ObjectNode> createContact(
        Authentication authentication, @RequestBody EntityWriteRequest request) {
        ObjectNode result = service.createContact(
            requestSupport.authenticatedUser(authentication), body(request));
        return created("/api/v1/contacts/" + result.path("id").asLong(), result);
    }

    @PutMapping("/contacts/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Contact', 'update')")
    @Operation(summary = "Update a contact")
    public ResponseEntity<Void> updateContact(
        Authentication authentication, @PathVariable long id,
        @RequestBody EntityWriteRequest request) {
        service.updateContact(requestSupport.authenticatedUser(authentication), id, body(request));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/contacts/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Contact', 'destroy')")
    @Operation(summary = "Delete a contact")
    public ResponseEntity<Void> destroyContact(Authentication authentication, @PathVariable long id) {
        service.destroy(requestSupport.authenticatedUser(authentication), id, "contacts");
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- leads

    @PostMapping("/leads")
    @Operation(summary = "Create a lead")
    public ResponseEntity<ObjectNode> createLead(
        Authentication authentication, @RequestBody EntityWriteRequest request) {
        ObjectNode result = service.createLead(
            requestSupport.authenticatedUser(authentication), body(request));
        return created("/api/v1/leads/" + result.path("id").asLong(), result);
    }

    @PutMapping("/leads/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Lead', 'update')")
    @Operation(summary = "Update a lead")
    public ResponseEntity<Void> updateLead(
        Authentication authentication, @PathVariable long id,
        @RequestBody EntityWriteRequest request) {
        service.updateLead(requestSupport.authenticatedUser(authentication), id, body(request));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/leads/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Lead', 'destroy')")
    @Operation(summary = "Delete a lead")
    public ResponseEntity<Void> destroyLead(Authentication authentication, @PathVariable long id) {
        service.destroy(requestSupport.authenticatedUser(authentication), id, "leads");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/leads/{id:\\d+}/convert")
    @PreAuthorize("hasPermission(#id, 'Lead', 'update')")
    @Operation(summary = "Render the lead convert form data")
    public ResponseEntity<ObjectNode> convertLead(@PathVariable long id) {
        return ResponseEntity.ok(service.convertLead(id));
    }

    @RequestMapping(
        value = "/leads/{id:\\d+}/promote",
        method = {org.springframework.web.bind.annotation.RequestMethod.PUT,
            org.springframework.web.bind.annotation.RequestMethod.PATCH})
    @PreAuthorize("hasPermission(#id, 'Lead', 'update')")
    @Operation(summary = "Promote a lead")
    public ResponseEntity<Void> promoteLead(
        Authentication authentication, @PathVariable long id,
        @RequestBody(required = false) EntityWriteRequest request) {
        service.promoteLead(requestSupport.authenticatedUser(authentication), id, body(request));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/leads/{id:\\d+}/reject")
    @PreAuthorize("hasPermission(#id, 'Lead', 'update')")
    @Operation(summary = "Reject a lead")
    public ResponseEntity<Void> rejectLead(Authentication authentication, @PathVariable long id) {
        service.rejectLead(requestSupport.authenticatedUser(authentication), id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- opportunities

    @PostMapping("/opportunities")
    @Operation(summary = "Create an opportunity")
    public ResponseEntity<ObjectNode> createOpportunity(
        Authentication authentication, @RequestBody EntityWriteRequest request) {
        ObjectNode result = service.createOpportunity(
            requestSupport.authenticatedUser(authentication), body(request));
        return created("/api/v1/opportunities/" + result.path("id").asLong(), result);
    }

    @PutMapping("/opportunities/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Opportunity', 'update')")
    @Operation(summary = "Update an opportunity")
    public ResponseEntity<Void> updateOpportunity(
        Authentication authentication, @PathVariable long id,
        @RequestBody EntityWriteRequest request) {
        service.updateOpportunity(requestSupport.authenticatedUser(authentication), id,
            body(request));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/opportunities/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Opportunity', 'destroy')")
    @Operation(summary = "Delete an opportunity")
    public ResponseEntity<Void> destroyOpportunity(Authentication authentication,
        @PathVariable long id) {
        service.destroy(requestSupport.authenticatedUser(authentication), id, "opportunities");
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- shared member actions

    private static final String FAMILIES = "accounts|campaigns|contacts|leads|opportunities";

    @PutMapping("/{family:" + FAMILIES + "}/{id:\\d+}/attach")
    @Operation(summary = "Attach a related asset")
    public ResponseEntity<Void> attach(
        Authentication authentication, @PathVariable String family, @PathVariable long id,
        @RequestParam String assets, @RequestParam Long asset_id) {
        String model = modelName(family);
        authorize(authentication, id, model);
        service.attach(requestSupport.authenticatedUser(authentication), family, id, assets,
            asset_id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{family:" + FAMILIES + "}/{id:\\d+}/discard")
    @Operation(summary = "Discard a related asset")
    public ResponseEntity<ObjectNode> discard(
        Authentication authentication, @PathVariable String family, @PathVariable long id,
        @RequestParam String attachment, @RequestParam Long attachment_id) {
        authorize(authentication, id, modelName(family));
        ObjectNode result = service.discard(requestSupport.authenticatedUser(authentication),
            family, id, attachment, attachment_id);
        return ResponseEntity.status(201).body(result);
    }

    @PostMapping("/{family:" + FAMILIES + "}/{id:\\d+}/subscribe")
    @Operation(summary = "Subscribe the current user (Rails 500 quirk)")
    public ResponseEntity<Void> subscribe(
        Authentication authentication, @PathVariable String family, @PathVariable long id) {
        authorize(authentication, id, modelName(family));
        service.subscribe(requestSupport.authenticatedUser(authentication), family, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{family:" + FAMILIES + "}/{id:\\d+}/unsubscribe")
    @Operation(summary = "Unsubscribe the current user")
    public ResponseEntity<ObjectNode> unsubscribe(
        Authentication authentication, @PathVariable String family, @PathVariable long id) {
        authorize(authentication, id, modelName(family));
        ObjectNode result = service.unsubscribe(requestSupport.authenticatedUser(authentication),
            family, id);
        return ResponseEntity.status(201).body(result);
    }

    private String modelName(String family) {
        return switch (family) {
            case "accounts" -> "Account";
            case "campaigns" -> "Campaign";
            case "contacts" -> "Contact";
            case "leads" -> "Lead";
            case "opportunities" -> "Opportunity";
            default -> throw new IllegalArgumentException(family);
        };
    }

    /** Same hasPermission update check the single-family mappings declare via @PreAuthorize. */
    private void authorize(Authentication authentication, long id, String model) {
        service.authorize(requestSupport.authenticatedUser(authentication), id, model);
    }
}
