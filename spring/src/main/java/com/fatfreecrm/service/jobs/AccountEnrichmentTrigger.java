package com.fatfreecrm.service.jobs;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.repository.AccountRepository;
import java.util.Date;
import java.util.UUID;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TriggerBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed scheduler is intentionally retained by this service."
)
public class AccountEnrichmentTrigger {

    private final AccountRepository accountRepository;
    private final Scheduler scheduler;
    private final JobsOwner jobsOwner;

    public AccountEnrichmentTrigger(AccountRepository accountRepository, Scheduler scheduler, JobsOwner jobsOwner) {
        this.accountRepository = accountRepository;
        this.scheduler = scheduler;
        this.jobsOwner = jobsOwner;
    }

    @Transactional(readOnly = true)
    public void afterAccountSaved(long accountId, boolean websiteChanged, boolean wikidataIdChanged) {
        if (!jobsOwner.isSpring()) {
            return;
        }
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return;
        }
        if (websiteChanged && present(account.getWebsite())) {
            schedule(AccountWebsiteJob.class, accountId);
        } else if (wikidataIdChanged && present(account.getWikidataId())) {
            schedule(WikidataJob.class, accountId);
        }
    }

    public void websiteChanged(long accountId) {
        afterAccountSaved(accountId, true, false);
    }

    private void schedule(Class<? extends org.quartz.Job> jobType, long accountId) {
        JobDataMap data = new JobDataMap();
        data.put("accountId", accountId);
        String key = jobType.getSimpleName().toLowerCase() + "-" + accountId + "-" + UUID.randomUUID();
        var detail = JobBuilder.newJob(jobType).withIdentity(key, "ffcrm-one-off").usingJobData(data).build();
        var trigger = TriggerBuilder.newTrigger()
            .withIdentity(key + "-trigger", "ffcrm-one-off")
            .startAt(new Date())
            .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
            .build();
        try {
            scheduler.scheduleJob(detail, trigger);
        } catch (SchedulerException exception) {
            throw new IllegalStateException("Unable to schedule account enrichment", exception);
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
