package com.fatfreecrm.service.jobs;

import com.fatfreecrm.config.JobsProperties;
import com.fatfreecrm.service.mailprocessor.MailProcessorService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Service;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are intentionally retained by this runner."
)
public class MailProcessorRunner {

    private final MailProcessorService mailProcessorService;
    private final JobsProperties jobsProperties;

    public MailProcessorRunner(MailProcessorService mailProcessorService, JobsProperties jobsProperties) {
        this.mailProcessorService = mailProcessorService;
        this.jobsProperties = jobsProperties;
    }

    public void runDropbox() {
        mailProcessorService.processDropbox(jobsProperties.getDropbox().isDryRun());
    }

    public void runCommentReplies() {
        mailProcessorService.processCommentReplies(jobsProperties.getCommentReplies().isDryRun());
    }
}
