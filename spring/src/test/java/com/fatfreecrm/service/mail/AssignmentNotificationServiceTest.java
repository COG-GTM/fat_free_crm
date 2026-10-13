package com.fatfreecrm.service.mail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.CrmEntity;
import com.fatfreecrm.service.jobs.RailsJobEnqueuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Parity with Rails {@code EntityObserver}: {@code after_create} notifies when the assignee is not the
 * current user; {@code after_update} only when {@code assigned_to} changed; both enqueue
 * {@code UserMailer.assigned_entity_notification(item, current_user).deliver_later} and require an
 * assignee who opted in, a present current user and {@code Setting.host}.
 */
class AssignmentNotificationServiceTest {

    private final MailSettingsService settings = mock(MailSettingsService.class);
    private final RailsJobEnqueuer enqueuer = mock(RailsJobEnqueuer.class);
    private final AssignmentNotificationService service = new AssignmentNotificationService(settings, enqueuer);

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = user(1L, "alice@example.test");
        bob = user(2L, "bob@example.test");
        when(settings.host()).thenReturn("https://crm.example.test");
    }

    private static User user(long id, String email) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail(email);
        user.setReceiveAssignedNotifications(true);
        return user;
    }

    private static <T extends CrmEntity> T withId(T entity, long id, User assignee) {
        ReflectionTestUtils.setField(entity, "id", id);
        entity.setAssignedTo(assignee);
        return entity;
    }

    private Account account(User assignee) {
        Account account = new Account();
        account.setName("Acme Corp");
        return withId(account, 42L, assignee);
    }

    @Test
    void afterCreateEnqueuesUserMailerWithTheEntityAndCurrentUserAsGlobalIdArguments() {
        Account account = account(bob);

        service.afterCreate(account, alice);

        verify(enqueuer).deliverLater("UserMailer", "assigned_entity_notification", account, alice);
    }

    @Test
    void everyObservedEntityTypeIsNotifiedIncludingOpportunity() {
        Contact contact = withId(new Contact(), 7L, bob);
        Lead lead = withId(new Lead(), 8L, bob);
        Opportunity opportunity = withId(new Opportunity(), 9L, bob);

        service.afterCreate(contact, alice);
        service.afterCreate(lead, alice);
        service.afterCreate(opportunity, alice);

        verify(enqueuer).deliverLater("UserMailer", "assigned_entity_notification", contact, alice);
        verify(enqueuer).deliverLater("UserMailer", "assigned_entity_notification", lead, alice);
        verify(enqueuer).deliverLater("UserMailer", "assigned_entity_notification", opportunity, alice);
    }

    @Test
    void entitiesOutsideTheObserverListAreIgnored() {
        CrmEntity other = mock(CrmEntity.class);
        when(other.getAssignedTo()).thenReturn(bob);

        service.afterCreate(other, alice);
        service.afterUpdate(other, null, alice);

        verifyNoInteractions(enqueuer);
    }

    @Test
    void noCurrentUserOrNoAssigneeSendsNothing() {
        service.afterCreate(account(bob), null);
        service.afterCreate(account(null), alice);

        verify(enqueuer, never()).deliverLater(anyString(), anyString(), any(), any());
    }

    @Test
    void selfAssignmentSendsNothing() {
        service.afterCreate(account(alice), alice);
        service.afterUpdate(account(alice), 2L, alice);

        verifyNoInteractions(enqueuer);
    }

    @Test
    void assigneeWhoOptedOutSendsNothing() {
        bob.setReceiveAssignedNotifications(false);

        service.afterCreate(account(bob), alice);
        service.afterUpdate(account(bob), null, alice);

        verifyNoInteractions(enqueuer);
    }

    @Test
    void missingHostSettingSendsNothing() {
        when(settings.host()).thenReturn("");
        service.afterCreate(account(bob), alice);
        when(settings.host()).thenReturn("   ");
        service.afterCreate(account(bob), alice);

        verifyNoInteractions(enqueuer);
    }

    @Test
    void assigneeWithoutEmailIsStillEnqueuedLikeRails() {
        bob.setEmail(null);
        Account account = account(bob);

        service.afterCreate(account, alice);

        verify(enqueuer).deliverLater("UserMailer", "assigned_entity_notification", account, alice);
    }

    @Test
    void afterUpdateNotifiesOnlyWhenAssignedToChanged() {
        Account account = account(bob);

        service.afterUpdate(account, 2L, alice);
        verifyNoInteractions(enqueuer);

        service.afterUpdate(account, null, alice);
        service.afterUpdate(account, 3L, alice);
        verify(enqueuer, org.mockito.Mockito.times(2))
            .deliverLater("UserMailer", "assigned_entity_notification", account, alice);
    }

    @Test
    void afterUpdateUnassigningSendsNothing() {
        service.afterUpdate(account(null), 2L, alice);

        verifyNoInteractions(enqueuer);
    }
}
