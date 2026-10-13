package com.fatfreecrm.service.mail;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.service.jobs.RailsJobEnqueuer;
import org.junit.jupiter.api.Test;

class MailDeliverySchedulerTest {

    @Test
    void enqueuesMailInTheSharedRailsQueue() {
        RailsJobEnqueuer enqueuer = mock(RailsJobEnqueuer.class);
        MailDeliveryScheduler deliveryScheduler = new MailDeliveryScheduler(enqueuer);
        Account account = mock(Account.class);
        User user = mock(User.class);

        deliveryScheduler.deliverLater(
            "UserMailer", "assigned_entity_notification", account, user);

        verify(enqueuer).deliverLater("UserMailer", "assigned_entity_notification", account, user);
    }
}
