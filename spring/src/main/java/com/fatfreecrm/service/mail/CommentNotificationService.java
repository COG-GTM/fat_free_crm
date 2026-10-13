package com.fatfreecrm.service.mail;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.CrmEntity;
import com.fatfreecrm.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed EntityManager is intentionally retained by this service."
)
public class CommentNotificationService {

    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final MailDeliveryScheduler deliveryScheduler;
    private final JdbcTemplate jdbcTemplate;

    public CommentNotificationService(
        UserRepository userRepository,
        EntityManager entityManager,
        MailDeliveryScheduler deliveryScheduler,
        JdbcTemplate jdbcTemplate
    ) {
        this.userRepository = userRepository;
        this.entityManager = entityManager;
        this.deliveryScheduler = deliveryScheduler;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void afterCreate(Comment comment) {
        Object commentable = entityManager.find(
            entityClass(comment.getCommentableType()), comment.getCommentableId());
        List<Long> subscribedUsers;
        if (commentable instanceof CrmEntity entity) {
            subscribedUsers = entity.getSubscribedUsers();
        } else if (commentable instanceof Task task) {
            subscribedUsers = task.getSubscribedUsers();
        } else {
            return;
        }
        List<Long> subscriberIds = subscribedUsers.stream()
            .filter(id -> comment.getUser() == null || !id.equals(comment.getUser().getId()))
            .toList();
        if (subscriberIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(subscriberIds.size(), "?"));
        List<Long> recipientIds = jdbcTemplate.query(
            "SELECT id FROM users WHERE id IN (" + placeholders + ")",
            (row, index) -> row.getLong(1),
            subscriberIds.toArray());
        List<User> subscribers = new ArrayList<>();
        for (Long recipientId : recipientIds) {
            userRepository.findById(recipientId).ifPresent(subscribers::add);
        }
        for (User subscriber : subscribers) {
            if (subscriber.getConfirmedAt() != null && subscriber.getSuspendedAt() == null
                && subscriber.getEmail() != null && !subscriber.getEmail().isBlank()
                && subscriber.isSubscribeToCommentReplies()) {
                deliveryScheduler.deliverLater("SubscriptionMailer", "comment_notification", subscriber, comment);
            }
        }
    }

    public String tagsFor(CrmEntity entity) {
        return String.join(", ", jdbcTemplate.query(
            """
                SELECT tags.name
                FROM tags
                JOIN taggings ON taggings.tag_id = tags.id
                WHERE taggings.taggable_type = ? AND taggings.taggable_id = ? AND taggings.context = 'tags'
                ORDER BY taggings.id
                """,
            (row, index) -> row.getString(1),
            entity.getClass().getSimpleName(), entity.getId().intValue()));
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
