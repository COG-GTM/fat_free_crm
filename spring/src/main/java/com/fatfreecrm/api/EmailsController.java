package com.fatfreecrm.api;

import com.fatfreecrm.api.support.CrmApiRequestSupport;
import com.fatfreecrm.service.write.EmailWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rails {@code EmailsController} — the only JSON write is {@code DELETE /emails/:id} (AB-272). */
@RestController
@RequestMapping("/api/v1/emails")
@Tag(name = "emails", description = "Write operations for emails.")
public class EmailsController {

    private final EmailWriteService emailWriteService;
    private final CrmApiRequestSupport requestSupport;

    public EmailsController(EmailWriteService emailWriteService, CrmApiRequestSupport requestSupport) {
        this.emailWriteService = emailWriteService;
        this.requestSupport = requestSupport;
    }

    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasPermission(#id, 'Email', 'destroy')")
    @Operation(summary = "Delete an email",
        description = "CanCan owner-or-admin; records the destroy version with mediator related.")
    public ResponseEntity<Void> destroy(Authentication authentication, @PathVariable long id) {
        emailWriteService.destroy(requestSupport.authenticatedUser(authentication), id);
        return ResponseEntity.noContent().build();
    }
}
