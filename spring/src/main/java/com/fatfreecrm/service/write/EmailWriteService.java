package com.fatfreecrm.service.write;

import com.fatfreecrm.domain.Email;
import com.fatfreecrm.repository.EmailRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.audit.EntityAttributes;
import com.fatfreecrm.service.audit.VersionRecorder;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rails {@code EmailsController#destroy} (the only JSON write on emails): CanCan owner-or-admin
 * via {@code hasPermission}, PaperTrail {@code meta: {related: :mediator}, ignore: [:state]}.
 */
@Service
public class EmailWriteService {

    private final EmailRepository emailRepository;
    private final VersionRecorder versionRecorder;

    public EmailWriteService(EmailRepository emailRepository, VersionRecorder versionRecorder) {
        this.emailRepository = emailRepository;
        this.versionRecorder = versionRecorder;
    }

    @Transactional
    public void destroy(AuthenticatedUser user, long id) {
        Email email = emailRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Email " + id + " was not found"));
        versionRecorder.recordDestroy(user, email, EntityAttributes.of(email));
        emailRepository.delete(email);
    }
}
