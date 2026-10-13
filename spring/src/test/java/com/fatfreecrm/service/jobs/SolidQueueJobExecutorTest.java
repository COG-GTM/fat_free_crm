package com.fatfreecrm.service.jobs;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fatfreecrm.service.mail.MailDeliveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Dispatch of Rails-queued Solid Queue jobs to their Spring ports. */
class SolidQueueJobExecutorTest {

    private ActiveJobArgumentParser parser;
    private MailDeliveryService mailDeliveryService;
    private AccountWebsiteJob accountWebsiteJob;
    private WikidataJob wikidataJob;
    private SolidQueueJobExecutor executor;

    @BeforeEach
    void setUp() {
        parser = mock(ActiveJobArgumentParser.class);
        mailDeliveryService = mock(MailDeliveryService.class);
        accountWebsiteJob = mock(AccountWebsiteJob.class);
        wikidataJob = mock(WikidataJob.class);
        executor = new SolidQueueJobExecutor(parser, mailDeliveryService, accountWebsiteJob, wikidataJob);
    }

    @Test
    void mailDeliveryJobsAreHandedToTheMailServiceWithRawArguments() throws Exception {
        executor.execute("ActionMailer::MailDeliveryJob", "[{\"job_class\":\"ActionMailer::MailDeliveryJob\"}]");

        verify(mailDeliveryService).deliverActiveJob("[{\"job_class\":\"ActionMailer::MailDeliveryJob\"}]");
        verifyNoInteractions(parser, accountWebsiteJob, wikidataJob);
    }

    @Test
    void accountWebsiteJobsRunWithTheParsedAccountId() throws Exception {
        when(parser.accountId("[{\"account_id\":51}]")).thenReturn(51L);

        executor.execute("AccountWebsiteJob", "[{\"account_id\":51}]");

        verify(accountWebsiteJob).perform(51L);
        verifyNoInteractions(mailDeliveryService, wikidataJob);
    }

    @Test
    void wikidataJobsRunWithTheParsedAccountId() throws Exception {
        when(parser.accountId("[52]")).thenReturn(52L);

        executor.execute("WikidataJob", "[52]");

        verify(wikidataJob).perform(52L);
        verifyNoInteractions(mailDeliveryService, accountWebsiteJob);
    }

    @Test
    void unsupportedClassesAreRejectedWithoutSideEffects() {
        assertThatThrownBy(() -> executor.execute("CustomFieldsBackfillJob", "{}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported Solid Queue class CustomFieldsBackfillJob");
        verifyNoInteractions(parser, mailDeliveryService, accountWebsiteJob, wikidataJob);
    }

    @Test
    void argumentParseFailuresPropagateBeforeAnyJobRuns() {
        when(parser.accountId("garbage")).thenThrow(new IllegalArgumentException("Unable to parse"));

        assertThatThrownBy(() -> executor.execute("AccountWebsiteJob", "garbage"))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(accountWebsiteJob, wikidataJob, mailDeliveryService);
        verifyNoMoreInteractions(accountWebsiteJob);
    }
}
