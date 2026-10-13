package com.fatfreecrm.service.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins {@link CommentNotificationService} to Rails {@code Comment#notify_subscribers}: every
 * subscriber except the comment author who is {@code emailable?} (confirmed, not suspended, has an
 * email) and has {@code subscribe_to_comment_replies} receives {@code SubscriptionMailer
 * .comment_notification.deliver_later}. Tasks are commentable too, and tags are joined in tagging
 * order like {@code entity.tag_list.join(", ")}.
 */
class CommentNotificationServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    private MailDeliveryScheduler scheduler;
    private CommentNotificationService service;

    @BeforeEach
    void setUp() {
        clearData();
        scheduler = mock(MailDeliveryScheduler.class);
        service = new CommentNotificationService(userRepository, entityManager, scheduler, jdbcTemplate);
    }

    @AfterEach
    void clearData() {
        jdbcTemplate.update("DELETE FROM taggings");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void notifiesOnlyEmailableOptedInSubscribersOtherThanTheAuthor() {
        User author = user("author", u -> { });
        User eligible = user("eligible", u -> { });
        User unconfirmed = user("unconfirmed", u -> u.setConfirmedAt(null));
        User suspended = user("suspended", u -> u.setSuspendedAt(Instant.parse("2025-06-01T00:00:00Z")));
        User noEmail = user("noemail", u -> u.setEmail(""));
        User optedOut = user("optedout", u -> u.setSubscribeToCommentReplies(false));
        long accountId = account(author, List.of(author.getId(), eligible.getId(), unconfirmed.getId(),
            suspended.getId(), noEmail.getId(), optedOut.getId(), 987654L));
        Comment comment = comment(author, "Account", accountId);

        service.afterCreate(comment);

        verify(scheduler).deliverLater(eq("SubscriptionMailer"), eq("comment_notification"),
            argUser(eligible.getId()), eq(comment));
        verify(scheduler, never()).deliverLater(eq("SubscriptionMailer"), eq("comment_notification"),
            argUser(author.getId()), any());
        verify(scheduler, org.mockito.Mockito.times(1)).deliverLater(any(), any(), any(), any());
    }

    @Test
    void anonymousCommentNotifiesEveryEligibleSubscriber() {
        User first = user("first", u -> { });
        User second = user("second", u -> { });
        long accountId = account(first, List.of(first.getId(), second.getId()));
        Comment comment = comment(null, "Account", accountId);

        service.afterCreate(comment);

        verify(scheduler).deliverLater(eq("SubscriptionMailer"), eq("comment_notification"),
            argUser(first.getId()), eq(comment));
        verify(scheduler).deliverLater(eq("SubscriptionMailer"), eq("comment_notification"),
            argUser(second.getId()), eq(comment));
    }

    @Test
    void entityWithoutSubscribersSchedulesNothing() {
        User author = user("author", u -> { });
        long accountId = account(author, List.of());

        service.afterCreate(comment(author, "Account", accountId));
        service.afterCreate(comment(author, "Account", 424242L));

        verifyNoInteractions(scheduler);
    }

    @Test
    void taskSubscribersAreNotifiedLikeEntitySubscribers() {
        User author = user("author", u -> { });
        User watcher = user("watcher", u -> { });
        long taskId = jdbcTemplate.queryForObject(
            "INSERT INTO tasks (name, user_id, subscribed_users, created_at, updated_at)"
                + " VALUES ('Call back', ?, ?, now(), now()) RETURNING id",
            Long.class, author.getId(), yaml(List.of(author.getId(), watcher.getId())));

        service.afterCreate(comment(author, "Task", taskId));

        verify(scheduler).deliverLater(eq("SubscriptionMailer"), eq("comment_notification"),
            argUser(watcher.getId()), any());
        verify(scheduler, org.mockito.Mockito.times(1)).deliverLater(any(), any(), any(), any());
    }

    @Test
    void tagsAreJoinedInTaggingOrder() {
        User owner = user("owner", u -> { });
        long accountId = account(owner, List.of());
        long hot = jdbcTemplate.queryForObject(
            "INSERT INTO tags (name) VALUES ('hot') RETURNING id", Long.class);
        long vip = jdbcTemplate.queryForObject(
            "INSERT INTO tags (name) VALUES ('vip') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO taggings (tag_id, taggable_id, taggable_type, context, created_at)"
            + " VALUES (?, ?, 'Account', 'tags', now())", vip, accountId);
        jdbcTemplate.update("INSERT INTO taggings (tag_id, taggable_id, taggable_type, context, created_at)"
            + " VALUES (?, ?, 'Account', 'tags', now())", hot, accountId);
        jdbcTemplate.update("INSERT INTO taggings (tag_id, taggable_id, taggable_type, context, created_at)"
            + " VALUES (?, ?, 'Contact', 'tags', now())", hot, accountId);
        Account account = entityManager.find(Account.class, accountId);

        assertEquals("vip, hot", service.tagsFor(account));

        jdbcTemplate.update("DELETE FROM taggings");
        assertEquals("", service.tagsFor(account));
    }

    private static User argUser(long id) {
        return org.mockito.ArgumentMatchers.argThat(user -> user instanceof User u && id == u.getId());
    }

    private User user(String username, java.util.function.Consumer<User> customiser) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setEncryptedPassword("enc");
        user.setPasswordSalt("salt");
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        user.setSubscribeToCommentReplies(true);
        customiser.accept(user);
        return userRepository.saveAndFlush(user);
    }

    private long account(User owner, List<Long> subscribers) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, user_id, access, subscribed_users, created_at, updated_at)"
                + " VALUES ('Acme', ?, 'Public', ?, now(), now()) RETURNING id",
            Long.class, owner.getId(), yaml(subscribers));
    }

    private static String yaml(List<Long> ids) {
        StringBuilder yaml = new StringBuilder("---\n");
        if (ids.isEmpty()) {
            yaml.append("[]\n");
        }
        for (Long id : ids) {
            yaml.append("- ").append(id).append('\n');
        }
        return yaml.toString();
    }

    private static Comment comment(User author, String commentableType, long commentableId) {
        Comment comment = new Comment();
        comment.setUser(author);
        comment.setCommentableType(commentableType);
        comment.setCommentableId((int) commentableId);
        comment.setComment("Callback notification");
        return comment;
    }
}
