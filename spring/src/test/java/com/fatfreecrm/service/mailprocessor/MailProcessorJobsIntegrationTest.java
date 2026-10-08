package com.fatfreecrm.service.mailprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fatfreecrm.config.JobsProperties;
import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.repository.CommentRepository;
import com.fatfreecrm.repository.SettingRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.service.jobs.JobLockKey;
import com.fatfreecrm.service.jobs.JobLockService;
import com.fatfreecrm.service.jobs.JobsOwner;
import com.fatfreecrm.service.mail.CommentNotificationService;
import com.fatfreecrm.service.mail.MailDeliveryScheduler;
import com.fatfreecrm.service.mail.MailSettingsService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Folder;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = "ffcrm.jobs.owner=rails")
class MailProcessorJobsIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SENDER_USERNAME = "ab273-jobs-mail-sender";
    private static final String SENDER_EMAIL = "ab273-sender@example.test";
    private static final String DROPBOX_EMAIL = "ab273-dropbox@example.test";
    private static final String REPLY_EMAIL = "ab273-reply@example.test";
    private static final String MENTION_USERNAME = "ab273-mentioned";
    private static final String MESSAGE_ID = "<ab273-jobs-mail@example.test>";
    private static final int LOCK_CLASS_ID = 1179009869;
    private static final int DROPBOX_LOCK_OBJECT_ID = 1;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MailSettingsService mailSettings;
    @Autowired
    private SettingRepository settingRepository;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private CommentNotificationService commentNotificationService;
    @Autowired
    private Scheduler scheduler;
    @Autowired
    private MailDeliveryScheduler mailDeliveryScheduler;
    @Autowired
    private JobsOwner jobsOwner;

    @Test
    void railsOwnerDoesNotScheduleDeliverOrConnectToImap() throws Exception {
        GreenMail greenMail = greenMail();
        greenMail.start();
        try {
            greenMail.setUser(DROPBOX_EMAIL, "dropbox", "secret");
            sendMessage(greenMail, DROPBOX_EMAIL, "Account: Must Not Be Processed\nMessage body");

            assertEquals("rails", jobsOwner.value());
            assertFalse(scheduler.isStarted());
            assertTrue(scheduler.getJobKeys(GroupMatcher.anyGroup()).isEmpty());
            int incomingBeforeDelivery = greenMail.getReceivedMessages().length;
            mailDeliveryScheduler.deliverLater(
                "UserMailer", "assigned_entity_notification",
                new com.fatfreecrm.service.mail.RenderedMail(
                    "subject", "sender@example.test", "recipient@example.test", "text/html", "<p>body</p>"));
            assertEquals(incomingBeforeDelivery, greenMail.getReceivedMessages().length);

            ImapClient imapClient = mock(ImapClient.class);
            DropboxProcessor processor = new DropboxProcessor(
                mailSettings,
                userRepository,
                entityManager,
                jdbcTemplate,
                jobsOwner,
                imapClient,
                transactionManager);
            processor.process(false);
            verifyNoInteractions(imapClient);
            assertEquals(1, unseenMessages(greenMail));
            assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM accounts WHERE name = ?",
                Integer.class, "Must Not Be Processed"));
        } finally {
            greenMail.stop();
        }
    }

    @Test
    void springOwnerWaitsForPostgresLockThenProcessesTheInbox() throws Exception {
        clearFixtures();
        GreenMail greenMail = greenMail();
        greenMail.start();
        try {
            greenMail.setUser(DROPBOX_EMAIL, "dropbox", "secret");
            insertSender();
            insertDropboxSetting(greenMail.getImap().getPort());
            sendMessage(greenMail, DROPBOX_EMAIL, "Account: Lock Example\nEmail body");

            JobsProperties properties = new JobsProperties();
            properties.setOwner("spring");
            JobsOwner springOwner = new JobsOwner(properties);
            JobLockService lockService = new JobLockService(dataSource, springOwner);
            DropboxProcessor processor = new DropboxProcessor(
                mailSettings,
                userRepository,
                entityManager,
                jdbcTemplate,
                springOwner,
                new ImapClient(),
                transactionManager);

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_lock(" + LOCK_CLASS_ID + ", " + DROPBOX_LOCK_OBJECT_ID + ")");
                assertFalse(lockService.runExclusively(JobLockKey.DROPBOX_POLL, () -> processor.process(false)));
                assertEquals(0, emailCount());
                assertEquals(1, unseenMessages(greenMail));
                statement.execute("SELECT pg_advisory_unlock(" + LOCK_CLASS_ID + ", " + DROPBOX_LOCK_OBJECT_ID + ")");
            }

            assertTrue(lockService.runExclusively(JobLockKey.DROPBOX_POLL, () -> processor.process(false)));
            assertEquals(1, emailCount());
            assertEquals(0, unseenMessages(greenMail));
            assertEquals("Email body", jdbcTemplate.queryForObject(
                """
                    SELECT body FROM emails
                    WHERE mediator_type = 'Account'
                      AND mediator_id = (SELECT id FROM accounts WHERE name = 'Lock Example')
                    """, String.class));
        } finally {
            greenMail.stop();
            clearFixtures();
        }
    }

    @Test
    void commentReplyCreatesCommentAndSubscribesMentionedUserAndAuthor() throws Exception {
        clearFixtures();
        GreenMail greenMail = greenMail();
        greenMail.start();
        try {
            greenMail.setUser(REPLY_EMAIL, "reply", "secret");
            insertSender();
            jdbcTemplate.update("""
                INSERT INTO users (username, email, encrypted_password, password_salt, created_at, updated_at)
                VALUES (?, 'ab273-mentioned@example.test', '', '', now(), now())
                """, MENTION_USERNAME);
            int accountId = jdbcTemplate.queryForObject("""
                INSERT INTO accounts (user_id, name, access, created_at, updated_at)
                VALUES ((SELECT id FROM users WHERE username = ?), 'Mention Account', 'Public', now(), now())
                RETURNING id
                """, Integer.class, SENDER_USERNAME);
            insertMailboxSetting("email_comment_replies", REPLY_EMAIL, "reply", greenMail.getImap().getPort());
            sendMessage(greenMail, REPLY_EMAIL, "[ac:" + accountId + "] discussion",
                "Please review @" + MENTION_USERNAME);

            JobsProperties properties = new JobsProperties();
            properties.setOwner("spring");
            CommentRepliesProcessor processor = new CommentRepliesProcessor(
                mailSettings,
                userRepository,
                commentRepository,
                entityManager,
                jdbcTemplate,
                commentNotificationService,
                new JobsOwner(properties),
                new ImapClient(),
                transactionManager);
            processor.process(false);

            Long senderId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, SENDER_USERNAME);
            Long mentionedId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, MENTION_USERNAME);
            assertEquals("Please review @" + MENTION_USERNAME, jdbcTemplate.queryForObject(
                "SELECT comment FROM comments WHERE commentable_id = ?",
                String.class, accountId));
            String subscribers = jdbcTemplate.queryForObject(
                "SELECT subscribed_users FROM accounts WHERE id = ?", String.class, accountId);
            assertTrue(subscribers.contains("- " + mentionedId));
            assertTrue(subscribers.contains("- " + senderId));
            Integer commentId = jdbcTemplate.queryForObject(
                "SELECT id FROM comments WHERE commentable_id = ?", Integer.class, accountId);
            assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM versions WHERE item_type = 'Comment' AND item_id = ? "
                    + "AND related_type = 'Account' AND related_id = ? AND event = 'create' "
                    + "AND whodunnit = ?",
                Integer.class, commentId, accountId, senderId.toString()));
        } finally {
            greenMail.stop();
            clearFixtures();
        }
    }

    @Test
    void dropboxCreatesKeywordAssetsAndWritesCreateVersions() throws Exception {
        clearFixtures();
        GreenMail greenMail = greenMail();
        greenMail.start();
        try {
            greenMail.setUser(DROPBOX_EMAIL, "dropbox", "secret");
            insertSender();
            insertDropboxSetting(greenMail.getImap().getPort());
            sendMessage(greenMail, SENDER_EMAIL, DROPBOX_EMAIL, "<ab273-e2e-keyword@example.test>",
                "Account: AB273 Keyword Account\nKeyword body", "text/plain");

            DropboxProcessor processor = dropboxProcessor();
            processor.process(false);

            Long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE name = 'AB273 Keyword Account'", Long.class);
            assertEquals("Keyword body", jdbcTemplate.queryForObject(
                "SELECT body FROM emails WHERE mediator_type = 'Account' AND mediator_id = ?",
                String.class, accountId));
            assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM versions WHERE item_type = 'Account' AND item_id = ? "
                    + "AND event = 'create' AND whodunnit = (SELECT id::text FROM users WHERE username = ?)",
                Integer.class, accountId, SENDER_USERNAME));
        } finally {
            greenMail.stop();
            clearFixtures();
        }
    }

    @Test
    void dropboxMatchesAlternateRecipientsUpdatesLeadAndAttachesContactToAccount() throws Exception {
        clearFixtures();
        GreenMail greenMail = greenMail();
        greenMail.start();
        try {
            greenMail.setUser(DROPBOX_EMAIL, "dropbox", "secret");
            insertSender();
            Long senderId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, SENDER_USERNAME);
            int accountId = jdbcTemplate.queryForObject("""
                INSERT INTO accounts (user_id, name, email, access, created_at, updated_at)
                VALUES (?, 'AB273 Related Account', 'account-primary@example.test', 'Public', now(), now())
                RETURNING id
                """, Integer.class, senderId);
            int contactId = jdbcTemplate.queryForObject("""
                INSERT INTO contacts
                    (user_id, first_name, last_name, access, alt_email, created_at, updated_at)
                VALUES (?, 'AB273', 'Related Contact', 'Public', 'contact-alt@example.test', now(), now())
                RETURNING id
                """, Integer.class, senderId);
            jdbcTemplate.update("""
                INSERT INTO account_contacts (account_id, contact_id, created_at, updated_at)
                VALUES (?, ?, now(), now())
                """, accountId, contactId);
            int leadId = jdbcTemplate.queryForObject("""
                INSERT INTO leads
                    (user_id, first_name, last_name, access, alt_email, status, created_at, updated_at)
                VALUES (?, 'AB273', 'Related Lead', 'Public', 'lead-alt@example.test', 'new', now(), now())
                RETURNING id
                """, Integer.class, senderId);
            insertDropboxSetting(greenMail.getImap().getPort());
            sendMessage(greenMail, SENDER_EMAIL, DROPBOX_EMAIL, "<ab273-e2e-lead@example.test>",
                "Please review these details", "text/plain", "lead-alt@example.test");
            sendMessage(greenMail, SENDER_EMAIL, DROPBOX_EMAIL, "<ab273-e2e-contact@example.test>",
                "Please review these details", "text/plain", "contact-alt@example.test");
            sendMessage(greenMail, SENDER_EMAIL, "account-primary@example.test",
                "<ab273-e2e-account@example.test>", "Please review these details", "text/plain");

            dropboxProcessor().process(false);

            assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM emails WHERE mediator_type = 'Lead' AND mediator_id = ?",
                Integer.class, leadId), "lead recipient was not attached");
            assertEquals("contacted", jdbcTemplate.queryForObject(
                "SELECT status FROM leads WHERE id = ?", String.class, leadId));
            assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM emails WHERE mediator_type = 'Contact' AND mediator_id = ?",
                Integer.class, contactId));
            assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM emails WHERE mediator_type = 'Account' AND mediator_id = ?",
                Integer.class, accountId));
        } finally {
            greenMail.stop();
            clearFixtures();
        }
    }

    @Test
    void dropboxCopiesInvalidMessagesWithoutExpungingAndArchivesValidMessages() throws Exception {
        clearFixtures();
        GreenMail greenMail = greenMail();
        greenMail.start();
        try {
            greenMail.setUser(DROPBOX_EMAIL, "dropbox", "secret");
            insertSender();
            insertMailboxSetting("email_dropbox", DROPBOX_EMAIL, "dropbox", greenMail.getImap().getPort(),
                "Processed", "Invalid");
            jdbcTemplate.update("""
                INSERT INTO accounts (name, email, access, created_at, updated_at)
                VALUES ('AB273 Shared Account', 'shared@example.test', 'Shared', now(), now())
                """);
            createMailboxFolder(greenMail, "Processed");
            createMailboxFolder(greenMail, "Invalid");
            sendMessage(greenMail, "unknown@example.test", DROPBOX_EMAIL, "<ab273-e2e-unknown@example.test>",
                "Unknown sender", "text/plain");
            sendMessage(greenMail, SENDER_EMAIL, DROPBOX_EMAIL, "<ab273-e2e-html@example.test>",
                "<p>HTML only</p>", "text/html");
            sendMessage(greenMail, SENDER_EMAIL, "shared@example.test", "<ab273-e2e-shared@example.test>",
                "Shared asset", "text/plain");
            sendMessage(greenMail, SENDER_EMAIL, DROPBOX_EMAIL, "<ab273-e2e-archive@example.test>",
                "Account: AB273 Archived Account\nArchived body", "text/plain");

            dropboxProcessor().process(false);

            assertTrue(messageHasFlag(greenMail, "INBOX", "<ab273-e2e-unknown@example.test>",
                Flags.Flag.DELETED));
            assertTrue(messageExists(greenMail, "Invalid", "<ab273-e2e-unknown@example.test>"));
            assertTrue(messageHasFlag(greenMail, "INBOX", "<ab273-e2e-html@example.test>",
                Flags.Flag.DELETED));
            assertTrue(messageExists(greenMail, "Invalid", "<ab273-e2e-html@example.test>"));
            assertTrue(messageHasFlag(greenMail, "INBOX", "<ab273-e2e-shared@example.test>",
                Flags.Flag.DELETED));
            assertTrue(messageExists(greenMail, "Invalid", "<ab273-e2e-shared@example.test>"));
            assertTrue(messageHasFlag(greenMail, "INBOX", "<ab273-e2e-archive@example.test>",
                Flags.Flag.SEEN));
            assertTrue(messageExists(greenMail, "Processed", "<ab273-e2e-archive@example.test>"));
        } finally {
            greenMail.stop();
            clearFixtures();
        }
    }

    @Test
    void dropboxDryRunLeavesMessagesUnseenAndDoesNotCreateAssets() throws Exception {
        clearFixtures();
        GreenMail greenMail = greenMail();
        greenMail.start();
        try {
            greenMail.setUser(DROPBOX_EMAIL, "dropbox", "secret");
            insertSender();
            insertDropboxSetting(greenMail.getImap().getPort());
            sendMessage(greenMail, SENDER_EMAIL, DROPBOX_EMAIL, "<ab273-e2e-dry-run@example.test>",
                "Account: AB273 Dry Run Account\nBody", "text/plain");

            dropboxProcessor().process(true);

            assertFalse(messageHasFlag(greenMail, "INBOX", "<ab273-e2e-dry-run@example.test>",
                Flags.Flag.SEEN));
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM accounts WHERE name = 'AB273 Dry Run Account'", Integer.class));
        } finally {
            greenMail.stop();
            clearFixtures();
        }
    }

    @Test
    void dropboxCreatesContactAndAccountForUnknownDomainRecipient() throws Exception {
        clearFixtures();
        GreenMail greenMail = greenMail();
        greenMail.start();
        try {
            greenMail.setUser(DROPBOX_EMAIL, "dropbox", "secret");
            insertSender();
            Setting defaultAccess = new Setting();
            defaultAccess.setName("default_access");
            defaultAccess.setValue("Shared");
            settingRepository.saveAndFlush(defaultAccess);
            insertDropboxSetting(greenMail.getImap().getPort());
            sendMessage(greenMail, SENDER_EMAIL, "new-contact@example.test",
                "<ab273-e2e-domain@example.test>", "Unknown domain", "text/plain");

            dropboxProcessor().process(false);

            Integer contactId = jdbcTemplate.queryForObject(
                "SELECT id FROM contacts WHERE email = 'new-contact@example.test'", Integer.class);
            Integer accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = 'new-contact@example.test'", Integer.class);
            assertEquals("Private", jdbcTemplate.queryForObject(
                "SELECT access FROM contacts WHERE id = ?", String.class, contactId));
            assertEquals("Private", jdbcTemplate.queryForObject(
                "SELECT access FROM accounts WHERE id = ?", String.class, accountId));
            assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM account_contacts WHERE account_id = ? AND contact_id = ?",
                Integer.class, accountId, contactId));
            assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM emails WHERE subject = ?", Integer.class,
                subjectFor("<ab273-e2e-domain@example.test>")));
        } finally {
            greenMail.stop();
            clearFixtures();
        }
    }

    private DropboxProcessor dropboxProcessor() {
        JobsProperties properties = new JobsProperties();
        properties.setOwner("spring");
        return new DropboxProcessor(
            mailSettings,
            userRepository,
            entityManager,
            jdbcTemplate,
            new JobsOwner(properties),
            new ImapClient(),
            transactionManager);
    }

    private void clearFixtures() {
        jdbcTemplate.update("DELETE FROM versions WHERE item_type = 'Email' AND item_id IN "
            + "(SELECT id FROM emails WHERE subject LIKE 'AB273 integration %')");
        jdbcTemplate.update("DELETE FROM emails WHERE subject LIKE 'AB273 integration %'");
        jdbcTemplate.update("DELETE FROM versions WHERE item_type = 'Email' AND item_id IN "
            + "(SELECT id FROM emails WHERE imap_message_id LIKE '<ab273-e2e-%')");
        jdbcTemplate.update("DELETE FROM emails WHERE imap_message_id LIKE '<ab273-e2e-%'");
        jdbcTemplate.update("DELETE FROM versions WHERE item_type = 'Account' AND item_id IN "
            + "(SELECT id FROM accounts WHERE name LIKE 'AB273 %' "
            + "OR email = 'new-contact@example.test')");
        jdbcTemplate.update("DELETE FROM versions WHERE item_type = 'Contact' AND item_id IN "
            + "(SELECT id FROM contacts WHERE first_name = 'AB273' "
            + "OR email = 'new-contact@example.test')");
        jdbcTemplate.update("DELETE FROM versions WHERE item_type = 'Lead' AND item_id IN "
            + "(SELECT id FROM leads WHERE first_name = 'AB273')");
        jdbcTemplate.update("DELETE FROM account_contacts WHERE account_id IN "
            + "(SELECT id FROM accounts WHERE name LIKE 'AB273 %' "
            + "OR email = 'new-contact@example.test') OR contact_id IN "
            + "(SELECT id FROM contacts WHERE first_name = 'AB273' "
            + "OR email = 'new-contact@example.test')");
        jdbcTemplate.update("DELETE FROM contacts WHERE first_name = 'AB273' "
            + "OR email = 'new-contact@example.test'");
        jdbcTemplate.update("DELETE FROM leads WHERE first_name = 'AB273'");
        jdbcTemplate.update("DELETE FROM accounts WHERE name LIKE 'AB273 %' "
            + "OR email = 'new-contact@example.test'");
        jdbcTemplate.update("DELETE FROM versions WHERE item_type = 'Comment' AND item_id IN "
            + "(SELECT id FROM comments WHERE commentable_id IN "
            + "(SELECT id FROM accounts WHERE name = 'Mention Account'))");
        jdbcTemplate.update("DELETE FROM comments WHERE commentable_id IN "
            + "(SELECT id FROM accounts WHERE name = 'Mention Account')");
        jdbcTemplate.update("DELETE FROM versions WHERE item_type = 'Email' AND item_id IN "
            + "(SELECT id FROM emails WHERE imap_message_id = ?)", MESSAGE_ID);
        jdbcTemplate.update("DELETE FROM emails WHERE imap_message_id = ?", MESSAGE_ID);
        jdbcTemplate.update("DELETE FROM versions WHERE item_type = 'Email' AND item_id IN "
            + "(SELECT id FROM emails WHERE mediator_type = 'Account' AND mediator_id IN "
            + "(SELECT id FROM accounts WHERE name = 'Mention Account'))");
        jdbcTemplate.update("DELETE FROM emails WHERE mediator_type = 'Account' AND mediator_id IN "
            + "(SELECT id FROM accounts WHERE name = 'Mention Account')");
        jdbcTemplate.update("DELETE FROM versions WHERE item_type = 'Account' AND item_id IN "
            + "(SELECT id FROM accounts WHERE name = 'Lock Example')");
        jdbcTemplate.update("DELETE FROM emails WHERE mediator_type = 'Account' AND mediator_id IN "
            + "(SELECT id FROM accounts WHERE name = 'Lock Example')");
        jdbcTemplate.update("DELETE FROM accounts WHERE name IN ('Lock Example', 'Must Not Be Processed')");
        jdbcTemplate.update("DELETE FROM settings WHERE name IN "
            + "('email_dropbox', 'email_comment_replies', 'default_access')");
        jdbcTemplate.update("DELETE FROM users WHERE username = ?", SENDER_USERNAME);
        jdbcTemplate.update("DELETE FROM users WHERE username = ?", MENTION_USERNAME);
    }

    private void insertSender() {
        jdbcTemplate.update("""
            INSERT INTO users (username, email, encrypted_password, password_salt, created_at, updated_at)
            VALUES (?, ?, '', '', now(), now())
            """, SENDER_USERNAME, SENDER_EMAIL);
    }

    private void insertDropboxSetting(int imapPort) {
        insertMailboxSetting("email_dropbox", DROPBOX_EMAIL, "dropbox", imapPort);
    }

    private void insertMailboxSetting(String settingName, String address, String username, int imapPort) {
        insertMailboxSetting(settingName, address, username, imapPort, "", "");
    }

    private void insertMailboxSetting(
        String settingName,
        String address,
        String username,
        int imapPort,
        String moveToFolder,
        String invalidFolder
    ) {
        Setting setting = new Setting();
        setting.setName(settingName);
        setting.setValue("""
            ---
            address: %s
            server: 127.0.0.1
            port: "%s"
            user: %s
            password: secret
            scan_folder: INBOX
            move_to_folder: %s
            move_invalid_to_folder: %s
            """.formatted(address, imapPort, username, moveToFolder, invalidFolder));
        settingRepository.saveAndFlush(setting);
    }

    private static GreenMail greenMail() {
        return new GreenMail(new ServerSetup[] {
            new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP),
            new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP)
        });
    }

    private static void sendMessage(GreenMail greenMail, String recipient, String body) throws Exception {
        sendMessage(greenMail, recipient, "AB-273 jobs-mail integration", body);
    }

    private static void sendMessage(GreenMail greenMail, String recipient, String subject, String body)
        throws Exception {
        sendMessage(greenMail, SENDER_EMAIL, recipient, MESSAGE_ID, subject, body, "text/plain", null, recipient);
    }

    private static void sendMessage(
        GreenMail greenMail, String sender, String recipient, String messageId, String body, String contentType
    ) throws Exception {
        sendMessage(
            greenMail, sender, recipient, messageId, subjectFor(messageId), body, contentType, null, DROPBOX_EMAIL);
    }

    private static void sendMessage(
        GreenMail greenMail,
        String sender,
        String recipient,
        String messageId,
        String body,
        String contentType,
        String cc
    ) throws Exception {
        sendMessage(
            greenMail, sender, recipient, messageId, subjectFor(messageId), body, contentType, cc, DROPBOX_EMAIL);
    }

    private static void sendMessage(
        GreenMail greenMail,
        String sender,
        String recipient,
        String messageId,
        String subject,
        String body,
        String contentType,
        String cc,
        String deliveryMailbox
    ) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", "127.0.0.1");
        properties.setProperty("mail.smtp.port", Integer.toString(greenMail.getSmtp().getPort()));
        MimeMessage message = new MimeMessage(Session.getInstance(properties));
        message.setHeader("Message-ID", messageId);
        message.setFrom(new InternetAddress(sender));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
        if (cc != null) {
            message.setRecipient(Message.RecipientType.CC, new InternetAddress(cc));
        }
        message.setSubject(subject);
        message.setContent(body, contentType);
        if ("text/html".equals(contentType)) {
            message.setHeader("Content-Type", "text/html");
        }
        message.setHeader("Message-ID", messageId);
        Transport.send(message, new InternetAddress[] {new InternetAddress(deliveryMailbox)});
        assertTrue(greenMail.waitForIncomingEmail(3_000, 1));
    }

    private static void createMailboxFolder(GreenMail greenMail, String name) throws Exception {
        Store store = new ImapClient().connect(Map.of(
            "server", "127.0.0.1",
            "port", Integer.toString(greenMail.getImap().getPort()),
            "user", "dropbox",
            "password", "secret"));
        try {
            Folder folder = store.getFolder(name);
            folder.create(Folder.HOLDS_MESSAGES);
        } finally {
            store.close();
        }
    }

    private static boolean messageExists(GreenMail greenMail, String folderName, String messageId)
        throws Exception {
        return mailboxMessage(greenMail, folderName, messageId, null);
    }

    private static boolean messageHasFlag(
        GreenMail greenMail, String folderName, String messageId, Flags.Flag flag
    ) throws Exception {
        return mailboxMessage(greenMail, folderName, messageId, flag);
    }

    private static boolean mailboxMessage(
        GreenMail greenMail, String folderName, String messageId, Flags.Flag flag
    )
        throws Exception {
        Store store = new ImapClient().connect(Map.of(
            "server", "127.0.0.1",
            "port", Integer.toString(greenMail.getImap().getPort()),
            "user", "dropbox",
            "password", "secret"));
        try {
            Folder folder = store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);
            for (Message message : folder.getMessages()) {
                if (subjectFor(messageId).equals(message.getSubject())) {
                    return flag == null || message.isSet(flag);
                }
            }
            folder.close(false);
            return false;
        } finally {
            store.close();
        }
    }

    private static String subjectFor(String messageId) {
        return "AB273 integration " + messageId.replaceAll("[<>]", "");
    }

    private static int unseenMessages(GreenMail greenMail) throws Exception {
        Store store = new ImapClient().connect(Map.of(
            "server", "127.0.0.1",
            "port", Integer.toString(greenMail.getImap().getPort()),
            "user", "dropbox",
            "password", "secret"));
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            int unseen = 0;
            for (Message message : inbox.getMessages()) {
                if (!message.isSet(jakarta.mail.Flags.Flag.SEEN)) {
                    unseen++;
                }
            }
            inbox.close(false);
            return unseen;
        } finally {
            store.close();
        }
    }

    private int emailCount() {
        return jdbcTemplate.queryForObject(
            """
                SELECT count(*) FROM emails
                WHERE mediator_type = 'Account'
                  AND mediator_id = (SELECT id FROM accounts WHERE name = 'Lock Example')
                """, Integer.class);
    }
}
