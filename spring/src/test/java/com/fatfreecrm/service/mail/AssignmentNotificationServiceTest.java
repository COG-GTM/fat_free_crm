package com.fatfreecrm.service.mail;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.CrmEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Parity with Rails {@code EntityObserver#after_create} → {@code UserMailer#assigned_entity_notification}:
 * the mail goes out only when a different user is assigned, that user opted in
 * ({@code receive_assigned_notifications}), has an email, {@code Setting.host} is set and a current user
 * is known. The entity name is {@code entity.name} ({@code full_name} for Contact and Lead) and the URL
 * is {@code url_for(entity)}.
 */
class AssignmentNotificationServiceTest {

    private static final RenderedMail RENDERED =
        new RenderedMail("subject", "from@example.test", "bob@example.test", "text/html", "html");

    private MailRendererService renderer;
    private MailDeliveryScheduler scheduler;
    private AssignmentNotificationService service;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        renderer = mock(MailRendererService.class);
        scheduler = mock(MailDeliveryScheduler.class);
        when(renderer.host()).thenReturn("http://crm.example.test/");
        when(renderer.assignment(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(RENDERED);
        service = new AssignmentNotificationService(renderer, scheduler);
        alice = user(1L, "alice", "Alice", "Smith", true);
        bob = user(2L, "bob", "Bob", "Jones", true);
    }

    @Test
    void accountAssignedToAnotherUserQueuesTheRailsUserMailerAction() {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", 7L);
        account.setName("Acme");
        account.setAssignedTo(bob);

        service.afterCreate(account, alice);

        verify(renderer).assignment("bob@example.test", "Acme", "Account",
            "http://crm.example.test/accounts/7", "Alice Smith");
        verify(scheduler).deliverLater("UserMailer", "assigned_entity_notification", RENDERED);
    }

    @Test
    void contactAndLeadUseFirstAndLastNameLikeRailsFullName() {
        Contact contact = new Contact();
        ReflectionTestUtils.setField(contact, "id", 8L);
        contact.setFirstName("Jane");
        contact.setLastName("Doe");
        contact.setAssignedTo(bob);
        Lead lead = new Lead();
        ReflectionTestUtils.setField(lead, "id", 9L);
        lead.setFirstName("John");
        lead.setLastName("Roe");
        lead.setAssignedTo(bob);

        service.afterCreate(contact, alice);
        service.afterCreate(lead, alice);

        verify(renderer).assignment("bob@example.test", "Jane Doe", "Contact",
            "http://crm.example.test/contacts/8", "Alice Smith");
        verify(renderer).assignment("bob@example.test", "John Roe", "Lead",
            "http://crm.example.test/leads/9", "Alice Smith");
    }

    @Test
    void hostWithoutTrailingSlashProducesTheSameUrl() {
        when(renderer.host()).thenReturn("http://crm.example.test");
        service.afterCreate(account(7L, bob), alice);
        verify(renderer).assignment("bob@example.test", "Acme", "Account",
            "http://crm.example.test/accounts/7", "Alice Smith");
    }

    @Test
    void selfAssignmentSendsNothing() {
        service.afterCreate(account(7L, alice), alice);
        verifyNoInteractions(scheduler);
        verify(renderer, never()).assignment(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void unassignedEntitySendsNothing() {
        service.afterCreate(account(7L, null), alice);
        verifyNoInteractions(scheduler);
    }

    @Test
    void assigneeWhoOptedOutSendsNothing() {
        User optedOut = user(3L, "carol", "Carol", "King", false);
        service.afterCreate(account(7L, optedOut), alice);
        verifyNoInteractions(scheduler);
    }

    @Test
    void assigneeWithoutEmailSendsNothing() {
        User noEmail = user(4L, "dave", "Dave", "Lee", true);
        noEmail.setEmail(null);
        service.afterCreate(account(7L, noEmail), alice);
        verifyNoInteractions(scheduler);
    }

    @Test
    void missingHostSettingSendsNothingLikeRailsCanSendEmail() {
        when(renderer.host()).thenReturn("");
        service.afterCreate(account(7L, bob), alice);
        verifyNoInteractions(scheduler);
        verify(renderer, never()).assignment(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void unknownCurrentUserSendsNothing() {
        service.afterCreate(account(7L, bob), null);
        verifyNoInteractions(scheduler);
    }

    @Test
    void assignerNameTrimsMissingNameParts() {
        User firstOnly = user(5L, "erin", "Erin", null, true);
        User nameless = user(6L, "frank", null, null, true);

        service.afterCreate(account(7L, bob), firstOnly);
        service.afterCreate(account(7L, bob), nameless);

        verify(renderer).assignment("bob@example.test", "Acme", "Account", "http://crm.example.test/accounts/7",
            "Erin");
        verify(renderer).assignment("bob@example.test", "Acme", "Account", "http://crm.example.test/accounts/7",
            "");
    }

    private static CrmEntity account(long id, User assignee) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", id);
        account.setName("Acme");
        account.setAssignedTo(assignee);
        return account;
    }

    private static User user(long id, String username, String firstName, String lastName, boolean notify) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setReceiveAssignedNotifications(notify);
        return user;
    }
}
