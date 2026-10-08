package com.fatfreecrm.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.api.dto.AdminFieldGroupWriteRequest;
import com.fatfreecrm.api.dto.AdminFieldWriteRequest;
import com.fatfreecrm.api.dto.AdminGroupWriteRequest;
import com.fatfreecrm.api.dto.AdminResearchToolWriteRequest;
import com.fatfreecrm.api.dto.AdminSettingsWriteRequest;
import com.fatfreecrm.api.dto.AdminTagWriteRequest;
import com.fatfreecrm.api.dto.AdminUserWriteRequest;
import com.fatfreecrm.api.support.CrmApiRequestSupport;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.security.authz.AdminOnly;
import com.fatfreecrm.service.validation.RailsValidationException;
import com.fatfreecrm.service.write.RailsInternalError;
import com.fatfreecrm.service.write.RailsParams;
import com.fatfreecrm.service.write.admin.AdminFieldGroupWriteService;
import com.fatfreecrm.service.write.admin.AdminFieldWriteService;
import com.fatfreecrm.service.write.admin.AdminGroupWriteService;
import com.fatfreecrm.service.write.admin.AdminResearchToolWriteService;
import com.fatfreecrm.service.write.admin.AdminSettingsWriteService;
import com.fatfreecrm.service.write.admin.AdminTagWriteService;
import com.fatfreecrm.service.write.admin.AdminUserWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rails {@code app/controllers/admin/} JSON-reachable writes (AB-272 Phase B). Same {@link AdminOnly}
 * gate as the AB-270 admin reads (Rails redirects non-admins to {@code /}; Spring answers 403).
 * Where Rails persists and then fails on a missing URL helper/template, the service transaction
 * commits first and the controller raises {@link RailsInternalError} (500) afterwards.
 */
@AdminOnly
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "admin-writes", description = "Admin write operations (users, groups, tags, fields, settings).")
public class AdminWriteController {

    private final AdminUserWriteService users;
    private final AdminGroupWriteService groups;
    private final AdminTagWriteService tags;
    private final AdminResearchToolWriteService researchTools;
    private final AdminFieldGroupWriteService fieldGroups;
    private final AdminFieldWriteService fields;
    private final AdminSettingsWriteService settings;
    private final CrmApiRequestSupport requestSupport;

    public AdminWriteController(
        AdminUserWriteService users,
        AdminGroupWriteService groups,
        AdminTagWriteService tags,
        AdminResearchToolWriteService researchTools,
        AdminFieldGroupWriteService fieldGroups,
        AdminFieldWriteService fields,
        AdminSettingsWriteService settings,
        CrmApiRequestSupport requestSupport
    ) {
        this.users = users;
        this.groups = groups;
        this.tags = tags;
        this.researchTools = researchTools;
        this.fieldGroups = fieldGroups;
        this.fields = fields;
        this.settings = settings;
        this.requestSupport = requestSupport;
    }

    // users ---------------------------------------------------------------------------------

    @PostMapping("/users")
    @Operation(summary = "Create a user", description = "Mirrors Admin::UsersController#create (201 + [name]).")
    public ResponseEntity<ArrayNode> createUser(Authentication authentication,
        @RequestBody(required = false) AdminUserWriteRequest request) {
        User user = users.create(requestSupport.authenticatedUser(authentication), userParams(request));
        ArrayNode body = JsonNodeFactory.instance.arrayNode();
        String first = user.getFirstName();
        body.add(first == null || first.isBlank() ? user.getUsername() : first);
        return ResponseEntity.created(URI.create("/users/" + user.getId())).body(body);
    }

    @PutMapping("/users/{id:\\d+}")
    @Operation(summary = "Update a user (partial)")
    public ResponseEntity<Void> updateUser(Authentication authentication, @PathVariable long id,
        @RequestBody(required = false) AdminUserWriteRequest request) {
        users.update(requestSupport.authenticatedUser(authentication), id, userParams(request));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{id:\\d+}")
    @Operation(summary = "Destroy a user", description = "204 even when destroyable? guards block it.")
    public ResponseEntity<Void> destroyUser(Authentication authentication, @PathVariable long id) {
        users.destroy(requestSupport.authenticatedUser(authentication), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{id:\\d+}/suspend")
    @Operation(summary = "Suspend a user (no-op for self)")
    public ResponseEntity<Void> suspendUser(Authentication authentication, @PathVariable long id) {
        users.suspend(requestSupport.authenticatedUser(authentication), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{id:\\d+}/reactivate")
    @Operation(summary = "Reactivate a user")
    public ResponseEntity<Void> reactivateUser(Authentication authentication, @PathVariable long id) {
        users.reactivate(requestSupport.authenticatedUser(authentication), id);
        return ResponseEntity.noContent().build();
    }

    // groups / tags / research tools ------------------------------------------------------------

    @PostMapping("/groups")
    @Operation(summary = "Create a group", description = "Commits, then Rails 500s on group_url.")
    public ResponseEntity<Void> createGroup(@RequestBody(required = false) AdminGroupWriteRequest request) {
        groups.create(RailsParams.require(request == null ? null : request.group(), "group"));
        throw new RailsInternalError("undefined method 'group_url'");
    }

    @PutMapping("/groups/{id:\\d+}")
    @Operation(summary = "Update a group (partial)")
    public ResponseEntity<Void> updateGroup(@PathVariable long id,
        @RequestBody(required = false) AdminGroupWriteRequest request) {
        groups.update(id, RailsParams.require(request == null ? null : request.group(), "group"));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/groups/{id:\\d+}")
    @Operation(summary = "Destroy a group")
    public ResponseEntity<Void> destroyGroup(@PathVariable long id) {
        groups.destroy(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tags")
    @Operation(summary = "Create a tag", description = "Commits, then Rails 500s on tag_url.")
    public ResponseEntity<Void> createTag(@RequestBody(required = false) AdminTagWriteRequest request) {
        tags.create(RailsParams.require(request == null ? null : request.tag(), "tag"));
        throw new RailsInternalError("undefined method 'tag_url'");
    }

    @PutMapping("/tags/{id:\\d+}")
    @Operation(summary = "Update a tag (partial)")
    public ResponseEntity<Void> updateTag(@PathVariable long id,
        @RequestBody(required = false) AdminTagWriteRequest request) {
        tags.update(id, RailsParams.require(request == null ? null : request.tag(), "tag"));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/tags/{id:\\d+}")
    @Operation(summary = "Destroy a tag")
    public ResponseEntity<Void> destroyTag(@PathVariable long id) {
        tags.destroy(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/research_tools")
    @Operation(summary = "Create a research tool", description = "201 + full row JSON.")
    public ResponseEntity<ObjectNode> createResearchTool(
        @RequestBody(required = false) AdminResearchToolWriteRequest request) {
        ObjectNode body = researchTools.create(
            RailsParams.require(request == null ? null : request.researchTool(), "research_tool"));
        return ResponseEntity.created(URI.create("/admin/research_tools")).body(body);
    }

    @PutMapping("/research_tools/{id:\\d+}")
    @Operation(summary = "Update a research tool (partial)")
    public ResponseEntity<Void> updateResearchTool(@PathVariable long id,
        @RequestBody(required = false) AdminResearchToolWriteRequest request) {
        researchTools.update(id,
            RailsParams.require(request == null ? null : request.researchTool(), "research_tool"));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/research_tools/{id:\\d+}")
    @Operation(summary = "Destroy a research tool")
    public ResponseEntity<Void> destroyResearchTool(@PathVariable long id) {
        researchTools.destroy(id);
        return ResponseEntity.noContent().build();
    }

    // field groups / fields -----------------------------------------------------------------------

    @PostMapping("/field_groups")
    @Operation(summary = "Create a field group", description = "Commits, then Rails 500s on field_group_url.")
    public ResponseEntity<Void> createFieldGroup(
        @RequestBody(required = false) AdminFieldGroupWriteRequest request) {
        fieldGroups.create(RailsParams.require(request == null ? null : request.fieldGroup(), "field_group"));
        throw new RailsInternalError("undefined method 'field_group_url'");
    }

    @PutMapping("/field_groups/{id:\\d+}")
    @Operation(summary = "Update a field group (partial)")
    public ResponseEntity<Void> updateFieldGroup(@PathVariable long id,
        @RequestBody(required = false) AdminFieldGroupWriteRequest request) {
        fieldGroups.update(id,
            RailsParams.require(request == null ? null : request.fieldGroup(), "field_group"));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/field_groups/{id:\\d+}")
    @Operation(summary = "Destroy a field group", description = "Moves fields to the klass's custom_fields group.")
    public ResponseEntity<Void> destroyFieldGroup(@PathVariable long id) {
        fieldGroups.destroy(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/field_groups/sort")
    @Operation(summary = "Sort field groups", description = "Commits, then Rails 500s (render nothing:).")
    public ResponseEntity<Void> sortFieldGroups(@RequestBody(required = false) Map<String, JsonNode> body) {
        fieldGroups.sort(body);
        throw new RailsInternalError("ActionView::MissingTemplate admin/field_groups/sort");
    }

    @PostMapping("/fields")
    @Operation(summary = "Create a custom field", description = "Runs ALTER TABLE ADD cf_*; commits, then 500s.")
    public ResponseEntity<Void> createField(@RequestBody(required = false) AdminFieldWriteRequest request) {
        return respond(fields.create(request == null ? null : request.field(),
            request == null ? null : request.pair()));
    }

    @PutMapping("/fields/{id:\\d+}")
    @Operation(summary = "Update a field", description = "Safe type transitions run ALTER COLUMN TYPE.")
    public ResponseEntity<Void> updateField(@PathVariable long id,
        @RequestBody(required = false) AdminFieldWriteRequest request) {
        return respond(fields.update(id, request == null ? null : request.field(),
            request == null ? null : request.pair()));
    }

    @DeleteMapping("/fields/{id:\\d+}")
    @Operation(summary = "Destroy a field", description = "The cf_* column is kept, like Rails.")
    public ResponseEntity<Void> destroyField(@PathVariable long id) {
        fields.destroy(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/fields/sort")
    @Operation(summary = "Sort fields", description = "Commits, then Rails 500s (render nothing:).")
    public ResponseEntity<Void> sortFields(@RequestBody(required = false) Map<String, JsonNode> body) {
        fields.sort(body);
        throw new RailsInternalError("ActionView::MissingTemplate admin/fields/sort");
    }

    // settings ----------------------------------------------------------------------------------------

    @PutMapping("/settings")
    @Operation(summary = "Update settings", description = "Psych-YAML settings.value; 302 → /admin/settings.")
    public ResponseEntity<Void> updateSettings(
        @RequestBody(required = false) AdminSettingsWriteRequest request) {
        settings.update(request == null ? null : request.settings());
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/admin/settings"))
            .contentType(MediaType.parseMediaType("text/html;charset=utf-8")).build();
    }

    private static RailsParams userParams(AdminUserWriteRequest request) {
        return RailsParams.of(request == null || request.user() == null ? Map.of() : request.user());
    }

    /** Outcome committed by the service; Rails then answers 422 (errors) or 500 (helper defect). */
    private static ResponseEntity<Void> respond(AdminFieldWriteService.Outcome outcome) {
        if (!outcome.errors().isEmpty()) {
            throw new RailsValidationException(outcome.errors());
        }
        if (outcome.internalError() != null) {
            throw new RailsInternalError(outcome.internalError());
        }
        return ResponseEntity.noContent().build();
    }
}
