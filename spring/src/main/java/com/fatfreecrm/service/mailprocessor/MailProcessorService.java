package com.fatfreecrm.service.mailprocessor;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Email;
import com.fatfreecrm.domain.Permission;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.Version;
import com.fatfreecrm.domain.support.CrmEntity;
import com.fatfreecrm.repository.CommentRepository;
import com.fatfreecrm.repository.PermissionRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.service.mail.CommentNotificationService;
import com.fatfreecrm.service.mail.MailSettingsService;
import com.fatfreecrm.service.jobs.JobsOwner;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed persistence collaborators are intentionally retained by this service."
)
public class MailProcessorService {

    private static final Pattern COMMENT_SUBJECT = Pattern.compile("\\[([^:]*):([^]]*)]");
    private static final Map<String, String> ENTITY_NAMES = Map.of(
        "ac", "account", "ca", "campaign", "co", "contact", "le", "lead", "op", "opportunity", "ta", "task");
    private static final Map<String, String> ENTITY_TABLES = Map.of(
        "account", "accounts", "campaign", "campaigns", "contact", "contacts", "lead", "leads",
        "opportunity", "opportunities", "task", "tasks");

    private final MailSettingsService settings;
    private final UserRepository userRepository;
    private final CommentRepository commentRepository;
    private final PermissionRepository permissionRepository;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final CommentNotificationService commentNotificationService;
    private final TransactionTemplate transactionTemplate;
    private final JobsOwner jobsOwner;

    public MailProcessorService(
        MailSettingsService settings,
        UserRepository userRepository,
        CommentRepository commentRepository,
        PermissionRepository permissionRepository,
        EntityManager entityManager,
        JdbcTemplate jdbcTemplate,
        CommentNotificationService commentNotificationService,
        JobsOwner jobsOwner,
        PlatformTransactionManager transactionManager
    ) {
        this.settings = settings;
        this.userRepository = userRepository;
        this.commentRepository = commentRepository;
        this.permissionRepository = permissionRepository;
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
        this.commentNotificationService = commentNotificationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jobsOwner = jobsOwner;
    }

    public void processDropbox(boolean dryRun) {
        processInbox("email_dropbox", dryRun, false);
    }

    public void processCommentReplies(boolean dryRun) {
        processInbox("email_comment_replies", dryRun, true);
    }

    public void setup(String mailbox) {
        if (!jobsOwner.isSpring()) {
            return;
        }
        Map<String, Object> config = settings.section(mailbox);
        Store store = null;
        try {
            store = connect(config);
            for (String folderName : List.of(string(config, "scan_folder"), string(config, "move_to_folder"),
                string(config, "move_invalid_to_folder"))) {
                if (folderName.isBlank()) {
                    continue;
                }
                Folder folder = store.getFolder(folderName);
                if (!folder.exists()) {
                    folder.create(Folder.HOLDS_MESSAGES);
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to set up IMAP folders for " + mailbox, exception);
        } finally {
            close(store);
        }
    }

    private void processInbox(String mailbox, boolean dryRun, boolean replies) {
        if (!jobsOwner.isSpring()) {
            return;
        }
        Map<String, Object> config = settings.section(mailbox);
        Store store = null;
        Folder folder = null;
        try {
            store = connect(config);
            folder = store.getFolder(string(config, "scan_folder"));
            folder.open(Folder.READ_WRITE);
            Message[] messages = folder.getMessages();
            FetchProfile fetchProfile = new FetchProfile();
            fetchProfile.add(FetchProfile.Item.ENVELOPE);
            folder.fetch(messages, fetchProfile);
            for (Message message : messages) {
                if (message.isSet(Flags.Flag.SEEN)) {
                    continue;
                }
                try {
                    String senderAddress = address(message.getFrom());
                    User sender = findSender(senderAddress);
                    if (!isValid(message) || sender == null) {
                        discard(folder, message, config, dryRun);
                    } else {
                        if (!dryRun) {
                            transactionTemplate.executeWithoutResult(status -> {
                                try {
                                    if (replies) {
                                        createCommentReply(message, sender);
                                    } else {
                                        attachDropboxMail(message, sender, config);
                                    }
                                } catch (Exception exception) {
                                    throw new IllegalStateException("Unable to process inbound email", exception);
                                }
                            });
                            archive(folder, message, config);
                        }
                    }
                } catch (Exception exception) {
                    if (!dryRun) {
                        discard(folder, message, config, false);
                    }
                }
                if (dryRun) {
                    message.setFlag(Flags.Flag.SEEN, false);
                }
            }
            folder.close(true);
            folder = null;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to process IMAP inbox " + mailbox, exception);
        } finally {
            if (folder != null && folder.isOpen()) {
                try {
                    folder.close(true);
                } catch (Exception ignored) {
                    close(store);
                }
            }
            close(store);
        }
    }

    private Store connect(Map<String, Object> config) throws Exception {
        Properties properties = new Properties();
        boolean ssl = Boolean.parseBoolean(string(config, "ssl"));
        String protocol = ssl ? "imaps" : "imap";
        String server = string(config, "server");
        String port = string(config, "port");
        if (server.isBlank() || string(config, "user").isBlank()) {
            throw new IllegalStateException("IMAP server and user must be configured");
        }
        if (!port.isBlank()) {
            properties.setProperty("mail." + protocol + ".port", port);
        }
        Session session = Session.getInstance(properties);
        Store store = session.getStore(protocol);
        store.connect(server, string(config, "user"), string(config, "password"));
        return store;
    }

    private User findSender(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        List<Long> ids = jdbcTemplate.query(
            "SELECT id FROM users WHERE (lower(email) = lower(?) OR lower(alt_email) = lower(?)) "
                + "AND suspended_at IS NULL ORDER BY id ASC LIMIT 1",
            (row, index) -> row.getLong(1),
            address,
            address);
        return ids.isEmpty() ? null : userRepository.findById(ids.get(0)).orElse(null);
    }

    static boolean isValid(Message message) throws Exception {
        return !"text/html".equalsIgnoreCase(message.getContentType());
    }

    private void discard(Folder folder, Message message, Map<String, Object> config, boolean dryRun) throws Exception {
        if (dryRun) {
            return;
        }
        move(folder, message, string(config, "move_invalid_to_folder"));
        message.setFlag(Flags.Flag.DELETED, true);
    }

    private void archive(Folder folder, Message message, Map<String, Object> config) throws Exception {
        move(folder, message, string(config, "move_to_folder"));
        message.setFlag(Flags.Flag.SEEN, true);
    }

    private static void move(Folder source, Message message, String targetName) throws Exception {
        if (targetName.isBlank()) {
            return;
        }
        Folder target = source.getStore().getFolder(targetName);
        if (target.exists()) {
            source.copyMessages(new Message[] {message}, target);
        }
    }

    protected void createCommentReply(Message message, User sender) throws Exception {
        Matcher matcher = COMMENT_SUBJECT.matcher(message.getSubject() == null ? "" : message.getSubject());
        if (!matcher.find()) {
            return;
        }
        String type = matcher.group(1).toLowerCase(Locale.ROOT);
        type = ENTITY_NAMES.getOrDefault(type, type);
        String idText = matcher.group(2);
        String table = ENTITY_TABLES.get(type);
        Class<?> entityClass = entityClass(type);
        if (table == null || entityClass == null || !idText.matches("\\d+")) {
            return;
        }
        Object raw = entityManager.find(entityClass, Long.parseLong(idText));
        if (!(raw instanceof CrmEntity entity) || !canAccess(entity, sender)) {
            return;
        }
        String reply = EmailReplyParser.parseReply(plainText(message).replace("\r\n", "\n")).strip();
        if (reply.isBlank()) {
            return;
        }
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
        commentNotificationService.afterCreate(comment);
        if (!entity.getSubscribedUsers().contains(sender.getId())) {
            entity.getSubscribedUsers().add(sender.getId());
            entityManager.merge(entity);
        }
        Version version = new Version();
        version.setItemType("Comment");
        version.setItemId(Math.toIntExact(comment.getId()));
        version.setRelatedType(comment.getCommentableType());
        version.setRelatedId(comment.getCommentableId());
        version.setEvent("create");
        version.setWhodunnit(sender.getId().toString());
        version.setObjectChanges(null);
        version.setCreatedAt(now);
        entityManager.persist(version);
    }

    private void attachDropboxMail(Message message, User sender, Map<String, Object> config) throws Exception {
        String from = address(message.getFrom());
        String to = addresses(message.getRecipients(Message.RecipientType.TO));
        String cc = addresses(message.getRecipients(Message.RecipientType.CC));
        List<String> recipients = new ArrayList<>();
        recipients.addAll(addressList(message.getRecipients(Message.RecipientType.TO)));
        recipients.addAll(addressList(message.getRecipients(Message.RecipientType.CC)));
        String dropbox = string(config, "address");
        List<?> aliases = config.get("address_aliases") instanceof List<?> list ? list : List.of();
        recipients.removeIf(address -> address.equalsIgnoreCase(dropbox)
            || aliases.stream().anyMatch(alias -> address.equalsIgnoreCase(String.valueOf(alias))));
        for (String recipient : recipients) {
            CrmEntity entity = findRecipient(recipient);
            if (entity != null && canAccess(entity, sender)) {
                createEmail(message, sender, entity, from, to.isBlank() ? dropbox : to, cc);
                return;
            }
        }
        if (!recipients.isEmpty()) {
            String recipient = recipients.get(0);
            com.fatfreecrm.domain.Contact contact = new com.fatfreecrm.domain.Contact();
            contact.setUser(sender);
            contact.setEmail(recipient);
            contact.setFirstName(recipient.substring(0, recipient.indexOf('@')).substring(0,
                Math.min(recipient.substring(0, recipient.indexOf('@')).length(), 64)));
            contact.setLastName("(unknown)");
            contact.setAccess(settings.defaultAccess());
            contact.setCreatedAt(Instant.now());
            contact.setUpdatedAt(contact.getCreatedAt());
            entityManager.persist(contact);
            entityManager.flush();
            createEmail(message, sender, contact, from, to.isBlank() ? dropbox : to, cc);
        }
    }

    private CrmEntity findRecipient(String email) {
        for (String type : List.of("account", "contact", "lead")) {
            String table = ENTITY_TABLES.get(type);
            String query = "SELECT id FROM " + table + " WHERE lower(email) = lower(?)"
                + (type.equals("contact") || type.equals("lead") ? " OR lower(alt_email) = lower(?)" : "")
                + " ORDER BY id ASC LIMIT 1";
            List<Long> ids = type.equals("contact") || type.equals("lead")
                ? jdbcTemplate.query(query, (row, index) -> row.getLong(1), email, email)
                : jdbcTemplate.query(query, (row, index) -> row.getLong(1), email);
            if (!ids.isEmpty()) {
                return (CrmEntity) entityManager.find(entityClass(type), ids.get(0));
            }
        }
        return null;
    }

    private void createEmail(Message message, User sender, CrmEntity entity, String from, String to, String cc)
        throws Exception {
        Email email = new Email();
        email.setUser(sender);
        email.setMediatorType(entity.getClass().getSimpleName());
        email.setMediatorId(Math.toIntExact(entity.getId()));
        email.setImapMessageId(message.getHeader("Message-ID") == null ? "" : message.getHeader("Message-ID")[0]);
        email.setSentFrom(from);
        email.setSentTo(to);
        email.setCc(cc);
        email.setSubject(message.getSubject() == null ? "" : message.getSubject());
        email.setBody(plainText(message));
        Date date = message.getSentDate() == null ? new Date() : message.getSentDate();
        email.setReceivedAt(date.toInstant());
        email.setSentAt(date.toInstant());
        Instant now = Instant.now();
        email.setCreatedAt(now);
        email.setUpdatedAt(now);
        entityManager.persist(email);
        entity.setUpdatedAt(now);
    }

    private boolean canAccess(CrmEntity entity, User sender) {
        if ("Public".equals(entity.getAccess())) {
            return true;
        }
        if (entity.getUser() != null && entity.getUser().getId().equals(sender.getId())
            || entity.getAssignedTo() != null && entity.getAssignedTo().getId().equals(sender.getId())) {
            return true;
        }
        if (!"Shared".equals(entity.getAccess())) {
            return false;
        }
        return permissionRepository.findByAssetTypeAndAssetId(entity.getClass().getSimpleName(),
                Math.toIntExact(entity.getId())).stream()
            .map(Permission::getUser)
            .anyMatch(user -> user != null && user.getId().equals(sender.getId()));
    }

    private static Class<?> entityClass(String type) {
        return switch (type) {
            case "account" -> com.fatfreecrm.domain.Account.class;
            case "campaign" -> com.fatfreecrm.domain.Campaign.class;
            case "contact" -> com.fatfreecrm.domain.Contact.class;
            case "lead" -> com.fatfreecrm.domain.Lead.class;
            case "opportunity" -> com.fatfreecrm.domain.Opportunity.class;
            case "task" -> com.fatfreecrm.domain.Task.class;
            default -> null;
        };
    }

    private static String plainText(Part message) throws Exception {
        Object content = message.getContent();
        if (content instanceof String text) {
            return message.getContentType().toLowerCase(Locale.ROOT).startsWith("text/html")
                ? Jsoup.parse(text).text() : text;
        }
        if (content instanceof Multipart multipart) {
            String html = null;
            for (int i = 0; i < multipart.getCount(); i++) {
                Part part = multipart.getBodyPart(i);
                if (part.getDisposition() != null
                    && Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                    continue;
                }
                String type = part.getContentType().toLowerCase(Locale.ROOT);
                Object partContent = part.getContent();
                if (partContent instanceof String text && type.startsWith("text/plain")) {
                    return text;
                }
                if (partContent instanceof String text && type.startsWith("text/html") && html == null) {
                    html = text;
                }
                if (partContent instanceof Multipart nested) {
                    String nestedText = plainText(part);
                    if (!nestedText.isBlank()) {
                        return nestedText;
                    }
                }
            }
            return html == null ? "" : Jsoup.parse(html).text();
        }
        return "";
    }

    private static String address(jakarta.mail.Address[] addresses) {
        List<String> result = addressList(addresses);
        return result.isEmpty() ? "" : result.get(0);
    }

    private static List<String> addressList(jakarta.mail.Address[] addresses) {
        if (addresses == null) {
            return List.of();
        }
        return Arrays.stream(addresses).filter(InternetAddress.class::isInstance).map(InternetAddress.class::cast)
            .map(InternetAddress::getAddress).filter(value -> value != null && !value.isBlank()).toList();
    }

    private static String addresses(jakarta.mail.Address[] addresses) {
        return String.join(", ", addressList(addresses));
    }

    private static String string(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? "" : value.toString();
    }

    private static void close(Store store) {
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (Exception ignored) {
                // Closing is best effort when a server disconnects unexpectedly.
            }
        }
    }
}
