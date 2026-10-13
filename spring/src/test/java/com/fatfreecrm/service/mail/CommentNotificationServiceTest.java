package com.fatfreecrm.service.mail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins {@link CommentNotificationService#afterCreate(Comment)} to Rails' {@code Comment#notify_subscribers}
 * (app/models/polymorphic/comment.rb) and {@code User#emailable?} (app/models/users/user.rb):
 * subscribers other than the author who are confirmed, not suspended, have an email and opted into
 * comment replies get a {@code SubscriptionMailer.comment_notification} job.
 */
class CommentNotificationServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final MailDeliveryScheduler deliveryScheduler = mock(MailDeliveryScheduler.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CommentNotificationService service =
        new CommentNotificationService(userRepository, entityManager, deliveryScheduler, jdbcTemplate);

    private final User author = emailableUser(1L);

    @Test
    void mailsEveryEmailableSubscriberExceptTheAuthor() {
        User eligible = emailableUser(2L);
        User alsoEligible = emailableUser(3L);
        Comment comment = commentOn("Account", 10, author);
        stubAccount(10, List.of(1L, 2L, 3L));
        stubExistingUsers(List.of(2L, 3L), eligible, alsoEligible);

        service.afterCreate(comment);

        verify(deliveryScheduler).deliverLater("SubscriptionMailer", "comment_notification", eligible, comment);
        verify(deliveryScheduler).deliverLater("SubscriptionMailer", "comment_notification", alsoEligible, comment);
        verify(deliveryScheduler, never()).deliverLater(anyString(), anyString(), eq(author), any());
    }

    @Test
    void authorOnlySubscriptionSkipsTheUserLookupEntirely() {
        stubAccount(10, List.of(1L));

        service.afterCreate(commentOn("Account", 10, author));

        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(deliveryScheduler);
    }

    @Test
    void unconfirmedSubscriberIsNotEmailable() {
        User unconfirmed = emailableUser(2L);
        unconfirmed.setConfirmedAt(null);
        assertNotMailed(unconfirmed);
    }

    @Test
    void suspendedSubscriberIsNotEmailable() {
        User suspended = emailableUser(2L);
        suspended.setSuspendedAt(Instant.parse("2024-01-01T00:00:00Z"));
        assertNotMailed(suspended);
    }

    @Test
    void subscriberWithoutEmailIsNotEmailable() {
        User noEmail = emailableUser(2L);
        noEmail.setEmail("  ");
        assertNotMailed(noEmail);
        User nullEmail = emailableUser(2L);
        nullEmail.setEmail(null);
        assertNotMailed(nullEmail);
    }

    @Test
    void subscriberWhoOptedOutOfCommentRepliesIsNotMailed() {
        User optedOut = emailableUser(2L);
        optedOut.setSubscribeToCommentReplies(false);
        assertNotMailed(optedOut);
    }

    @Test
    void deletedSubscriberIdsAreIgnored() {
        Comment comment = commentOn("Account", 10, author);
        stubAccount(10, List.of(99L));
        stubExistingUsers(List.of());

        service.afterCreate(comment);

        verifyNoInteractions(deliveryScheduler);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void taskCommentsNotifyTaskSubscribers() {
        User eligible = emailableUser(2L);
        Task task = new Task();
        task.setSubscribedUsers(List.of(2L));
        when(entityManager.find(Task.class, 5)).thenReturn(task);
        stubExistingUsers(List.of(2L), eligible);
        Comment comment = commentOn("Task", 5, author);

        service.afterCreate(comment);

        verify(deliveryScheduler).deliverLater("SubscriptionMailer", "comment_notification", eligible, comment);
    }

    @Test
    void commentWithoutAuthorStillNotifiesSubscribers() {
        User eligible = emailableUser(2L);
        Comment comment = commentOn("Account", 10, null);
        stubAccount(10, List.of(2L));
        stubExistingUsers(List.of(2L), eligible);

        service.afterCreate(comment);

        verify(deliveryScheduler).deliverLater("SubscriptionMailer", "comment_notification", eligible, comment);
    }

    @Test
    void missingCommentableDoesNothing() {
        when(entityManager.find(Account.class, 404)).thenReturn(null);

        service.afterCreate(commentOn("Account", 404, author));

        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(deliveryScheduler);
    }

    @Test
    void unknownCommentableTypeDoesNothing() {
        service.afterCreate(commentOn("Widget", 1, author));

        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(deliveryScheduler);
    }

    private void assertNotMailed(User subscriber) {
        Comment comment = commentOn("Account", 10, author);
        stubAccount(10, List.of(2L));
        stubExistingUsers(List.of(2L), subscriber);

        service.afterCreate(comment);

        verifyNoInteractions(deliveryScheduler);
    }

    private void stubAccount(int id, List<Long> subscribedUsers) {
        Account account = new Account();
        account.setSubscribedUsers(subscribedUsers);
        when(entityManager.find(Account.class, id)).thenReturn(account);
    }

    @SuppressWarnings("unchecked")
    private void stubExistingUsers(List<Long> existingIds, User... users) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(existingIds);
        for (User user : users) {
            Long id = (Long) ReflectionTestUtils.getField(user, "id");
            when(userRepository.findById(id)).thenReturn(Optional.of(user));
        }
    }

    private static Comment commentOn(String type, int commentableId, User user) {
        Comment comment = new Comment();
        comment.setCommentableType(type);
        comment.setCommentableId(commentableId);
        comment.setUser(user);
        comment.setComment("hello");
        return comment;
    }

    private static User emailableUser(long id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setUsername("user" + id);
        user.setEmail("user" + id + "@example.com");
        user.setConfirmedAt(Instant.parse("2024-01-01T00:00:00Z"));
        user.setSubscribeToCommentReplies(true);
        return user;
    }
}
