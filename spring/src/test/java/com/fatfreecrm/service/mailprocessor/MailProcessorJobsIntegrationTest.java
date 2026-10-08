package com.fatfreecrm.service.mailprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fatfreecrm.config.JobsProperties;
import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.AddressRepository;
import com.fatfreecrm.repository.CommentRepository;
import com.fatfreecrm.repository.SettingRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.service.jobs.AccountEnrichmentTrigger;
import com.fatfreecrm.service.jobs.AccountWebsiteJob;
import com.fatfreecrm.service.jobs.JobLockKey;
import com.fatfreecrm.service.jobs.JobLockService;
import com.fatfreecrm.service.jobs.JobsHttpClient;
import com.fatfreecrm.service.jobs.JobsOwner;
import com.fatfreecrm.service.jobs.WikidataJob;
import com.fatfreecrm.service.mail.CommentNotificationService;
import com.fatfreecrm.service.mail.MailDeliveryScheduler;
import com.fatfreecrm.service.mail.MailSettingsService;
import com.fatfreecrm.service.audit.VersionRecorder;
import com.fatfreecrm.service.history.RailsRowAttributes;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.io.InputStream;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Assertions;

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
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AddressRepository addressRepository;
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
    @Autowired
    private RailsRowAttributes rowAttributes;
    @Autowired
    private VersionRecorder versionRecorder;

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
                rowAttributes,
                versionRecorder,
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
                rowAttributes,
                versionRecorder,
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
                rowAttributes,
                versionRecorder,
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
            assertRailsScenario("comment_reply", jdbcTemplate.query(
                "SELECT item_type, event, related_type, whodunnit FROM versions ORDER BY id",
                (rows, index) -> springVersionSignature(rows.getString("item_type"), rows.getString("event"),
                    rows.getString("related_type"), rows.getString("whodunnit"))));
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
    void dropboxKeywordLeadMatchesRailsVersionScenario() throws Exception {
        clearFixtures();
        GreenMail greenMail = greenMail();
        greenMail.start();
        try {
            greenMail.setUser(DROPBOX_EMAIL, "dropbox", "secret");
            insertSender();
            insertDropboxSetting(greenMail.getImap().getPort());
            sendMessage(greenMail, SENDER_EMAIL, DROPBOX_EMAIL, "<ab273-e2e-keyword-lead@example.test>",
                "Lead: AB273 Keyword Lead\nKeyword lead body", "text/plain");

            dropboxProcessor().process(false);

            Long leadId = jdbcTemplate.queryForObject(
                "SELECT id FROM leads WHERE first_name = 'AB273' AND last_name = 'Keyword Lead'", Long.class);
            assertRailsScenario("dropbox_keyword_lead", jdbcTemplate.query("""
                SELECT item_type, event, related_type, whodunnit
                FROM versions
                WHERE (item_type = 'Lead' AND item_id = ?)
                   OR (item_type = 'Email' AND related_type = 'Lead' AND related_id = ?)
                ORDER BY id
                """, (rows, index) -> springVersionSignature(rows.getString("item_type"), rows.getString("event"),
                    rows.getString("related_type"), rows.getString("whodunnit")), leadId, leadId));
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
            assertRailsScenario("dropbox_attach_new_lead", jdbcTemplate.query("""
                SELECT item_type, event, related_type, whodunnit
                FROM versions
                WHERE (item_type = 'Email' AND related_type = 'Lead' AND related_id = ?)
                   OR (item_type = 'Lead' AND item_id = ?)
                ORDER BY id
                """, (rows, index) -> springVersionSignature(rows.getString("item_type"), rows.getString("event"),
                    rows.getString("related_type"), rows.getString("whodunnit")), leadId, leadId));
        } finally {
            greenMail.stop();
            clearFixtures();
        }
    }

    @Test
    void dropboxAttachToAccountMatchesRailsVersionScenario() throws Exception {
        clearFixtures();
        GreenMail greenMail = greenMail();
        greenMail.start();
        try {
            greenMail.setUser(DROPBOX_EMAIL, "dropbox", "secret");
            insertSender();
            Long senderId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, SENDER_USERNAME);
            int accountId = jdbcTemplate.queryForObject("""
                INSERT INTO accounts (user_id, name, access, created_at, updated_at)
                VALUES (?, 'AB273 Attach Account', 'Public', now(), now())
                RETURNING id
                """, Integer.class, senderId);
            int contactId = jdbcTemplate.queryForObject("""
                INSERT INTO contacts (user_id, first_name, last_name, access, alt_email, created_at, updated_at)
                VALUES (?, 'AB273', 'Attach Contact', 'Public', 'contact-alt@example.test', now(), now())
                RETURNING id
                """, Integer.class, senderId);
            jdbcTemplate.update("""
                INSERT INTO account_contacts (account_id, contact_id, created_at, updated_at)
                VALUES (?, ?, now(), now())
                """, accountId, contactId);
            insertDropboxSetting(greenMail.getImap().getPort());
            sendMessage(greenMail, SENDER_EMAIL, DROPBOX_EMAIL, "<ab273-e2e-attach-account@example.test>",
                "Please review these details", "text/plain", "contact-alt@example.test");

            dropboxProcessor().process(false);

            assertRailsScenario("dropbox_attach_to_account", jdbcTemplate.query("""
                SELECT item_type, event, related_type, whodunnit
                FROM versions
                WHERE (item_type = 'Email' AND related_type = 'Contact' AND related_id = ?)
                   OR (item_type = 'Contact' AND item_id = ?)
                   OR (item_type = 'Email' AND related_type = 'Account' AND related_id = ?)
                   OR (item_type = 'Account' AND item_id = ?)
                ORDER BY id
                """, (rows, index) -> springVersionSignature(rows.getString("item_type"), rows.getString("event"),
                    rows.getString("related_type"), rows.getString("whodunnit")), contactId, contactId,
                accountId, accountId));
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
            assertRailsScenario("dropbox_create_and_attach", jdbcTemplate.query("""
                SELECT item_type, event, related_type, whodunnit
                FROM versions
                WHERE (item_type = 'Account' AND item_id = ? AND event = 'create')
                   OR (item_type = 'Contact' AND item_id = ?)
                   OR (item_type = 'AccountContact' AND related_type = 'Contact' AND related_id = ?)
                   OR (item_type = 'Email' AND related_type = 'Contact' AND related_id = ?)
                ORDER BY id
                """, (rows, index) -> springVersionSignature(rows.getString("item_type"), rows.getString("event"),
                    rows.getString("related_type"), rows.getString("whodunnit")), accountId, contactId,
                contactId, contactId));
            assertEquals(java.util.List.of("Email|create|Account|sender", "Account|update|none|sender"),
                jdbcTemplate.query("""
                    SELECT item_type, event, related_type, whodunnit
                    FROM versions
                    WHERE (item_type = 'Email' AND related_type = 'Account' AND related_id = ?)
                       OR (item_type = 'Account' AND item_id = ? AND event = 'update')
                    ORDER BY id
                    """, (rows, index) -> springVersionSignature(rows.getString("item_type"), rows.getString("event"),
                        rows.getString("related_type"), rows.getString("whodunnit")), accountId, accountId),
                "Spring's create-and-attach path also attaches the account");
        } finally {
            greenMail.stop();
            clearFixtures();
        }
    }

    @Test
    void accountWebsiteJobWritesRailsCompatibleVersions() throws Exception {
        clearFixtures();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/site", exchange -> respond(exchange, """
            <script type="application/ld+json">
            {"@type":"Organization","telephone":"+1-555-0100",
             "address":{"@type":"PostalAddress","streetAddress":"10 Main St","addressLocality":"Hartford"}}
            </script>
            """));
        server.start();
        try {
            long accountId = jdbcTemplate.queryForObject("""
                INSERT INTO accounts (name, website, access, created_at, updated_at)
                VALUES ('AB273 Website Job', ?, 'Public', now(), now()) RETURNING id
                """, Long.class, "http://127.0.0.1:" + server.getAddress().getPort() + "/site");
            JobsProperties properties = springJobsProperties();

            accountWebsiteJob(properties).perform(accountId);

            assertRailsScenario("account_website_job", jdbcTemplate.query("""
                SELECT item_type, event, related_type, whodunnit
                FROM versions
                WHERE (item_type = 'Account' AND item_id = ?)
                   OR (item_type = 'Address' AND related_type = 'Account' AND related_id = ?)
                ORDER BY id
                """, (rows, index) -> springVersionSignature(rows.getString("item_type"), rows.getString("event"),
                    rows.getString("related_type"), rows.getString("whodunnit")), accountId, accountId));
        } finally {
            server.stop(0);
            clearFixtures();
        }
    }

    @Test
    void wikidataJobWritesRailsCompatibleVersions() throws Exception {
        clearFixtures();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sparql", exchange -> respond(exchange, """
            {"results":{"bindings":[{"description":{"value":"Wikidata description"}}]}}
            """));
        server.start();
        try {
            long accountId = jdbcTemplate.queryForObject("""
                INSERT INTO accounts (name, wikidata_id, access, created_at, updated_at)
                VALUES ('AB273 Wikidata Job', 'Q42', 'Public', now(), now()) RETURNING id
                """, Long.class);
            JobsProperties properties = springJobsProperties();
            properties.getWikidata().setEndpoint(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/sparql");

            wikidataJob(properties).perform(accountId);

            assertRailsScenario("wikidata_service", jdbcTemplate.query("""
                SELECT item_type, event, related_type, whodunnit
                FROM versions WHERE item_type = 'Account' AND item_id = ? ORDER BY id
                """, (rows, index) -> springVersionSignature(rows.getString("item_type"), rows.getString("event"),
                    rows.getString("related_type"), rows.getString("whodunnit")), accountId));
        } finally {
            server.stop(0);
            clearFixtures();
        }
    }

    private JobsProperties springJobsProperties() {
        JobsProperties properties = new JobsProperties();
        properties.setOwner("spring");
        return properties;
    }

    private AccountWebsiteJob accountWebsiteJob(JobsProperties properties) {
        AccountWebsiteJob job = new AccountWebsiteJob();
        ReflectionTestUtils.setField(job, "jobsOwner", new JobsOwner(properties));
        ReflectionTestUtils.setField(job, "httpClient", new JobsHttpClient(properties));
        ReflectionTestUtils.setField(job, "accountRepository", accountRepository);
        ReflectionTestUtils.setField(job, "addressRepository", addressRepository);
        ReflectionTestUtils.setField(job, "versionRecorder", versionRecorder);
        ReflectionTestUtils.setField(job, "rowAttributes", rowAttributes);
        ReflectionTestUtils.setField(job, "objectMapper", JSON);
        ReflectionTestUtils.setField(job, "enrichmentTrigger", mock(AccountEnrichmentTrigger.class));
        return job;
    }

    private WikidataJob wikidataJob(JobsProperties properties) {
        WikidataJob job = new WikidataJob();
        ReflectionTestUtils.setField(job, "jobsOwner", new JobsOwner(properties));
        ReflectionTestUtils.setField(job, "httpClient", new JobsHttpClient(properties));
        ReflectionTestUtils.setField(job, "properties", properties);
        ReflectionTestUtils.setField(job, "accountRepository", accountRepository);
        ReflectionTestUtils.setField(job, "versionRecorder", versionRecorder);
        ReflectionTestUtils.setField(job, "rowAttributes", rowAttributes);
        ReflectionTestUtils.setField(job, "objectMapper", JSON);
        ReflectionTestUtils.setField(job, "enrichmentTrigger", mock(AccountEnrichmentTrigger.class));
        return job;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
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
            rowAttributes,
            versionRecorder,
            new JobsOwner(properties),
            new ImapClient(),
            transactionManager);
    }

    private void assertRailsScenario(String scenarioName, java.util.List<String> actual) throws Exception {
        JsonNode fixture;
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("audit/rails_audit_goldens.json")) {
            fixture = JSON.readTree(input);
        }
        JsonNode scenario = null;
        for (JsonNode candidate : fixture.path("scenarios")) {
            if (scenarioName.equals(candidate.path("name").asText())) {
                scenario = candidate;
                break;
            }
        }
        Assertions.assertNotNull(scenario, "missing Rails scenario " + scenarioName);
        java.util.List<String> expected = new java.util.ArrayList<>();
        scenario.path("rows").forEach(row -> expected.add(versionSignature(
            row.path("item_type").asText(), row.path("event").asText(),
            row.path("related_type").isNull() ? null : row.path("related_type").asText(),
            row.path("whodunnit").isNull() ? null : row.path("whodunnit").asText())));
        assertEquals(expected, actual, scenarioName + " Spring/Rails version sequence");
    }

    private static String versionSignature(String itemType, String event, String relatedType, String whodunnit) {
        return itemType + "|" + event + "|" + (relatedType == null ? "none" : relatedType) + "|"
            + (whodunnit == null ? "nil" : "sender");
    }

    private String springVersionSignature(String itemType, String event, String relatedType, String whodunnit) {
        if (whodunnit != null) {
            String senderId = jdbcTemplate.query(
                "SELECT id::text FROM users WHERE username = ?",
                resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                SENDER_USERNAME);
            if (!whodunnit.equals(senderId)) {
                return itemType + "|" + event + "|" + (relatedType == null ? "none" : relatedType)
                    + "|unexpected:" + whodunnit;
            }
        }
        return versionSignature(itemType, event, relatedType, whodunnit);
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
        jdbcTemplate.update("DELETE FROM versions WHERE item_type = 'Address' AND item_id IN "
            + "(SELECT id FROM addresses WHERE addressable_type = 'Account' AND addressable_id IN "
            + "(SELECT id FROM accounts WHERE name LIKE 'AB273 %'))");
        jdbcTemplate.update("DELETE FROM addresses WHERE addressable_type = 'Account' AND addressable_id IN "
            + "(SELECT id FROM accounts WHERE name LIKE 'AB273 %')");
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
