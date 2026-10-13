package com.fatfreecrm.service.mail;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.CrmEntity;
import com.fatfreecrm.service.jobs.RailsJobEnqueuer;
import org.springframework.stereotype.Service;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are intentionally retained by this service."
)
public class AssignmentNotificationService {

    private final MailSettingsService settings;
    private final RailsJobEnqueuer enqueuer;

    public AssignmentNotificationService(
        MailSettingsService settings,
        RailsJobEnqueuer enqueuer
    ) {
        this.settings = settings;
        this.enqueuer = enqueuer;
    }

    public void afterCreate(CrmEntity entity, User currentUser) {
        User assignee = entity.getAssignedTo();
        if (assignee != null && (currentUser == null || !assignee.getId().equals(currentUser.getId()))) {
            enqueueIfEligible(entity, currentUser);
        }
    }

    public void afterUpdate(CrmEntity entity, Long previousAssignedToId, User currentUser) {
        User assignee = entity.getAssignedTo();
        if (assignee != null
            && !java.util.Objects.equals(previousAssignedToId, assignee.getId())
            && (currentUser == null || !assignee.getId().equals(currentUser.getId()))) {
            enqueueIfEligible(entity, currentUser);
        }
    }

    private void enqueueIfEligible(CrmEntity entity, User currentUser) {
        if (!(entity instanceof com.fatfreecrm.domain.Account
            || entity instanceof com.fatfreecrm.domain.Contact
            || entity instanceof com.fatfreecrm.domain.Lead
            || entity instanceof com.fatfreecrm.domain.Opportunity)) {
            return;
        }
        User assignee = entity.getAssignedTo();
        if (currentUser != null && assignee.isReceiveAssignedNotifications() && !settings.host().isBlank()) {
            enqueuer.deliverLater("UserMailer", "assigned_entity_notification", entity, currentUser);
        }
    }
}
