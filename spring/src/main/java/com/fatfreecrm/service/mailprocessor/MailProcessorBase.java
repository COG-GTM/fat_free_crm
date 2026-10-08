package com.fatfreecrm.service.mailprocessor;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.CrmEntity;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.service.jobs.JobsOwner;
import com.fatfreecrm.service.mail.MailSettingsService;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FlagTerm;
import jakarta.persistence.EntityManager;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public abstract class MailProcessorBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailProcessorBase.class);

    protected final MailSettingsService settings;
    protected final UserRepository userRepository;
    protected final EntityManager entityManager;
    protected final JdbcTemplate jdbcTemplate;
    protected final TransactionTemplate transactionTemplate;
    protected final JobsOwner jobsOwner;
    private final ImapClient imapClient;

    protected MailProcessorBase(
        MailSettingsService settings,
        UserRepository userRepository,
        EntityManager entityManager,
        JdbcTemplate jdbcTemplate,
        JobsOwner jobsOwner,
        ImapClient imapClient,
        PlatformTransactionManager transactionManager
    ) {
        this.settings = settings;
        this.userRepository = userRepository;
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
        this.jobsOwner = jobsOwner;
        this.imapClient = imapClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    protected final void processInbox(String mailbox, boolean dryRun, MessageHandler handler) {
        if (!jobsOwner.isSpring()) {
            return;
        }
        Map<String, Object> config = settings.section(mailbox);
        if (string(config, "server").isBlank()) {
            LOGGER.info("Skipping {} IMAP polling: server is not configured", mailbox);
            return;
        }
        Store store = null;
        Folder folder = null;
        int archived = 0;
        int discarded = 0;
        try {
            store = connect(config);
            folder = store.getFolder(string(config, "scan_folder"));
            folder.open(Folder.READ_WRITE);
            Message[] messages = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            FetchProfile fetchProfile = new FetchProfile();
            fetchProfile.add(FetchProfile.Item.ENVELOPE);
            folder.fetch(messages, fetchProfile);
            for (Message message : messages) {
                try {
                    if (!isValid(message)) {
                        discard(folder, message, config, dryRun);
                        discarded++;
                        continue;
                    }
                    User sender = findSender(address(message.getFrom()));
                    if (sender == null) {
                        LOGGER.info("{} discarded an email from an unknown sender", getClass().getSimpleName());
                        discard(folder, message, config, dryRun);
                        discarded++;
                        continue;
                    }
                    if (!dryRun) {
                        transactionTemplate.executeWithoutResult(status -> {
                            try {
                                handler.process(message, sender, config);
                            } catch (Exception exception) {
                                throw new IllegalStateException("Unable to process inbound email", exception);
                            }
                        });
                        archive(folder, message, config);
                    } else {
                        message.setFlag(Flags.Flag.SEEN, false);
                    }
                    archived++;
                } catch (Exception exception) {
                    LOGGER.warn("Unable to process inbound email; discarding message", exception);
                    if (!dryRun) {
                        discard(folder, message, config, false);
                    }
                    discarded++;
                }
            }
            folder.close(false);
            folder = null;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to process IMAP inbox " + mailbox, exception);
        } finally {
            if (folder != null && folder.isOpen()) {
                try {
                    folder.close(false);
                } catch (Exception exception) {
                    LOGGER.debug("Unable to close IMAP folder", exception);
                }
            }
            close(store);
            LOGGER.info("{} messages processed={} archived={} discarded={}",
                getClass().getSimpleName(), archived + discarded, archived, discarded);
        }
    }

    public final void setup(String mailbox) {
        if (!jobsOwner.isSpring()) {
            return;
        }
        Map<String, Object> config = settings.section(mailbox);
        if (string(config, "server").isBlank()) {
            LOGGER.info("Skipping {} IMAP setup: server is not configured", mailbox);
            return;
        }
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

    protected final User findSender(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        List<Long> ids = jdbcTemplate.query(
            "SELECT id FROM users WHERE (lower(email) = lower(?) OR lower(alt_email) = lower(?)) "
                + "AND suspended_at IS NULL ORDER BY id ASC LIMIT 1",
            (row, index) -> row.getLong(1), address, address);
        return ids.isEmpty() ? null : userRepository.findById(ids.get(0)).orElse(null);
    }

    protected final boolean canAccess(CrmEntity entity, User sender) {
        if ("Public".equals(entity.getAccess())) {
            return true;
        }
        if ((entity.getUser() != null && entity.getUser().getId().equals(sender.getId()))
            || (entity.getAssignedTo() != null && entity.getAssignedTo().getId().equals(sender.getId()))) {
            return true;
        }
        if ("Shared".equals(entity.getAccess())) {
            throw new IllegalStateException("Rails Permission.exists permission check is invalid for Shared assets");
        }
        return false;
    }

    public static boolean isValid(Message message) throws Exception {
        return !"text/html".equals(message.getContentType());
    }

    public static String plainTextBody(Part message) throws Exception {
        Object content = message.getContent();
        if (content instanceof String text) {
            return message.getContentType().toLowerCase(Locale.ROOT).startsWith("text/html")
                ? cleanHtml(text) : text.strip().replace("\r\n", "\n");
        }
        if (content instanceof Multipart multipart) {
            String html = null;
            for (int index = 0; index < multipart.getCount(); index++) {
                Part part = multipart.getBodyPart(index);
                if (part.getDisposition() != null && Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                    continue;
                }
                String type = part.getContentType().toLowerCase(Locale.ROOT);
                Object partContent = part.getContent();
                if (partContent instanceof String text && type.startsWith("text/plain")) {
                    return text.strip().replace("\r\n", "\n");
                }
                if (partContent instanceof String text && type.startsWith("text/html") && html == null) {
                    html = text;
                }
                if (partContent instanceof Multipart && type.startsWith("multipart/")) {
                    String nested = plainTextBody(part);
                    if (!nested.isBlank() && html == null) {
                        html = nested;
                    }
                }
            }
            return html == null ? "" : cleanHtml(html);
        }
        return "";
    }

    protected static Class<?> entityClass(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "account" -> com.fatfreecrm.domain.Account.class;
            case "campaign" -> com.fatfreecrm.domain.Campaign.class;
            case "contact" -> com.fatfreecrm.domain.Contact.class;
            case "lead" -> com.fatfreecrm.domain.Lead.class;
            case "opportunity" -> com.fatfreecrm.domain.Opportunity.class;
            case "task" -> com.fatfreecrm.domain.Task.class;
            default -> null;
        };
    }

    protected static String address(jakarta.mail.Address[] addresses) {
        List<String> values = addressList(addresses);
        return values.isEmpty() ? "" : values.get(0);
    }

    protected static List<String> addressList(jakarta.mail.Address[] addresses) {
        if (addresses == null) {
            return List.of();
        }
        return Arrays.stream(addresses).filter(InternetAddress.class::isInstance).map(InternetAddress.class::cast)
            .map(InternetAddress::getAddress).filter(value -> value != null && !value.isBlank()).toList();
    }

    protected static String addresses(jakarta.mail.Address[] addresses) {
        return String.join(", ", addressList(addresses));
    }

    protected static String string(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? "" : value.toString();
    }

    private static String cleanHtml(String html) {
        Document document = Jsoup.parse(html);
        document.select("script,style").remove();
        document.select("a[href]").forEach(link -> {
            String href = link.attr("href");
            if (!href.isBlank() && !href.equals(link.text())) {
                link.after(new TextNode(" (" + href + ")"));
            }
        });
        document.select("br").forEach(element -> element.before(new TextNode("\n")));
        document.select("p,div,li,tr,h1,h2,h3,h4,h5,h6").forEach(element -> {
            element.before(new TextNode("\n"));
            element.after(new TextNode("\n\n"));
        });
        return document.body().wholeText().replace("\r\n", "\n").lines()
            .map(line -> line.replaceAll("[\\t\\f ]+", " ").strip())
            .filter(line -> !line.isEmpty())
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private Store connect(Map<String, Object> config) throws Exception {
        return imapClient.connect(config);
    }

    private static void discard(Folder folder, Message message, Map<String, Object> config, boolean dryRun)
        throws Exception {
        if (dryRun) {
            message.setFlag(Flags.Flag.SEEN, false);
            return;
        }
        move(folder, message, string(config, "move_invalid_to_folder"));
        message.setFlag(Flags.Flag.DELETED, true);
    }

    private static void archive(Folder folder, Message message, Map<String, Object> config) throws Exception {
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

    private static void close(Store store) {
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (Exception exception) {
                LOGGER.debug("Unable to close IMAP store", exception);
            }
        }
    }

    @FunctionalInterface
    protected interface MessageHandler {

        void process(Message message, User sender, Map<String, Object> config) throws Exception;
    }
}
