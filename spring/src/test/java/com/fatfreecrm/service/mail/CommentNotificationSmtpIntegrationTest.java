package com.fatfreecrm.service.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.CommentRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.service.jobs.SolidQueueDrainService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "ffcrm.jobs.owner=spring")
class CommentNotificationSmtpIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String AUTHOR = "ab273-notify-author";
    private static final String SUBSCRIBER = "ab273-notify-subscriber";
    private static final GreenMail GREEN_MAIL = startGreenMail();

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CommentNotificationService notificationService;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private SolidQueueDrainService queueDrainService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void smtpProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> GREEN_MAIL.getSmtp().getPort());
    }

    @AfterAll
    static void stopGreenMail() {
        GREEN_MAIL.stop();
    }

    @Test
    void commentReplyNotificationIsDeliveredToSubscribedUser() throws Exception {
        clearFixtures();
        String activeJobId = null;
        try {
            long authorId = insertUser(AUTHOR, "author@example.test", "Reply", "Author");
            long subscriberId = insertUser(SUBSCRIBER, "subscriber@example.test", "Reply", "Subscriber");
            int accountId = jdbcTemplate.queryForObject("""
                INSERT INTO accounts
                    (user_id, name, access, subscribed_users, created_at, updated_at)
                VALUES (?, 'AB273 Notification Account', 'Public', ?, now(), now())
                RETURNING id
                """, Integer.class, authorId, "---\n- " + subscriberId);
            User author = userRepository.findById(authorId).orElseThrow();
            Comment comment = new Comment();
            comment.setCommentableType("Account");
            comment.setCommentableId(accountId);
            comment.setUser(author);
            comment.setComment("A reply delivered through SMTP");
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Comment savedComment = commentRepository.saveAndFlush(comment);
                notificationService.afterCreate(savedComment);
            });
            activeJobId = jdbcTemplate.queryForObject("""
                SELECT active_job_id FROM solid_queue_jobs
                WHERE class_name = 'ActionMailer::MailDeliveryJob'
                ORDER BY id DESC LIMIT 1
                """, String.class);

            assertEquals(1, queueDrainService.drain());
            assertTrue(GREEN_MAIL.waitForIncomingEmail(5_000, 1));
            MimeMessage message = GREEN_MAIL.getReceivedMessages()[0];
            assertEquals("subscriber@example.test", message.getAllRecipients()[0].toString());
            assertTrue(message.getSubject().contains("AB273 Notification Account"));
            assertTrue(message.getContent().toString().contains("A reply delivered through SMTP"));
        } finally {
            clearJob(activeJobId);
            clearFixtures();
        }
    }

    private long insertUser(String username, String email, String firstName, String lastName) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users
                (username, email, first_name, last_name, encrypted_password, password_salt,
                 subscribe_to_comment_replies, confirmed_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, '', '', true, now(), now(), now())
            RETURNING id
            """, Long.class, username, email, firstName, lastName);
    }

    private void clearFixtures() {
        jdbcTemplate.update("DELETE FROM comments WHERE comment = ?", "A reply delivered through SMTP");
        jdbcTemplate.update("DELETE FROM accounts WHERE name = 'AB273 Notification Account'");
        jdbcTemplate.update("DELETE FROM users WHERE username IN (?, ?)", AUTHOR, SUBSCRIBER);
    }

    private void clearJob(String activeJobId) {
        if (activeJobId == null) {
            return;
        }
        jdbcTemplate.update("""
            DELETE FROM solid_queue_ready_executions
            WHERE job_id = (SELECT id FROM solid_queue_jobs WHERE active_job_id = ?)
            """, activeJobId);
        jdbcTemplate.update("DELETE FROM solid_queue_jobs WHERE active_job_id = ?", activeJobId);
    }

    private static GreenMail startGreenMail() {
        GreenMail greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        greenMail.start();
        return greenMail;
    }
}
