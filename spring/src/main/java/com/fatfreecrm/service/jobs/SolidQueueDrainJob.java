package com.fatfreecrm.service.jobs;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

@DisallowConcurrentExecution
public class SolidQueueDrainJob implements Job {

    @Autowired
    private JobsOwner jobsOwner;
    @Autowired
    private JobLockService lockService;
    @Autowired
    private SolidQueueDrainService drainService;

    @Override
    public void execute(JobExecutionContext context) {
        if (jobsOwner.isSpring()) {
            lockService.runExclusively(JobLockKey.SOLID_QUEUE_DRAIN, drainService::drain);
        }
    }
}
