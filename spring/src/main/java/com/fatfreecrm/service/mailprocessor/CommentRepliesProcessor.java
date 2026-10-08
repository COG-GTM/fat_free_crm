package com.fatfreecrm.service.mailprocessor;

import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.CrmEntity;
import com.fatfreecrm.repository.CommentRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.audit.VersionRecorder;
import com.fatfreecrm.service.history.RailsRowAttributes;
import com.fatfreecrm.service.jobs.JobsOwner;
import com.fatfreecrm.service.mail.CommentNotificationService;
import com.fatfreecrm.service.mail.MailSettingsService;
import jakarta.mail.Message;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class CommentRepliesProcessor extends MailProcessorBase {

    private static final Pattern SUBJECT = Pattern.compile("\\[([^:]*):([^\\]]*)]");
    private static final Pattern MENTION = Pattern.compile("@([a-zA-Z0-9_-]+)");
    private static final Map<String, String> ENTITY_SHORTCUTS = Map.of(
        "ac", "account", "ca", "campaign", "co", "contact", "le", "lead", "op", "opportunity", "ta", "task");

    private final CommentRepository commentRepository;
    private final CommentNotificationService commentNotificationService;
    private final RailsRowAttributes rowAttributes;
    private final VersionRecorder versionRecorder;

    public CommentRepliesProcessor(
        MailSettingsService settings,
        UserRepository userRepository,
        CommentRepository commentRepository,
        EntityManager entityManager,
        JdbcTemplate jdbcTemplate,
        RailsRowAttributes rowAttributes,
        VersionRecorder versionRecorder,
        CommentNotificationService commentNotificationService,
        JobsOwner jobsOwner,
        ImapClient imapClient,
        PlatformTransactionManager transactionManager
    ) {
        super(settings, userRepository, entityManager, jdbcTemplate, jobsOwner, imapClient, transactionManager);
        this.commentRepository = commentRepository;
        this.commentNotificationService = commentNotificationService;
        this.rowAttributes = rowAttributes;
        this.versionRecorder = versionRecorder;
    }

    public void process(boolean dryRun) {
        processInbox("email_comment_replies", dryRun, this::processMessage);
    }

    private void processMessage(Message message, User sender, Map<String, Object> config) throws Exception {
        List<String> subjectLine = subjectLine(message.getSubject());
        if (subjectLine == null) {
            return;
        }
        String name = subjectLine.get(0).toLowerCase(Locale.ROOT);
        String type = ENTITY_SHORTCUTS.getOrDefault(name, name);
        String idText = subjectLine.get(1);
        Class<?> typeClass = entityClass(type);
        if (typeClass == null || !idText.matches("\\d+")) {
            return;
        }
        Object raw = entityManager.find(typeClass, Long.parseLong(idText));
        if (!(raw instanceof CrmEntity entity) || !canAccess(entity, sender)) {
            return;
        }
        String reply = EmailReplyParser.parseReply(plainTextBody(message)).strip();
        if (reply.isBlank()) {
            return;
        }
        subscribeMentionedUsers(entity, reply);
        entity.setUpdatedAt(Instant.now());
        entityManager.merge(entity);
        Comment comment = new Comment();
        comment.setUser(sender);
        comment.setCommentableType(type.substring(0, 1).toUpperCase(Locale.ROOT) + type.substring(1));
        comment.setCommentableId(Math.toIntExact(entity.getId()));
        comment.setComment(reply);
        comment.setPrivate(false);
        Instant now = Instant.now();
        comment.setCreatedAt(now);
        comment.setUpdatedAt(now);
        commentRepository.saveAndFlush(comment);
        entity.getSubscribedUsers().add(sender.getId());
        entity.setUpdatedAt(Instant.now());
        entityManager.merge(entity);
        commentNotificationService.afterCreate(comment);
        versionRecorder.recordCreate(new AuthenticatedUser(sender.getId(), null, false), comment,
            rowAttributes.read(comment), rowAttributes.defaults(comment));
    }

    static List<String> subjectLine(String subject) {
        Matcher matcher = SUBJECT.matcher(subject == null ? "" : subject);
        if (!matcher.find()) {
            return null;
        }
        String name = matcher.group(1);
        String entity = switch (name) {
            case "account", "campaign", "contact", "lead", "opportunity", "task" -> name;
            default -> ENTITY_SHORTCUTS.get(name);
        };
        return entity == null ? null : List.of(entity, matcher.group(2));
    }

    private void subscribeMentionedUsers(CrmEntity entity, String comment) {
        Matcher mentions = MENTION.matcher(comment);
        while (mentions.find()) {
            jdbcTemplate.query("SELECT id FROM users WHERE username = ? ORDER BY id LIMIT 1",
                (row, index) -> row.getLong(1), mentions.group(1))
                .forEach(entity.getSubscribedUsers()::add);
        }
    }
}
