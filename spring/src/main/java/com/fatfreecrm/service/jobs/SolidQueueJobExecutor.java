package com.fatfreecrm.service.jobs;

import com.fatfreecrm.service.mail.MailDeliveryService;
import org.springframework.stereotype.Service;

@Service
public class SolidQueueJobExecutor {

    private final ActiveJobArgumentParser parser;
    private final MailDeliveryService mailDeliveryService;
    private final AccountWebsiteJob accountWebsiteJob;
    private final WikidataJob wikidataJob;

    public SolidQueueJobExecutor(
        ActiveJobArgumentParser parser,
        MailDeliveryService mailDeliveryService,
        AccountWebsiteJob accountWebsiteJob,
        WikidataJob wikidataJob
    ) {
        this.parser = parser;
        this.mailDeliveryService = mailDeliveryService;
        this.accountWebsiteJob = accountWebsiteJob;
        this.wikidataJob = wikidataJob;
    }

    public void execute(String className, String arguments) throws Exception {
        switch (className) {
            case "ActionMailer::MailDeliveryJob" -> mailDeliveryService.deliverActiveJob(arguments);
            case "AccountWebsiteJob" -> accountWebsiteJob.perform(parser.accountId(arguments));
            case "WikidataJob" -> wikidataJob.perform(parser.accountId(arguments));
            default -> throw new IllegalArgumentException("Unsupported Solid Queue class " + className);
        }
    }
}
