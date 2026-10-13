package com.fatfreecrm.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.api.dto.ListWriteRequest;
import com.fatfreecrm.api.support.CrmApiRequestSupport;
import com.fatfreecrm.service.write.ListWriteService;
import com.fatfreecrm.service.write.RailsParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rails {@code ListsController} — no authorization at all (mirrored Rails gap; see ADR open
 * questions). Create is a case-insensitive-name upsert keyed by user_id, always 201.
 */
@RestController
@RequestMapping("/api/v1/lists")
@Tag(name = "lists", description = "Write operations for saved lists.")
public class ListsController {

    private final ListWriteService listWriteService;
    private final CrmApiRequestSupport requestSupport;

    public ListsController(ListWriteService listWriteService, CrmApiRequestSupport requestSupport) {
        this.listWriteService = listWriteService;
        this.requestSupport = requestSupport;
    }

    @PostMapping
    @Operation(summary = "Create or update a saved list",
        description = "Upsert by lower(name)+user_id; is_global != \"1\" forces user_id = current user.")
    public ResponseEntity<ObjectNode> create(
        Authentication authentication,
        @RequestBody ListWriteRequest request
    ) {
        ObjectNode body = listWriteService.create(
            requestSupport.authenticatedUser(authentication),
            RailsParams.of(request == null ? null : request.list()),
            request != null && "1".equals(request.is_global()));
        return ResponseEntity.created(URI.create("/api/v1/lists/" + body.path("id").asLong()))
            .body(body);
    }

    @DeleteMapping("/{id:\\d+}")
    @Operation(summary = "Delete a saved list", description = "Any user, mirroring Rails.")
    public ResponseEntity<Void> destroy(@PathVariable long id) {
        listWriteService.destroy(id);
        return ResponseEntity.noContent().build();
    }
}
