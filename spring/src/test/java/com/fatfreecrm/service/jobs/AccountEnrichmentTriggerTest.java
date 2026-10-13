package com.fatfreecrm.service.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fatfreecrm.domain.Account;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link AccountEnrichmentTrigger} to the Rails {@code after_save} callbacks in
 * app/models/entities/account.rb: a website change enqueues {@code AccountWebsiteJob}, otherwise a
 * Wikidata change enqueues {@code WikidataJob}; blank values and unchanged columns enqueue nothing.
 */
class AccountEnrichmentTriggerTest {

    private final RailsJobEnqueuer enqueuer = mock(RailsJobEnqueuer.class);
    private final AccountEnrichmentTrigger trigger = new AccountEnrichmentTrigger(enqueuer);

    @Test
    void websiteSetOnCreateEnqueuesAccountWebsiteJob() {
        Account account = account("https://example.com", null);
        trigger.afterAccountSaved(account, null, null);
        verify(enqueuer).performLater("AccountWebsiteJob", account);
        verifyNoMoreInteractions(enqueuer);
    }

    @Test
    void websiteChangeEnqueuesAccountWebsiteJob() {
        Account account = account("https://new.example.com", null);
        trigger.afterAccountSaved(account, "https://old.example.com", null);
        verify(enqueuer).performLater("AccountWebsiteJob", account);
        verifyNoMoreInteractions(enqueuer);
    }

    @Test
    void wikidataSetOnCreateEnqueuesWikidataJob() {
        Account account = account(null, "Q42");
        trigger.afterAccountSaved(account, null, null);
        verify(enqueuer).performLater("WikidataJob", account);
        verifyNoMoreInteractions(enqueuer);
    }

    @Test
    void wikidataChangeEnqueuesWikidataJob() {
        Account account = account("https://example.com", "Q42");
        trigger.afterAccountSaved(account, "https://example.com", "Q1");
        verify(enqueuer).performLater("WikidataJob", account);
        verifyNoMoreInteractions(enqueuer);
    }

    @Test
    void websiteChangeWinsOverSimultaneousWikidataChange() {
        Account account = account("https://example.com", "Q42");
        trigger.afterAccountSaved(account, null, null);
        verify(enqueuer).performLater("AccountWebsiteJob", account);
        verifyNoMoreInteractions(enqueuer);
    }

    @Test
    void unchangedColumnsEnqueueNothing() {
        Account account = account("https://example.com", "Q42");
        trigger.afterAccountSaved(account, "https://example.com", "Q42");
        verifyNoInteractions(enqueuer);
    }

    @Test
    void clearingWebsiteEnqueuesNothing() {
        trigger.afterAccountSaved(account("", null), "https://example.com", null);
        trigger.afterAccountSaved(account(null, null), "https://example.com", null);
        trigger.afterAccountSaved(account("   ", null), "https://example.com", null);
        verifyNoInteractions(enqueuer);
    }

    @Test
    void clearingWikidataEnqueuesNothing() {
        trigger.afterAccountSaved(account(null, ""), null, "Q42");
        trigger.afterAccountSaved(account(null, null), null, "Q42");
        verifyNoInteractions(enqueuer);
    }

    @Test
    void anyWebsiteChangeSuppressesWikidataJobEvenWhenWebsiteIsCleared() {
        // Rails: `!saved_change_to_website?` guards the Wikidata callback regardless of the new value.
        trigger.afterAccountSaved(account("", "Q42"), "https://example.com", null);
        verifyNoInteractions(enqueuer);
    }

    @Test
    void blankAccountEnqueuesNothing() {
        trigger.afterAccountSaved(account(null, null), null, null);
        verifyNoInteractions(enqueuer);
    }

    private static Account account(String website, String wikidataId) {
        Account account = new Account();
        account.setName("Acme");
        account.setWebsite(website);
        account.setWikidataId(wikidataId);
        return account;
    }
}
