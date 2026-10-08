package com.fatfreecrm.service.mail;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

public class MailDeliveryQuartzJob implements Job {

    @Autowired
    private MailDeliveryService mailDeliveryService;

    @Override
    public void execute(JobExecutionContext context) {
        var data = context.getMergedJobDataMap();
        mailDeliveryService.deliver(new RenderedMail(
            data.getString("to"),
            data.getString("from"),
            data.getString("subject"),
            data.getString("textBody"),
            data.getString("htmlBody")));
    }
}
