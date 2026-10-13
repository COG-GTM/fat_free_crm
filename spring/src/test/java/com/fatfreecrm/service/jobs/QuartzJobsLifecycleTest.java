package com.fatfreecrm.service.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

/** The {@code FFCRM_JOBS_OWNER} gate: only the Spring owner registers and starts the recurring Quartz jobs. */
class QuartzJobsLifecycleTest {

    private final Scheduler scheduler = mock(Scheduler.class);

    private static JobsProperties properties(String owner) {
        JobsProperties properties = new JobsProperties();
        properties.setOwner(owner);
        return properties;
    }

    private QuartzJobsLifecycle lifecycle(JobsProperties properties) {
        return new QuartzJobsLifecycle(scheduler, new JobsOwner(properties), properties);
    }

    @Test
    void railsOwnerNeverTouchesTheScheduler() {
        QuartzJobsLifecycle lifecycle = lifecycle(properties("rails"));

        lifecycle.start();

        verifyNoInteractions(scheduler);
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void springOwnerSchedulesDropboxCommentRepliesAndDrainThenStarts() throws Exception {
        JobsProperties properties = properties("spring");
        properties.getDropbox().setCron("0 */7 * * * ?");
        QuartzJobsLifecycle lifecycle = lifecycle(properties);

        lifecycle.start();

        ArgumentCaptor<JobDetail> details = ArgumentCaptor.forClass(JobDetail.class);
        ArgumentCaptor<Trigger> triggers = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler, times(3)).scheduleJob(details.capture(), triggers.capture());
        verify(scheduler).start();
        assertThat(lifecycle.isRunning()).isTrue();

        List<JobDetail> scheduled = details.getAllValues();
        assertThat(scheduled).extracting(detail -> detail.getKey().getName())
            .containsExactly("dropbox-poll", "comment-replies-poll", "solid-queue-drain");
        assertThat(scheduled).extracting(detail -> detail.getKey().getGroup()).containsOnly("ffcrm-scheduled");
        assertThat(scheduled).extracting(JobDetail::getJobClass)
            .containsExactly(DropboxPollJob.class, CommentRepliesPollJob.class, SolidQueueDrainJob.class);

        CronTrigger dropbox = (CronTrigger) triggers.getAllValues().get(0);
        assertThat(dropbox.getCronExpression()).isEqualTo("0 */7 * * * ?");
        assertThat(dropbox.getKey().getName()).isEqualTo("dropbox-poll-trigger");
        assertThat(dropbox.getMisfireInstruction()).isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
        assertThat(((CronTrigger) triggers.getAllValues().get(2)).getCronExpression())
            .isEqualTo(properties.getSolidQueueDrain().getCron());
    }

    @Test
    void disabledSolidQueueDrainIsNotScheduled() throws Exception {
        JobsProperties properties = properties("spring");
        properties.getSolidQueueDrain().setEnabled(false);

        lifecycle(properties).start();

        ArgumentCaptor<JobDetail> details = ArgumentCaptor.forClass(JobDetail.class);
        verify(scheduler, times(2)).scheduleJob(details.capture(), any(Trigger.class));
        assertThat(details.getAllValues()).extracting(detail -> detail.getKey().getName())
            .containsExactly("dropbox-poll", "comment-replies-poll");
        verify(scheduler).start();
    }

    @Test
    void stopShutsDownOnlyAStartedScheduler() throws Exception {
        QuartzJobsLifecycle idle = lifecycle(properties("rails"));
        idle.stop();
        verify(scheduler, never()).shutdown(true);

        QuartzJobsLifecycle running = lifecycle(properties("spring"));
        running.start();
        running.stop();

        verify(scheduler).shutdown(true);
        assertThat(running.isRunning()).isFalse();
    }

    @Test
    void schedulerFailuresAbortStartup() throws Exception {
        when(scheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
            .thenThrow(new SchedulerException("boom"));
        QuartzJobsLifecycle lifecycle = lifecycle(properties("spring"));

        assertThatThrownBy(lifecycle::start)
            .isInstanceOf(IllegalStateException.class)
            .hasCauseInstanceOf(SchedulerException.class);
        verify(scheduler, never()).start();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void stopFailuresAreWrappedAndClearTheRunningFlag() throws Exception {
        doThrow(new SchedulerException("stuck")).when(scheduler).shutdown(true);
        QuartzJobsLifecycle lifecycle = lifecycle(properties("spring"));
        lifecycle.start();

        assertThatThrownBy(lifecycle::stop).isInstanceOf(IllegalStateException.class);
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void startsAutomaticallyLateInTheContextLifecycle() {
        QuartzJobsLifecycle lifecycle = lifecycle(properties("rails"));
        assertThat(lifecycle.isAutoStartup()).isTrue();
        assertThat(lifecycle.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);
    }
}
