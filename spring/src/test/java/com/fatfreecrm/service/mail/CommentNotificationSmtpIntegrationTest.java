package com.fatfreecrm.service.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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

            notificationService.afterCreate(comment);

            assertTrue(GREEN_MAIL.waitForIncomingEmail(5_000, 1));
            MimeMessage message = GREEN_MAIL.getReceivedMessages()[0];
            assertEquals("subscriber@example.test", message.getAllRecipients()[0].toString());
            assertTrue(message.getSubject().contains("AB273 Notification Account"));
            assertTrue(message.getContent().toString().contains("A reply delivered through SMTP"));
        } finally {
            clearFixtures();
        }
    }

    private long insertUser(String username, String email, String firstName, String lastName) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users
                (username, email, first_name, last_name, encrypted_password, password_salt,
                 subscribe_to_comment_replies, created_at, updated_at)
            VALUES (?, ?, ?, ?, '', '', true, now(), now())
            RETURNING id
            """, Long.class, username, email, firstName, lastName);
    }

    private void clearFixtures() {
        jdbcTemplate.update("DELETE FROM accounts WHERE name = 'AB273 Notification Account'");
        jdbcTemplate.update("DELETE FROM users WHERE username IN (?, ?)", AUTHOR, SUBSCRIBER);
    }

    private static GreenMail startGreenMail() {
        GreenMail greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        greenMail.start();
        return greenMail;
    }
}
