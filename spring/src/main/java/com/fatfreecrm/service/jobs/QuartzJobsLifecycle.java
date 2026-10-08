package com.fatfreecrm.service.jobs;

import com.fatfreecrm.config.JobsProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed scheduler and configuration are intentionally retained."
)
public class QuartzJobsLifecycle implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuartzJobsLifecycle.class);

    private final Scheduler scheduler;
    private final JobsOwner jobsOwner;
    private final JobsProperties properties;
    private volatile boolean running;

    public QuartzJobsLifecycle(Scheduler scheduler, JobsOwner jobsOwner, JobsProperties properties) {
        this.scheduler = scheduler;
        this.jobsOwner = jobsOwner;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (!jobsOwner.isSpring()) {
            LOGGER.info("jobs owner is rails; scheduler not started");
            return;
        }
        try {
            addSchedule(DropboxPollJob.class, "dropbox-poll", properties.getDropbox().getCron());
            addSchedule(CommentRepliesPollJob.class, "comment-replies-poll", properties.getCommentReplies().getCron());
            if (properties.getSolidQueueDrain().isEnabled()) {
                addSchedule(SolidQueueDrainJob.class, "solid-queue-drain", properties.getSolidQueueDrain().getCron());
            }
            scheduler.start();
            running = true;
            LOGGER.info("Spring jobs scheduler started");
        } catch (SchedulerException | RuntimeException exception) {
            throw new IllegalStateException("Unable to start Spring jobs scheduler", exception);
        }
    }

    private void addSchedule(Class<? extends org.quartz.Job> type, String name, String cron)
        throws SchedulerException {
        JobDetail detail = JobBuilder.newJob(type).withIdentity(name, "ffcrm-scheduled").build();
        Trigger trigger = TriggerBuilder.newTrigger()
            .withIdentity(name + "-trigger", "ffcrm-scheduled")
            .forJob(detail)
            .withSchedule(CronScheduleBuilder.cronSchedule(cron).withMisfireHandlingInstructionDoNothing())
            .build();
        scheduler.scheduleJob(detail, trigger);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        try {
            scheduler.shutdown(true);
        } catch (SchedulerException exception) {
            throw new IllegalStateException("Unable to stop Spring jobs scheduler", exception);
        } finally {
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
