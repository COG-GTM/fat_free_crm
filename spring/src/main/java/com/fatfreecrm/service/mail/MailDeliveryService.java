package com.fatfreecrm.service.mail;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Email;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.CrmEntity;
import com.fatfreecrm.service.jobs.JobsOwner;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are intentionally retained by this service."
)
public class MailDeliveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailDeliveryService.class);

    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final MailRendererService renderer;
    private final MailSettingsService settings;
    private final JobsOwner jobsOwner;

    public MailDeliveryService(
        JavaMailSender mailSender,
        ObjectMapper objectMapper,
        EntityManager entityManager,
        MailRendererService renderer,
        MailSettingsService settings,
        JobsOwner jobsOwner
    ) {
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.renderer = renderer;
        this.settings = settings;
        this.jobsOwner = jobsOwner;
    }

    public void deliver(RenderedMail mail) {
        if (!jobsOwner.isSpring()) {
            LOGGER.warn("jobs owner is rails; not sending mail to <redacted>");
            return;
        }
        try {
            JavaMailSender sender = senderForSettings();
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setTo(mail.to());
            helper.setFrom(mail.from());
            helper.setSubject(mail.subject());
            helper.setText(mail.body(), "text/html".equals(mail.contentType()));
            sender.send(message);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to deliver mail", exception);
        }
    }

    JavaMailSender senderForSettings() {
        Map<String, Object> smtp = settings.section("smtp");
        String address = setting(smtp, "address");
        if (address.isBlank()) {
            return mailSender;
        }
        JavaMailSenderImpl configured = new JavaMailSenderImpl();
        configured.setHost(address);
        String port = setting(smtp, "port");
        if (!port.isBlank()) {
            configured.setPort(Integer.parseInt(port));
        }
        configured.setUsername(setting(smtp, "user_name"));
        configured.setPassword(setting(smtp, "password"));
        Properties properties = configured.getJavaMailProperties();
        String authentication = setting(smtp, "authentication").replaceFirst("^:", "");
        if (!authentication.isBlank()) {
            properties.setProperty("mail.smtp.auth", "true");
            properties.setProperty("mail.smtp.auth.mechanisms", authentication.toUpperCase(Locale.ROOT));
        }
        if (Boolean.parseBoolean(setting(smtp, "enable_starttls_auto"))) {
            properties.setProperty("mail.smtp.starttls.enable", "true");
        }
        return configured;
    }

    private static String setting(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }

    @Transactional
    public void deliverActiveJob(String serializedArguments) throws IOException {
        if (!jobsOwner.isSpring()) {
            return;
        }
        JsonNode root = objectMapper.readTree(serializedArguments);
        JsonNode payload = root.isArray() ? root.path(0) : root;
        JsonNode arguments = payload.path("arguments");
        if (!arguments.isArray() || arguments.size() < 2) {
            throw new IllegalArgumentException("Unsupported Action Mailer Active Job arguments");
        }
        String mailer = arguments.get(0).asText();
        String action = arguments.get(1).asText();
        JsonNode mailerArguments = arguments.size() > 3
            ? arguments.get(3).path("args") : objectMapper.createArrayNode();
        List<String> globalIds = new ArrayList<>();
        mailerArguments.findValues("_aj_globalid")
            .forEach(node -> globalIds.add(node.asText()));
        List<GlobalId> ids = globalIds.stream().map(MailDeliveryService::parseGlobalId).toList();
        RenderedMail rendered = switch (mailer + "#" + action) {
            case "UserMailer#assigned_entity_notification" -> renderAssignment(ids);
            case "SubscriptionMailer#comment_notification" -> renderComment(ids);
            case "DropboxMailer#dropbox_notification" -> renderDropbox(arguments, mailerArguments, ids);
            case "DeviseMailer#confirmation_instructions" -> renderer.deviseConfirmation(user(ids, 0).getEmail(),
                stringArgument(mailerArguments, 1));
            case "DeviseMailer#reset_password_instructions" -> renderer.deviseResetPassword(user(ids, 0).getEmail(),
                stringArgument(mailerArguments, 1));
            case "DeviseMailer#password_change" -> renderer.devisePasswordChange(user(ids, 0).getEmail());
            default -> throw new IllegalArgumentException("Unsupported Active Job mailer " + mailer + "#" + action);
        };
        deliver(rendered);
    }

    private RenderedMail renderAssignment(List<GlobalId> ids) {
        CrmEntity entity = entity(ids, 0);
        User assigner = user(ids, 1);
        if (entity.getAssignedTo() == null || blank(entity.getAssignedTo().getEmail())) {
            throw new IllegalArgumentException("Assignment mail recipient is unavailable");
        }
        String name = entityName(entity);
        String entityType = entity.getClass().getSimpleName();
        String host = renderer.host();
        String entityUrl = host.isBlank() ? "" : host.replaceAll("/+$", "") + "/"
            + route(entityType) + "/" + entity.getId();
        return renderer.assignment(entity.getAssignedTo().getEmail(), name, entityType, entityUrl, userName(assigner));
    }

    private RenderedMail renderComment(List<GlobalId> ids) {
        User recipient = user(ids, 0);
        Comment comment = (Comment) load(ids, 1, Comment.class);
        Object rawEntity = entityManager.find(entityClass(comment.getCommentableType()), comment.getCommentableId());
        CrmEntity entity = (CrmEntity) rawEntity;
        String tags = "";
        String from = comment.getUser() == null ? null : userName(comment.getUser());
        return renderer.commentNotification(recipient.getEmail(), from, entityName(entity),
            entity.getClass().getSimpleName(), entity.getId(), tags, comment.getComment());
    }

    private RenderedMail renderDropbox(JsonNode arguments, JsonNode mailerArguments, List<GlobalId> ids) {
        User recipient = user(ids, 0);
        Email email = (Email) load(ids, 1, Email.class);
        String from = mailerArguments.size() > 1 ? mailerArguments.get(1).asText() : "";
        String links = mailerArguments.size() > 3 ? mailerArguments.get(3).toString() : "";
        String subject = email.getSubject() == null ? "" : email.getSubject();
        return renderer.dropboxNotification(recipient.getEmail(), from, subject, email.getBody(), links);
    }

    private CrmEntity entity(List<GlobalId> ids, int index) {
        GlobalId id = at(ids, index);
        return (CrmEntity) entityManager.find(entityClass(id.type()), id.id());
    }

    private User user(List<GlobalId> ids, int index) {
        return (User) load(ids, index, User.class);
    }

    private Object load(List<GlobalId> ids, int index, Class<?> expectedType) {
        GlobalId id = at(ids, index);
        return entityManager.find(expectedType, id.id());
    }

    private static GlobalId at(List<GlobalId> ids, int index) {
        if (ids.size() <= index) {
            throw new IllegalArgumentException("Active Job is missing a GlobalID argument");
        }
        return ids.get(index);
    }

    private static String stringArgument(JsonNode arguments, int index) {
        return arguments.size() > index ? arguments.get(index).asText() : "";
    }

    private static GlobalId parseGlobalId(String value) {
        int slash = value.lastIndexOf('/');
        int typeSlash = value.lastIndexOf('/', slash - 1);
        if (slash < 0 || typeSlash < 0) {
            throw new IllegalArgumentException("Invalid Active Job GlobalID");
        }
        return new GlobalId(value.substring(typeSlash + 1, slash), Long.parseLong(value.substring(slash + 1)));
    }

    private static Class<?> entityClass(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "account" -> com.fatfreecrm.domain.Account.class;
            case "campaign" -> com.fatfreecrm.domain.Campaign.class;
            case "contact" -> com.fatfreecrm.domain.Contact.class;
            case "lead" -> com.fatfreecrm.domain.Lead.class;
            case "opportunity" -> com.fatfreecrm.domain.Opportunity.class;
            case "task" -> com.fatfreecrm.domain.Task.class;
            case "comment" -> Comment.class;
            default -> throw new IllegalArgumentException("Unsupported Active Job GlobalID type " + type);
        };
    }

    private static String entityName(CrmEntity entity) {
        if (entity instanceof com.fatfreecrm.domain.Contact contact) {
            return (contact.getFirstName() + " " + contact.getLastName()).trim();
        }
        if (entity instanceof com.fatfreecrm.domain.Lead lead) {
            return (lead.getFirstName() + " " + lead.getLastName()).trim();
        }
        try {
            return String.valueOf(entity.getClass().getMethod("getName").invoke(entity));
        } catch (ReflectiveOperationException exception) {
            return entity.getClass().getSimpleName();
        }
    }

    private static String userName(User user) {
        String firstName = user.getFirstName() == null ? "" : user.getFirstName();
        String lastName = user.getLastName() == null ? "" : user.getLastName();
        return (firstName + " " + lastName).trim();
    }

    private static String route(String entityType) {
        return switch (entityType) {
            case "Account" -> "accounts";
            case "Campaign" -> "campaigns";
            case "Contact" -> "contacts";
            case "Lead" -> "leads";
            case "Opportunity" -> "opportunities";
            case "Task" -> "tasks";
            default -> entityType.toLowerCase(Locale.ROOT) + "s";
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record GlobalId(String type, long id) {
    }
}
