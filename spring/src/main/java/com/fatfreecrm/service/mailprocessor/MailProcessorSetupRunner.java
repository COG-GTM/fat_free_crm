package com.fatfreecrm.service.mailprocessor;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fatfreecrm.service.jobs.JobsOwner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ffcrm.jobs", name = "setup-mail-folders", havingValue = "true")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed processor is intentionally retained by this runner."
)
public class MailProcessorSetupRunner implements ApplicationRunner {

    private final JobsOwner jobsOwner;
    private final MailProcessorService mailProcessorService;

    public MailProcessorSetupRunner(JobsOwner jobsOwner, MailProcessorService mailProcessorService) {
        this.jobsOwner = jobsOwner;
        this.mailProcessorService = mailProcessorService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!jobsOwner.isSpring()) {
            return;
        }
        mailProcessorService.setup("email_dropbox");
        mailProcessorService.setup("email_comment_replies");
    }
}
