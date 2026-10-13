package com.fatfreecrm.service.mail;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Campaign;
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
 * Parity with Rails {@code EntityObserver} ({@code observe :account, :contact, :lead, :opportunity}):
 * {@code after_create} notifies when the assignee is not the current user; {@code after_update} only when
 * {@code assigned_to} changed to someone else; and the mail is enqueued only when the assignee opted in
 * ({@code receive_assigned_notifications}), {@code Setting.host} is set and a current user is known.
 */
class AssignmentNotificationServiceTest {

    private MailSettingsService settings;
    private RailsJobEnqueuer enqueuer;
    private AssignmentNotificationService service;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        settings = mock(MailSettingsService.class);
        enqueuer = mock(RailsJobEnqueuer.class);
        when(settings.host()).thenReturn("http://crm.example.test");
        service = new AssignmentNotificationService(settings, enqueuer);
        alice = user(1L, true);
        bob = user(2L, true);
    }

    @Test
    void createAssignedToAnotherUserEnqueuesTheRailsUserMailerAction() {
        CrmEntity account = entity(new Account(), 7L, bob);

        service.afterCreate(account, alice);

        verify(enqueuer).deliverLater("UserMailer", "assigned_entity_notification", account, alice);
    }

    @Test
    void everyObservedEntityTypeIsNotifiedAndOthersAreNot() {
        CrmEntity contact = entity(new Contact(), 8L, bob);
        CrmEntity lead = entity(new Lead(), 9L, bob);
        CrmEntity opportunity = entity(new Opportunity(), 10L, bob);
        CrmEntity campaign = entity(new Campaign(), 11L, bob);

        service.afterCreate(contact, alice);
        service.afterCreate(lead, alice);
        service.afterCreate(opportunity, alice);
        service.afterCreate(campaign, alice);

        verify(enqueuer).deliverLater("UserMailer", "assigned_entity_notification", contact, alice);
        verify(enqueuer).deliverLater("UserMailer", "assigned_entity_notification", lead, alice);
        verify(enqueuer).deliverLater("UserMailer", "assigned_entity_notification", opportunity, alice);
        verify(enqueuer, org.mockito.Mockito.never())
            .deliverLater("UserMailer", "assigned_entity_notification", campaign, alice);
    }

    @Test
    void selfAssignmentOnCreateSendsNothing() {
        service.afterCreate(entity(new Account(), 7L, alice), alice);
        verifyNoInteractions(enqueuer);
    }

    @Test
    void unassignedEntitySendsNothing() {
        service.afterCreate(entity(new Account(), 7L, null), alice);
        service.afterUpdate(entity(new Account(), 7L, null), 2L, alice);
        verifyNoInteractions(enqueuer);
    }

    @Test
    void assigneeWhoOptedOutSendsNothing() {
        service.afterCreate(entity(new Account(), 7L, user(3L, false)), alice);
        verifyNoInteractions(enqueuer);
    }

    @Test
    void missingHostSettingSendsNothingLikeRailsCanSendEmail() {
        when(settings.host()).thenReturn("");
        service.afterCreate(entity(new Account(), 7L, bob), alice);
        when(settings.host()).thenReturn("   ");
        service.afterCreate(entity(new Account(), 7L, bob), alice);
        verifyNoInteractions(enqueuer);
    }

    @Test
    void unknownCurrentUserSendsNothing() {
        service.afterCreate(entity(new Account(), 7L, bob), null);
        service.afterUpdate(entity(new Account(), 7L, bob), 1L, null);
        verifyNoInteractions(enqueuer);
    }

    @Test
    void reassignmentToAnotherUserEnqueuesOnUpdate() {
        CrmEntity account = entity(new Account(), 7L, bob);

        service.afterUpdate(account, 1L, alice);
        service.afterUpdate(account, null, alice);

        verify(enqueuer, org.mockito.Mockito.times(2))
            .deliverLater("UserMailer", "assigned_entity_notification", account, alice);
    }

    @Test
    void updateWithoutAssigneeChangeOrToSelfSendsNothing() {
        service.afterUpdate(entity(new Account(), 7L, bob), 2L, alice);
        service.afterUpdate(entity(new Account(), 7L, alice), 2L, alice);
        verifyNoInteractions(enqueuer);
    }

    private static CrmEntity entity(CrmEntity entity, long id, User assignee) {
        ReflectionTestUtils.setField(entity, "id", id);
        entity.setAssignedTo(assignee);
        return entity;
    }

    private static User user(long id, boolean notify) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setUsername("user" + id);
        user.setEmail("user" + id + "@example.test");
        user.setReceiveAssignedNotifications(notify);
        return user;
    }
}
