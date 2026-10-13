package com.fatfreecrm.service.jobs;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fatfreecrm.service.mail.MailDeliveryService;
import org.junit.jupiter.api.Test;

/** Solid Queue bridge dispatch: the three supported Rails job classes and nothing else. */
class SolidQueueJobExecutorTest {

    private final ActiveJobArgumentParser parser = mock(ActiveJobArgumentParser.class);
    private final MailDeliveryService mailDeliveryService = mock(MailDeliveryService.class);
    private final AccountWebsiteJob accountWebsiteJob = mock(AccountWebsiteJob.class);
    private final WikidataJob wikidataJob = mock(WikidataJob.class);
    private final SolidQueueJobExecutor executor =
        new SolidQueueJobExecutor(parser, mailDeliveryService, accountWebsiteJob, wikidataJob);

    @Test
    void mailDeliveryJobsPassTheSerializedActiveJobArgumentsVerbatim() throws Exception {
        executor.execute("ActionMailer::MailDeliveryJob", "{\"arguments\":[\"UserMailer\"]}");

        verify(mailDeliveryService).deliverActiveJob("{\"arguments\":[\"UserMailer\"]}");
        verifyNoMoreInteractions(accountWebsiteJob, wikidataJob);
    }

    @Test
    void accountWebsiteJobsReceiveTheParsedAccountId() throws Exception {
        when(parser.accountId("{\"arguments\":[7]}")).thenReturn(7L);

        executor.execute("AccountWebsiteJob", "{\"arguments\":[7]}");

        verify(accountWebsiteJob).perform(7L);
        verifyNoMoreInteractions(wikidataJob, mailDeliveryService);
    }

    @Test
    void wikidataJobsReceiveTheParsedAccountId() throws Exception {
        when(parser.accountId("{\"arguments\":[9]}")).thenReturn(9L);

        executor.execute("WikidataJob", "{\"arguments\":[9]}");

        verify(wikidataJob).perform(9L);
        verifyNoMoreInteractions(accountWebsiteJob, mailDeliveryService);
    }

    @Test
    void unsupportedClassesAreRejectedSoTheRowStaysForRails() {
        assertThatThrownBy(() -> executor.execute("SomeOtherJob", "{}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SomeOtherJob");
        verifyNoMoreInteractions(accountWebsiteJob, wikidataJob, mailDeliveryService);
    }
}
