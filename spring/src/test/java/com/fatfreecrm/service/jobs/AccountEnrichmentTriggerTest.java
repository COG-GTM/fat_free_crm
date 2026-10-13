package com.fatfreecrm.service.jobs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fatfreecrm.domain.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Rails {@code Account} {@code after_save} callbacks enqueue {@code AccountWebsiteJob} when the website
 * changed and {@code WikidataJob} when only the Wikidata id changed; both go through the Rails job queue
 * so whichever app owns jobs drains them.
 */
class AccountEnrichmentTriggerTest {

    private RailsJobEnqueuer enqueuer;
    private AccountEnrichmentTrigger trigger;

    @BeforeEach
    void setUp() {
        enqueuer = mock(RailsJobEnqueuer.class);
        trigger = new AccountEnrichmentTrigger(enqueuer);
    }

    @Test
    void websiteChangeEnqueuesTheWebsiteJob() {
        Account account = account(51L, "https://example.test", "Q42");

        trigger.afterAccountSaved(account, null, "Q42");

        verify(enqueuer).performLater("AccountWebsiteJob", account);
        verify(enqueuer, never()).performLater("WikidataJob", account);
    }

    @Test
    void wikidataChangeAloneEnqueuesTheWikidataJob() {
        Account account = account(52L, "https://example.test", "Q42");

        trigger.afterAccountSaved(account, "https://example.test", "Q1");

        verify(enqueuer).performLater("WikidataJob", account);
        verify(enqueuer, never()).performLater("AccountWebsiteJob", account);
    }

    @Test
    void websiteChangeTakesPrecedenceWhenBothChanged() {
        Account account = account(53L, "https://example.test", "Q42");

        trigger.afterAccountSaved(account, "https://old.test", "Q1");

        verify(enqueuer).performLater("AccountWebsiteJob", account);
        verify(enqueuer, never()).performLater("WikidataJob", account);
    }

    @Test
    void unchangedValuesEnqueueNothing() {
        trigger.afterAccountSaved(account(54L, "https://example.test", "Q42"), "https://example.test", "Q42");
        trigger.afterAccountSaved(account(55L, null, null), null, null);

        verifyNoInteractions(enqueuer);
    }

    @Test
    void blankNewValuesEnqueueNothing() {
        trigger.afterAccountSaved(account(56L, "   ", "Q42"), "https://example.test", "Q42");
        trigger.afterAccountSaved(account(57L, null, "Q42"), "https://example.test", "Q42");
        trigger.afterAccountSaved(account(58L, "https://example.test", ""), "https://example.test", "Q42");
        trigger.afterAccountSaved(account(59L, "https://example.test", null), "https://example.test", "Q42");

        verifyNoInteractions(enqueuer);
    }

    @Test
    void clearingTheWebsiteSuppressesTheWikidataJobEvenWhenTheIdChanged() {
        trigger.afterAccountSaved(account(60L, null, "Q42"), "https://example.test", "Q1");

        verify(enqueuer, never()).performLater(anyString(), any(Account.class));
    }

    private static Account account(long id, String website, String wikidataId) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", id);
        account.setWebsite(website);
        account.setWikidataId(wikidataId);
        return account;
    }
}
