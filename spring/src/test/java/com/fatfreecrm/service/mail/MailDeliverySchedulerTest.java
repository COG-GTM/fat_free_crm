package com.fatfreecrm.service.mail;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fatfreecrm.config.JobsProperties;
import com.fatfreecrm.service.jobs.JobsOwner;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;

class MailDeliverySchedulerTest {

    @Test
    void doesNotScheduleMailWhenRailsOwnsJobs() {
        Scheduler scheduler = mock(Scheduler.class);
        MailDeliveryScheduler deliveryScheduler = new MailDeliveryScheduler(
            scheduler,
            new JobsOwner(new JobsProperties()));

        deliveryScheduler.deliverLater(
            "UserMailer",
            "assigned_entity_notification",
            new RenderedMail("subject", "from@example.test", "to@example.test", "text/html", "html"));

        verifyNoInteractions(scheduler);
    }
}
