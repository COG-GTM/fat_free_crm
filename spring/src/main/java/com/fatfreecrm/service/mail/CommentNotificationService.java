package com.fatfreecrm.service.mail;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.CrmEntity;
import com.fatfreecrm.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed EntityManager is intentionally retained by this service."
)
public class CommentNotificationService {

    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final MailRendererService renderer;
    private final MailDeliveryScheduler deliveryScheduler;

    public CommentNotificationService(
        UserRepository userRepository,
        EntityManager entityManager,
        MailRendererService renderer,
        MailDeliveryScheduler deliveryScheduler
    ) {
        this.userRepository = userRepository;
        this.entityManager = entityManager;
        this.renderer = renderer;
        this.deliveryScheduler = deliveryScheduler;
    }

    public void afterCreate(Comment comment) {
        CrmEntity entity = (CrmEntity) entityManager.find(
            entityClass(comment.getCommentableType()), comment.getCommentableId());
        if (entity == null || entity.getSubscribedUsers() == null) {
            return;
        }
        List<User> subscribers = userRepository.findAllById(entity.getSubscribedUsers());
        String entityName = entity instanceof com.fatfreecrm.domain.Account account ? account.getName()
            : entity instanceof com.fatfreecrm.domain.Contact contact
                ? contact.getFirstName() + " " + contact.getLastName()
                : entity instanceof com.fatfreecrm.domain.Lead lead
                    ? lead.getFirstName() + " " + lead.getLastName() : entity.getClass().getSimpleName();
        String fromName = comment.getUser() == null ? null : userName(comment.getUser());
        for (User subscriber : subscribers) {
            if (comment.getUser() != null && subscriber.getId().equals(comment.getUser().getId())) {
                continue;
            }
            if (subscriber.isSubscribeToCommentReplies() && subscriber.getEmail() != null
                && !subscriber.getEmail().isBlank()) {
                deliveryScheduler.deliverLater(renderer.commentNotification(
                    subscriber.getEmail(),
                    fromName,
                    entityName,
                    entity.getClass().getSimpleName(),
                    entity.getId(),
                    "",
                    comment.getComment()));
            }
        }
    }

    private static String userName(User user) {
        return ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
            + (user.getLastName() == null ? "" : user.getLastName())).trim();
    }

    private static Class<?> entityClass(String type) {
        return switch (type) {
            case "Account" -> com.fatfreecrm.domain.Account.class;
            case "Campaign" -> com.fatfreecrm.domain.Campaign.class;
            case "Contact" -> com.fatfreecrm.domain.Contact.class;
            case "Lead" -> com.fatfreecrm.domain.Lead.class;
            case "Opportunity" -> com.fatfreecrm.domain.Opportunity.class;
            case "Task" -> com.fatfreecrm.domain.Task.class;
            default -> Object.class;
        };
    }
}
