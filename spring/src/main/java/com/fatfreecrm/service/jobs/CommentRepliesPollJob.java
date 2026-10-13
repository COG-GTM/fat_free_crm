package com.fatfreecrm.service.jobs;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

@DisallowConcurrentExecution
public class CommentRepliesPollJob implements Job {

    @Autowired
    private JobsOwner jobsOwner;
    @Autowired
    private JobLockService lockService;
    @Autowired
    private MailProcessorRunner processorRunner;

    @Override
    public void execute(JobExecutionContext context) {
        if (jobsOwner.isSpring()) {
            lockService.runExclusively(JobLockKey.COMMENT_REPLIES_POLL, processorRunner::runCommentReplies);
        }
    }
}
