package com.fatfreecrm.service.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fatfreecrm.config.JobsProperties;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.repository.AccountRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Rails {@code Account after_commit} enqueues {@code AccountWebsiteJob} when the website changed and
 * {@code WikidataJob} when the Wikidata id changed; Spring schedules one-off Quartz jobs instead, but
 * only when it owns jobs.
 */
class AccountEnrichmentTriggerTest {

    @Test
    void railsOwnerNeitherLoadsTheAccountNorSchedules() {
        AccountRepository accounts = mock(AccountRepository.class);
        Scheduler scheduler = mock(Scheduler.class);

        trigger(accounts, scheduler, "rails").afterAccountSaved(51L, true, true);

        verifyNoInteractions(accounts, scheduler);
    }

    @Test
    void missingAccountSchedulesNothing() {
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findById(51L)).thenReturn(Optional.empty());
        Scheduler scheduler = mock(Scheduler.class);

        trigger(accounts, scheduler, "spring").afterAccountSaved(51L, true, true);

        verifyNoInteractions(scheduler);
    }

    @Test
    void websiteChangeSchedulesAOneOffWebsiteJobCarryingTheAccountId() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        trigger(account(51L, "https://example.test", "Q42"), scheduler, "spring")
            .afterAccountSaved(51L, true, false);

        ArgumentCaptor<JobDetail> detail = ArgumentCaptor.forClass(JobDetail.class);
        ArgumentCaptor<Trigger> quartzTrigger = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).scheduleJob(detail.capture(), quartzTrigger.capture());
        assertThat(detail.getValue().getJobClass()).isEqualTo(AccountWebsiteJob.class);
        assertThat(detail.getValue().getKey().getGroup()).isEqualTo("ffcrm-one-off");
        assertThat(detail.getValue().getKey().getName()).startsWith("accountwebsitejob-51-");
        assertThat(detail.getValue().getJobDataMap().getLong("accountId")).isEqualTo(51L);
        assertThat(quartzTrigger.getValue()).isInstanceOf(SimpleTrigger.class);
        assertThat(quartzTrigger.getValue().getKey().getGroup()).isEqualTo("ffcrm-one-off");
        assertThat(quartzTrigger.getValue().getKey().getName()).isEqualTo(detail.getValue().getKey().getName()
            + "-trigger");
        assertThat(quartzTrigger.getValue().getMisfireInstruction())
            .isEqualTo(SimpleTrigger.MISFIRE_INSTRUCTION_FIRE_NOW);
    }

    @Test
    void wikidataChangeSchedulesTheWikidataJobOnlyWhenWebsiteDidNotChange() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        trigger(account(52L, "https://example.test", "Q42"), scheduler, "spring")
            .afterAccountSaved(52L, false, true);

        ArgumentCaptor<JobDetail> detail = ArgumentCaptor.forClass(JobDetail.class);
        verify(scheduler).scheduleJob(detail.capture(), any(Trigger.class));
        assertThat(detail.getValue().getJobClass()).isEqualTo(WikidataJob.class);
        assertThat(detail.getValue().getJobDataMap().getLong("accountId")).isEqualTo(52L);

        Scheduler both = mock(Scheduler.class);
        trigger(account(53L, "https://example.test", "Q42"), both, "spring").afterAccountSaved(53L, true, true);
        ArgumentCaptor<JobDetail> single = ArgumentCaptor.forClass(JobDetail.class);
        verify(both).scheduleJob(single.capture(), any(Trigger.class));
        assertThat(single.getValue().getJobClass()).isEqualTo(AccountWebsiteJob.class);
    }

    @Test
    void blankValuesSuppressTheJobs() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        trigger(account(54L, "   ", null), scheduler, "spring").afterAccountSaved(54L, true, true);
        trigger(account(55L, null, ""), scheduler, "spring").afterAccountSaved(55L, true, true);
        trigger(account(56L, "https://example.test", "Q1"), scheduler, "spring").afterAccountSaved(56L, false, false);

        verifyNoInteractions(scheduler);
    }

    @Test
    void websiteChangedDelegatesToAfterAccountSavedForTheWebsiteOnly() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        trigger(account(57L, "https://example.test", "Q42"), scheduler, "spring").websiteChanged(57L);

        ArgumentCaptor<JobDetail> detail = ArgumentCaptor.forClass(JobDetail.class);
        verify(scheduler).scheduleJob(detail.capture(), any(Trigger.class));
        assertThat(detail.getValue().getJobClass()).isEqualTo(AccountWebsiteJob.class);
    }

    @Test
    void schedulerFailuresSurfaceAsIllegalState() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
            .thenThrow(new SchedulerException("down"));

        assertThatThrownBy(() -> trigger(account(58L, "https://example.test", null), scheduler, "spring")
            .websiteChanged(58L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unable to schedule account enrichment");
    }

    private static AccountRepository account(long id, String website, String wikidataId) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", id);
        account.setWebsite(website);
        account.setWikidataId(wikidataId);
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findById(id)).thenReturn(Optional.of(account));
        return accounts;
    }

    private static AccountEnrichmentTrigger trigger(AccountRepository accounts, Scheduler scheduler, String owner) {
        JobsProperties properties = new JobsProperties();
        properties.setOwner(owner);
        return new AccountEnrichmentTrigger(accounts, scheduler, new JobsOwner(properties));
    }
}
