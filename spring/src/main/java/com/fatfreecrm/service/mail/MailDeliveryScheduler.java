package com.fatfreecrm.service.mail;

import com.fatfreecrm.service.jobs.JobsOwner;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TriggerBuilder;
import org.springframework.stereotype.Service;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "The Spring-managed scheduler is intentionally retained by this service."
)
public class MailDeliveryScheduler {

    private final Scheduler scheduler;
    private final JobsOwner jobsOwner;

    public MailDeliveryScheduler(Scheduler scheduler, JobsOwner jobsOwner) {
        this.scheduler = scheduler;
        this.jobsOwner = jobsOwner;
    }

    public void deliverLater(RenderedMail mail) {
        if (!jobsOwner.isSpring()) {
            return;
        }
        JobDataMap data = new JobDataMap();
        data.put("to", mail.to());
        data.put("from", mail.from());
        data.put("subject", mail.subject());
        data.put("textBody", mail.textBody());
        data.put("htmlBody", mail.htmlBody());
        String key = "mail-" + UUID.randomUUID();
        var detail = JobBuilder.newJob(MailDeliveryQuartzJob.class)
            .withIdentity(key, "ffcrm-one-off")
            .usingJobData(data)
            .build();
        var trigger = TriggerBuilder.newTrigger()
            .withIdentity(key + "-trigger", "ffcrm-one-off")
            .startNow()
            .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
            .build();
        try {
            scheduler.scheduleJob(detail, trigger);
        } catch (SchedulerException exception) {
            throw new IllegalStateException("Unable to schedule mail delivery", exception);
        }
    }
}
