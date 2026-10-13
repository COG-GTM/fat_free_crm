package com.fatfreecrm.service.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fatfreecrm.domain.Account;
import org.junit.jupiter.api.Test;

/**
 * Mirrors Rails {@code Account}:
 * {@code after_save_commit :enqueue_website_job, if: website.present? && saved_change_to_website?} and
 * {@code after_save_commit :enqueue_wikidata_job, if: wikidata_id.present? && saved_change_to_wikidata_id?
 * && !saved_change_to_website?}, both via {@code perform_later(self)}.
 */
class AccountEnrichmentTriggerTest {

    private final RailsJobEnqueuer enqueuer = mock(RailsJobEnqueuer.class);
    private final AccountEnrichmentTrigger trigger = new AccountEnrichmentTrigger(enqueuer);

    private static Account account(String website, String wikidataId) {
        Account account = new Account();
        account.setName("Acme");
        account.setWebsite(website);
        account.setWikidataId(wikidataId);
        return account;
    }

    @Test
    void createWithWebsiteEnqueuesTheWebsiteJob() {
        Account account = account("https://acme.test", null);

        trigger.afterAccountSaved(account, null, null);

        verify(enqueuer).performLater("AccountWebsiteJob", account);
        verifyNoMoreEnqueues();
    }

    @Test
    void createWithOnlyWikidataIdEnqueuesTheWikidataJob() {
        Account account = account(null, "Q42");

        trigger.afterAccountSaved(account, null, null);

        verify(enqueuer).performLater("WikidataJob", account);
        verifyNoMoreEnqueues();
    }

    @Test
    void unchangedValuesEnqueueNothing() {
        trigger.afterAccountSaved(account("https://acme.test", "Q42"), "https://acme.test", "Q42");
        trigger.afterAccountSaved(account(null, null), null, null);

        verifyNoInteractions(enqueuer);
    }

    @Test
    void websiteChangeSuppressesTheWikidataJobEvenWhenWikidataIdAlsoChanged() {
        Account account = account("https://acme.test", "Q42");

        trigger.afterAccountSaved(account, "https://old.test", "Q1");

        verify(enqueuer).performLater("AccountWebsiteJob", account);
        verifyNoMoreEnqueues();
    }

    @Test
    void wikidataChangeAloneEnqueuesTheWikidataJob() {
        Account account = account("https://acme.test", "Q42");

        trigger.afterAccountSaved(account, "https://acme.test", null);

        verify(enqueuer).performLater("WikidataJob", account);
        verifyNoMoreEnqueues();
    }

    @Test
    void blankWebsiteChangeEnqueuesNothingAndBlocksTheWikidataFallback() {
        trigger.afterAccountSaved(account("   ", "Q42"), "https://acme.test", "Q1");
        trigger.afterAccountSaved(account("", "Q42"), "https://acme.test", null);
        trigger.afterAccountSaved(account(null, null), "https://acme.test", "Q1");

        verifyNoInteractions(enqueuer);
    }

    @Test
    void blankWikidataIdChangeEnqueuesNothing() {
        trigger.afterAccountSaved(account("https://acme.test", "  "), "https://acme.test", "Q1");
        trigger.afterAccountSaved(account("https://acme.test", null), "https://acme.test", "Q1");

        verifyNoInteractions(enqueuer);
    }

    private void verifyNoMoreEnqueues() {
        org.mockito.Mockito.verifyNoMoreInteractions(enqueuer);
    }
}
