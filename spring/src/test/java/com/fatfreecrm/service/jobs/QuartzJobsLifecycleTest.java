package com.fatfreecrm.service.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fatfreecrm.config.JobsProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;

/** The single owner switch: only a Spring-owned deployment registers and starts the polling schedules. */
class QuartzJobsLifecycleTest {

    @Test
    void railsOwnerNeverTouchesTheScheduler() {
        Scheduler scheduler = mock(Scheduler.class);
        QuartzJobsLifecycle lifecycle = lifecycle(scheduler, new JobsProperties());

        lifecycle.start();
        lifecycle.stop();

        verifyNoInteractions(scheduler);
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void springOwnerSchedulesPollersAndDrainWithConfiguredCronsThenStarts() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        JobsProperties properties = springProperties();
        properties.getDropbox().setCron("0 */7 * * * ?");
        properties.getCommentReplies().setCron("0 */3 * * * ?");
        properties.getSolidQueueDrain().setCron("*/20 * * * * ?");
        QuartzJobsLifecycle lifecycle = lifecycle(scheduler, properties);

        lifecycle.start();

        ArgumentCaptor<JobDetail> details = ArgumentCaptor.forClass(JobDetail.class);
        ArgumentCaptor<Trigger> triggers = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler, org.mockito.Mockito.times(3)).scheduleJob(details.capture(), triggers.capture());
        verify(scheduler).start();
        assertThat(details.getAllValues()).extracting(detail -> detail.getKey().getName())
            .containsExactly("dropbox-poll", "comment-replies-poll", "solid-queue-drain");
        assertThat(details.getAllValues()).extracting(detail -> detail.getKey().getGroup())
            .containsOnly("ffcrm-scheduled");
        assertThat(details.getAllValues()).<Class<?>>extracting(JobDetail::getJobClass)
            .containsExactly(DropboxPollJob.class, CommentRepliesPollJob.class, SolidQueueDrainJob.class);
        assertThat(triggers.getAllValues()).allSatisfy(trigger -> assertThat(trigger).isInstanceOf(CronTrigger.class));
        assertThat(triggers.getAllValues()).extracting(trigger -> ((CronTrigger) trigger).getCronExpression())
            .containsExactly("0 */7 * * * ?", "0 */3 * * * ?", "*/20 * * * * ?");
        assertThat(triggers.getAllValues()).extracting(trigger -> trigger.getKey().getName())
            .containsExactly("dropbox-poll-trigger", "comment-replies-poll-trigger", "solid-queue-drain-trigger");
        assertThat(triggers.getAllValues()).extracting(Trigger::getMisfireInstruction)
            .containsOnly(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
        assertThat(lifecycle.isRunning()).isTrue();
    }

    @Test
    void disabledDrainSkipsOnlyTheDrainSchedule() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        JobsProperties properties = springProperties();
        properties.getSolidQueueDrain().setEnabled(false);

        lifecycle(scheduler, properties).start();

        ArgumentCaptor<JobDetail> details = ArgumentCaptor.forClass(JobDetail.class);
        verify(scheduler, org.mockito.Mockito.times(2)).scheduleJob(details.capture(), any(Trigger.class));
        assertThat(details.getAllValues()).extracting(detail -> detail.getKey().getName())
            .containsExactly("dropbox-poll", "comment-replies-poll");
        verify(scheduler).start();
    }

    @Test
    void stopShutsDownWaitingForRunningJobsOnlyWhenStarted() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        QuartzJobsLifecycle lifecycle = lifecycle(scheduler, springProperties());
        lifecycle.start();

        lifecycle.stop();

        verify(scheduler).shutdown(true);
        assertThat(lifecycle.isRunning()).isFalse();

        lifecycle.stop();
        verify(scheduler, org.mockito.Mockito.times(1)).shutdown(true);
    }

    @Test
    void schedulerFailuresSurfaceAsIllegalState() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
            .thenThrow(new SchedulerException("boom"));
        QuartzJobsLifecycle lifecycle = lifecycle(scheduler, springProperties());

        assertThatThrownBy(lifecycle::start)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unable to start Spring jobs scheduler")
            .hasCauseInstanceOf(SchedulerException.class);
        assertThat(lifecycle.isRunning()).isFalse();
        verify(scheduler, never()).start();

        Scheduler failingStop = mock(Scheduler.class);
        doThrow(new SchedulerException("stop")).when(failingStop).shutdown(true);
        QuartzJobsLifecycle started = lifecycle(failingStop, springProperties());
        started.start();
        assertThatThrownBy(started::stop)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unable to stop Spring jobs scheduler");
        assertThat(started.isRunning()).isFalse();
    }

    @Test
    void invalidCronFailsFastInsteadOfStartingHalfConfigured() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        JobsProperties properties = springProperties();
        properties.getDropbox().setCron("not a cron");

        assertThatThrownBy(lifecycle(scheduler, properties)::start).isInstanceOf(IllegalStateException.class);
        verify(scheduler, never()).start();
    }

    @Test
    void startsAutomaticallyLateInTheLifecycle() {
        QuartzJobsLifecycle lifecycle = lifecycle(mock(Scheduler.class), new JobsProperties());
        assertThat(lifecycle.isAutoStartup()).isTrue();
        assertThat(lifecycle.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);
        assertThat(List.of(lifecycle.getPhase())).allMatch(phase -> phase < Integer.MAX_VALUE);
    }

    private static JobsProperties springProperties() {
        JobsProperties properties = new JobsProperties();
        properties.setOwner("spring");
        return properties;
    }

    private static QuartzJobsLifecycle lifecycle(Scheduler scheduler, JobsProperties properties) {
        return new QuartzJobsLifecycle(scheduler, new JobsOwner(properties), properties);
    }
}
