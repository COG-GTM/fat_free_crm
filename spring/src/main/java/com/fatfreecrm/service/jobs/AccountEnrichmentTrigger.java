package com.fatfreecrm.service.jobs;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fatfreecrm.domain.Account;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "The Spring-managed enqueuer is intentionally retained by this service."
)
public class AccountEnrichmentTrigger {

    private final RailsJobEnqueuer enqueuer;

    public AccountEnrichmentTrigger(RailsJobEnqueuer enqueuer) {
        this.enqueuer = enqueuer;
    }

    @Transactional
    public void afterAccountSaved(Account account, String previousWebsite, String previousWikidataId) {
        boolean websiteChanged = !Objects.equals(previousWebsite, account.getWebsite());
        boolean wikidataIdChanged = !Objects.equals(previousWikidataId, account.getWikidataId());
        if (websiteChanged && present(account.getWebsite())) {
            enqueuer.performLater("AccountWebsiteJob", account);
        } else if (!websiteChanged && wikidataIdChanged && present(account.getWikidataId())) {
            enqueuer.performLater("WikidataJob", account);
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
