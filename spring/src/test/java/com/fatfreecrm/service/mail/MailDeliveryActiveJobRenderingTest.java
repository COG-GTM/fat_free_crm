package com.fatfreecrm.service.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.config.JobsProperties;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.service.jobs.JobsOwner;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins how {@link MailDeliveryService#deliverActiveJob(String)} turns the Rails
 * {@code ActionMailer::MailDeliveryJob} envelope written by Spring callbacks into mailer inputs:
 * {@code url_for(entity)} routes, {@code User#name} ({@code first_name} or {@code username}) for the
 * assigner and {@code User#full_name} (names or email) plus {@code tag_list} for comment mail.
 */
class MailDeliveryActiveJobRenderingTest {

    private static final RenderedMail RENDERED =
        new RenderedMail("subject", "crm@example.test", "to@example.test", "text/plain", "body");

    private final EntityManager entityManager = mock(EntityManager.class);
    private final MailRendererService renderer = mock(MailRendererService.class);
    private final MailSettingsService settings = mock(MailSettingsService.class);
    private final List<MimeMessage> sent = new ArrayList<>();
    private MailDeliveryService service;

    @BeforeEach
    void setUp() {
        when(settings.section("smtp")).thenReturn(Map.of());
        when(renderer.host()).thenReturn("https://crm.example.test/");
        when(renderer.assignment(any(), any(), any(), any(), any())).thenReturn(RENDERED);
        when(renderer.commentNotification(any(), any(), any(), any(), anyLong(), any(), any())).thenReturn(RENDERED);
        JobsProperties properties = new JobsProperties();
        properties.setOwner("spring");
        JavaMailSenderImpl sender = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage mimeMessage) {
                sent.add(mimeMessage);
            }
        };
        service = new MailDeliveryService(
            sender, new ObjectMapper(), entityManager, renderer, settings, new JobsOwner(properties));
    }

    @Test
    void assignmentMailUsesEntityRouteAndAssignerShortName() throws Exception {
        User assignee = user(2L, "bob", "bob@example.test", null, null);
        User assigner = user(1L, "alice", "alice@example.test", "", null);
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", 101L);
        account.setName("Acme");
        account.setAssignedTo(assignee);
        when(entityManager.find(Account.class, 101L)).thenReturn(account);
        when(entityManager.find(User.class, 1L)).thenReturn(assigner);

        service.deliverActiveJob(assignmentEnvelope("Account", 101L, 1L));

        verify(renderer).assignment(
            "bob@example.test", "Acme", "Account", "https://crm.example.test/accounts/101", "alice");
        assertEquals(1, sent.size());
    }

    @Test
    void opportunityRouteIsPluralisedLikeRails() throws Exception {
        User assignee = user(2L, "bob", "bob@example.test", null, null);
        User assigner = user(1L, "alice", "alice@example.test", "Alice", "Admin");
        Opportunity opportunity = new Opportunity();
        ReflectionTestUtils.setField(opportunity, "id", 55L);
        opportunity.setName("Big deal");
        opportunity.setAssignedTo(assignee);
        when(entityManager.find(Opportunity.class, 55L)).thenReturn(opportunity);
        when(entityManager.find(User.class, 1L)).thenReturn(assigner);

        service.deliverActiveJob(assignmentEnvelope("Opportunity", 55L, 1L));

        verify(renderer).assignment(
            "bob@example.test", "Big deal", "Opportunity", "https://crm.example.test/opportunities/55", "Alice");
    }

    @Test
    void contactNameJoinsFirstAndLastNameAndBlankHostYieldsNoUrl() throws Exception {
        when(renderer.host()).thenReturn("");
        User assignee = user(2L, "bob", "bob@example.test", null, null);
        User assigner = user(1L, "alice", "alice@example.test", null, "Admin");
        Contact contact = new Contact();
        ReflectionTestUtils.setField(contact, "id", 7L);
        contact.setFirstName("Jane");
        contact.setLastName("Doe");
        contact.setAssignedTo(assignee);
        when(entityManager.find(Contact.class, 7L)).thenReturn(contact);
        when(entityManager.find(User.class, 1L)).thenReturn(assigner);

        service.deliverActiveJob(assignmentEnvelope("Contact", 7L, 1L));

        verify(renderer).assignment("bob@example.test", "Jane Doe", "Contact", "", "alice");
    }

    @Test
    void assignmentWithoutRecipientEmailIsRejected() {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", 101L);
        account.setAssignedTo(user(2L, "bob", "  ", null, null));
        when(entityManager.find(Account.class, 101L)).thenReturn(account);
        when(entityManager.find(User.class, 1L)).thenReturn(user(1L, "alice", "alice@example.test", null, null));

        assertThrows(IllegalArgumentException.class,
            () -> service.deliverActiveJob(assignmentEnvelope("Account", 101L, 1L)));

        account.setAssignedTo(null);
        assertThrows(IllegalArgumentException.class,
            () -> service.deliverActiveJob(assignmentEnvelope("Account", 101L, 1L)));
        assertTrue(sent.isEmpty());
    }

    @Test
    void commentMailUsesAuthorFullNameFallingBackToEmailAndJoinsTags() throws Exception {
        User recipient = user(3L, "carol", "carol@example.test", null, null);
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", 101L);
        account.setName("Acme");
        Comment comment = comment(9L, user(1L, "alice", "alice@example.test", " ", null), 101);
        when(entityManager.find(User.class, 3L)).thenReturn(recipient);
        when(entityManager.find(Comment.class, 9L)).thenReturn(comment);
        when(entityManager.find(Account.class, 101)).thenReturn(account);
        Query tags = mock(Query.class);
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(tags);
        when(tags.setParameter(any(String.class), any())).thenReturn(tags);
        when(tags.getResultList()).thenReturn(List.of("vip", "hot"));

        service.deliverActiveJob(commentEnvelope(3L, 9L));

        verify(renderer).commentNotification(
            "carol@example.test", "alice@example.test", "Acme", "Account", 101L, "vip, hot", "Hello");
        verify(tags).setParameter("type", "Account");
        verify(tags).setParameter("id", 101);
    }

    @Test
    void commentMailUsesAuthorNamesWhenPresent() throws Exception {
        User recipient = user(3L, "carol", "carol@example.test", null, null);
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", 101L);
        account.setName("Acme");
        Comment comment = comment(9L, user(1L, "alice", "alice@example.test", "Alice", "Admin"), 101);
        when(entityManager.find(User.class, 3L)).thenReturn(recipient);
        when(entityManager.find(Comment.class, 9L)).thenReturn(comment);
        when(entityManager.find(Account.class, 101)).thenReturn(account);
        Query tags = mock(Query.class);
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(tags);
        when(tags.setParameter(any(String.class), any())).thenReturn(tags);
        when(tags.getResultList()).thenReturn(List.of());

        service.deliverActiveJob(commentEnvelope(3L, 9L));

        verify(renderer).commentNotification(
            "carol@example.test", "Alice Admin", "Acme", "Account", 101L, "", "Hello");
    }

    @Test
    void unsupportedOrMalformedEnvelopesAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> service.deliverActiveJob("{\"arguments\":[\"UserMailer\"]}"));
        assertThrows(IllegalArgumentException.class,
            () -> service.deliverActiveJob("{\"arguments\":[\"OtherMailer\",\"whatever\",\"deliver_now\"]}"));
        assertThrows(IllegalArgumentException.class,
            () -> service.deliverActiveJob("{\"arguments\":[\"UserMailer\",\"assigned_entity_notification\","
                + "\"deliver_now\",{\"args\":[]}]}"));
        verifyNoInteractions(renderer);
        assertTrue(sent.isEmpty());
    }

    @Test
    void railsOwnedJobsAreLeftAlone() throws Exception {
        JobsProperties properties = new JobsProperties();
        properties.setOwner("rails");
        MailDeliveryService railsOwned = new MailDeliveryService(
            new JavaMailSenderImpl(), new ObjectMapper(), entityManager, renderer, settings,
            new JobsOwner(properties));

        railsOwned.deliverActiveJob(assignmentEnvelope("Account", 101L, 1L));

        verifyNoInteractions(renderer, entityManager);
    }

    private static String assignmentEnvelope(String type, long entityId, long userId) {
        return "[{\"job_class\":\"ActionMailer::MailDeliveryJob\",\"arguments\":[\"UserMailer\","
            + "\"assigned_entity_notification\",\"deliver_now\",{\"_aj_ruby2_keywords\":[\"args\"],\"args\":["
            + "{\"_aj_globalid\":\"gid://fat-free-crm/" + type + "/" + entityId + "\"},"
            + "{\"_aj_globalid\":\"gid://fat-free-crm/User/" + userId + "\"}]}]}]";
    }

    private static String commentEnvelope(long userId, long commentId) {
        return "{\"job_class\":\"ActionMailer::MailDeliveryJob\",\"arguments\":[\"SubscriptionMailer\","
            + "\"comment_notification\",\"deliver_now\",{\"_aj_ruby2_keywords\":[\"args\"],\"args\":["
            + "{\"_aj_globalid\":\"gid://fat-free-crm/User/" + userId + "\"},"
            + "{\"_aj_globalid\":\"gid://fat-free-crm/Comment/" + commentId + "\"}]}]}";
    }

    private static Comment comment(long id, User author, int commentableId) {
        Comment comment = new Comment();
        ReflectionTestUtils.setField(comment, "id", id);
        comment.setUser(author);
        comment.setCommentableType("Account");
        comment.setCommentableId(commentableId);
        comment.setComment("Hello");
        return comment;
    }

    private static User user(long id, String username, String email, String firstName, String lastName) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        return user;
    }
}
