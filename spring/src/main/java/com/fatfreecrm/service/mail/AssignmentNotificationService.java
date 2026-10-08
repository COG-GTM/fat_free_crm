package com.fatfreecrm.service.mail;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.CrmEntity;
import org.springframework.stereotype.Service;

@Service
public class AssignmentNotificationService {

    private final MailRendererService renderer;
    private final MailDeliveryScheduler deliveryScheduler;

    public AssignmentNotificationService(
        MailRendererService renderer,
        MailDeliveryScheduler deliveryScheduler
    ) {
        this.renderer = renderer;
        this.deliveryScheduler = deliveryScheduler;
    }

    public void afterCreate(CrmEntity entity, User currentUser) {
        if (!eligible(entity, currentUser)) {
            return;
        }
        User assignee = entity.getAssignedTo();
        String entityType = entity.getClass().getSimpleName();
        String route = entityType.toLowerCase(java.util.Locale.ROOT) + "s";
        String host = renderer.host();
        String url = host.isBlank() ? "" : host.replaceAll("/+$", "") + "/" + route + "/" + entity.getId();
        String entityName = entity.getClass().getSimpleName();
        if (entity instanceof com.fatfreecrm.domain.Account account) {
            entityName = account.getName();
        } else if (entity instanceof com.fatfreecrm.domain.Contact contact) {
            entityName = contact.getFirstName() + " " + contact.getLastName();
        } else if (entity instanceof com.fatfreecrm.domain.Lead lead) {
            entityName = lead.getFirstName() + " " + lead.getLastName();
        }
        deliveryScheduler.deliverLater(
            "UserMailer",
            "assigned_entity_notification",
            renderer.assignment(assignee.getEmail(), entityName, entityType, url, currentUserName(currentUser)));
    }

    private boolean eligible(CrmEntity entity, User currentUser) {
        return currentUser != null && entity.getAssignedTo() != null
            && !entity.getAssignedTo().getId().equals(currentUser.getId())
            && entity.getAssignedTo().isReceiveAssignedNotifications()
            && !renderer.host().isBlank()
            && entity.getAssignedTo().getEmail() != null;
    }

    private static String currentUserName(User user) {
        String firstName = user.getFirstName() == null ? "" : user.getFirstName();
        String lastName = user.getLastName() == null ? "" : user.getLastName();
        return (firstName + " " + lastName).trim();
    }
}
