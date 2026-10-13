package com.fatfreecrm.service.mail;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fatfreecrm.service.jobs.RailsJobEnqueuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "The Spring-managed enqueuer is intentionally retained by this service."
)
public class MailDeliveryScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailDeliveryScheduler.class);

    private final RailsJobEnqueuer enqueuer;

    public MailDeliveryScheduler(RailsJobEnqueuer enqueuer) {
        this.enqueuer = enqueuer;
    }

    public void deliverLater(String mailer, String action, Object... globalIdArgs) {
        LOGGER.debug("Enqueueing {}#{} in Rails Solid Queue", mailer, action);
        enqueuer.deliverLater(mailer, action, globalIdArgs);
    }
}
