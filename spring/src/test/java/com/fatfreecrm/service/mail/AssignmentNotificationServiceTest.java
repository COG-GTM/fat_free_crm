package com.fatfreecrm.service.mail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins {@link AssignmentNotificationService} to Rails' {@code EntityObserver}
 * (app/models/observers/entity_observer.rb): mail is sent only when an entity gains a new assignee
 * who is not the current user, the assignee opted in, and {@code Setting.host} is configured.
 */
class AssignmentNotificationServiceTest {

    private final MailSettingsService settings = mock(MailSettingsService.class);
    private final RailsJobEnqueuer enqueuer = mock(RailsJobEnqueuer.class);
    private final AssignmentNotificationService service = new AssignmentNotificationService(settings, enqueuer);

    private final User assignee = user(7L, true);
    private final User currentUser = user(3L, true);

    @BeforeEach
    void configuredHost() {
        when(settings.host()).thenReturn("crm.example.com");
    }

    @Test
    void afterCreateEnqueuesUserMailerForEachObservedEntityType() {
        for (CrmEntity entity : List.of(new Account(), new Contact(), new Lead(), new Opportunity())) {
            entity.setAssignedTo(assignee);
            service.afterCreate(entity, currentUser);
            verify(enqueuer).deliverLater("UserMailer", "assigned_entity_notification", entity, currentUser);
        }
    }

    @Test
    void afterCreateIgnoresCampaignsLikeTheRailsObserverList() {
        Campaign campaign = new Campaign();
        campaign.setAssignedTo(assignee);
        service.afterCreate(campaign, currentUser);
        verifyNoInteractions(enqueuer);
    }

    @Test
    void afterCreateWithoutAssigneeDoesNothing() {
        service.afterCreate(new Account(), currentUser);
        verifyNoInteractions(enqueuer);
        verifyNoInteractions(settings);
    }

    @Test
    void afterCreateSelfAssignmentDoesNotMail() {
        Account account = new Account();
        account.setAssignedTo(assignee);
        service.afterCreate(account, assignee);
        verifyNoInteractions(enqueuer);
    }

    @Test
    void afterCreateWithoutCurrentUserDoesNotMail() {
        Account account = new Account();
        account.setAssignedTo(assignee);
        service.afterCreate(account, null);
        verifyNoInteractions(enqueuer);
    }

    @Test
    void afterCreateRespectsAssigneeOptOut() {
        Account account = new Account();
        account.setAssignedTo(user(8L, false));
        service.afterCreate(account, currentUser);
        verifyNoInteractions(enqueuer);
    }

    @Test
    void afterCreateWithoutConfiguredHostDoesNotMail() {
        when(settings.host()).thenReturn("");
        Account account = new Account();
        account.setAssignedTo(assignee);
        service.afterCreate(account, currentUser);
        verifyNoInteractions(enqueuer);
    }

    @Test
    void afterUpdateWithUnchangedAssigneeDoesNotMail() {
        Account account = new Account();
        account.setAssignedTo(assignee);
        service.afterUpdate(account, 7L, currentUser);
        verifyNoInteractions(enqueuer);
    }

    @Test
    void afterUpdateMailsWhenAssigneeChanges() {
        Account account = new Account();
        account.setAssignedTo(assignee);
        service.afterUpdate(account, 5L, currentUser);
        verify(enqueuer).deliverLater("UserMailer", "assigned_entity_notification", account, currentUser);
    }

    @Test
    void afterUpdateMailsWhenPreviouslyUnassigned() {
        Lead lead = new Lead();
        lead.setAssignedTo(assignee);
        service.afterUpdate(lead, null, currentUser);
        verify(enqueuer).deliverLater("UserMailer", "assigned_entity_notification", lead, currentUser);
    }

    @Test
    void afterUpdateUnassigningDoesNotMail() {
        Account account = new Account();
        service.afterUpdate(account, 7L, currentUser);
        verify(enqueuer, never()).deliverLater(anyString(), anyString(), any());
    }

    @Test
    void afterUpdateReassignmentToSelfDoesNotMail() {
        Account account = new Account();
        account.setAssignedTo(assignee);
        service.afterUpdate(account, 5L, assignee);
        verifyNoInteractions(enqueuer);
    }

    private static User user(long id, boolean receiveAssignedNotifications) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setReceiveAssignedNotifications(receiveAssignedNotifications);
        return user;
    }
}
