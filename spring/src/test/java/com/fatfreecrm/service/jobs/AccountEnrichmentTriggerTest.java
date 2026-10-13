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

/**
 * Mirrors Rails {@code Account} callbacks: {@code after_save :enqueue_website_job if saved_change_to_website?}
 * and the Wikidata job on {@code wikidata_id} changes — but only when Spring owns background jobs.
 */
class AccountEnrichmentTriggerTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final Scheduler scheduler = mock(Scheduler.class);

    private static JobsOwner owner(String owner) {
        JobsProperties properties = new JobsProperties();
        properties.setOwner(owner);
        return new JobsOwner(properties);
    }

    private static Account account(String website, String wikidataId) {
        Account account = new Account();
        account.setName("Acme");
        account.setWebsite(website);
        account.setWikidataId(wikidataId);
        return account;
    }

    private AccountEnrichmentTrigger trigger(String owner) {
        return new AccountEnrichmentTrigger(accountRepository, scheduler, owner(owner));
    }

    @Test
    void railsOwnerSchedulesNothingAndDoesNotLoadTheAccount() {
        trigger("rails").afterAccountSaved(7L, true, true);

        verifyNoInteractions(accountRepository, scheduler);
    }

    @Test
    void missingAccountSchedulesNothing() {
        when(accountRepository.findById(7L)).thenReturn(Optional.empty());

        trigger("spring").afterAccountSaved(7L, true, true);

        verifyNoInteractions(scheduler);
    }

    @Test
    void websiteChangeSchedulesAOneOffWebsiteJobFiringNow() throws Exception {
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account("https://acme.test", "Q1")));

        trigger("spring").websiteChanged(7L);

        ArgumentCaptor<JobDetail> detail = ArgumentCaptor.forClass(JobDetail.class);
        ArgumentCaptor<Trigger> quartzTrigger = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).scheduleJob(detail.capture(), quartzTrigger.capture());
        assertThat(detail.getValue().getJobClass()).isEqualTo(AccountWebsiteJob.class);
        assertThat(detail.getValue().getKey().getGroup()).isEqualTo("ffcrm-one-off");
        assertThat(detail.getValue().getKey().getName()).startsWith("accountwebsitejob-7-");
        assertThat(detail.getValue().getJobDataMap().getLong("accountId")).isEqualTo(7L);
        assertThat(quartzTrigger.getValue()).isInstanceOf(SimpleTrigger.class);
        assertThat(quartzTrigger.getValue().getMisfireInstruction())
            .isEqualTo(SimpleTrigger.MISFIRE_INSTRUCTION_FIRE_NOW);
        assertThat(quartzTrigger.getValue().getStartTime()).isBeforeOrEqualTo(new java.util.Date());
    }

    @Test
    void wikidataChangeSchedulesTheWikidataJobOnlyWhenWebsiteDidNotChange() throws Exception {
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account("https://acme.test", "Q42")));

        trigger("spring").afterAccountSaved(7L, false, true);

        ArgumentCaptor<JobDetail> detail = ArgumentCaptor.forClass(JobDetail.class);
        verify(scheduler).scheduleJob(detail.capture(), any(Trigger.class));
        assertThat(detail.getValue().getJobClass()).isEqualTo(WikidataJob.class);
        assertThat(detail.getValue().getKey().getName()).startsWith("wikidatajob-7-");
    }

    @Test
    void websiteChangeTakesPrecedenceOverWikidataChange() throws Exception {
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account("https://acme.test", "Q42")));

        trigger("spring").afterAccountSaved(7L, true, true);

        ArgumentCaptor<JobDetail> detail = ArgumentCaptor.forClass(JobDetail.class);
        verify(scheduler).scheduleJob(detail.capture(), any(Trigger.class));
        assertThat(detail.getValue().getJobClass()).isEqualTo(AccountWebsiteJob.class);
    }

    @Test
    void blankValuesSchedulesNothingEvenWhenChanged() {
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account("   ", null)));

        trigger("spring").afterAccountSaved(7L, true, true);

        verifyNoInteractions(scheduler);
    }

    @Test
    void clearedWebsiteWithExistingWikidataDoesNotFallBackToWikidata() {
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account("", "Q42")));

        trigger("spring").afterAccountSaved(7L, true, false);

        verifyNoInteractions(scheduler);
    }

    @Test
    void schedulerFailuresSurfaceAsIllegalState() throws Exception {
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account("https://acme.test", null)));
        when(scheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
            .thenThrow(new SchedulerException("down"));

        assertThatThrownBy(() -> trigger("spring").websiteChanged(7L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Unable to schedule account enrichment")
            .hasCauseInstanceOf(SchedulerException.class);
    }
}
