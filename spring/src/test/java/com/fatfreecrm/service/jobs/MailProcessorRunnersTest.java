package com.fatfreecrm.service.jobs;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fatfreecrm.config.JobsProperties;
import com.fatfreecrm.service.mailprocessor.MailProcessorService;
import com.fatfreecrm.service.mailprocessor.MailProcessorSetupRunner;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.ApplicationArguments;

/** The Quartz poll jobs and the IMAP folder setup runner delegate to {@link MailProcessorService}. */
class MailProcessorRunnersTest {

    @Test
    void dropboxRunPassesItsOwnDryRunFlag() {
        MailProcessorService service = mock(MailProcessorService.class);
        JobsProperties properties = new JobsProperties();
        properties.getDropbox().setDryRun(true);
        properties.getCommentReplies().setDryRun(false);

        new MailProcessorRunner(service, properties).runDropbox();

        verify(service).processDropbox(true);
        verifyNoMoreInteractions(service);
    }

    @Test
    void commentRepliesRunPassesItsOwnDryRunFlag() {
        MailProcessorService service = mock(MailProcessorService.class);
        JobsProperties properties = new JobsProperties();
        properties.getDropbox().setDryRun(true);
        properties.getCommentReplies().setDryRun(false);

        new MailProcessorRunner(service, properties).runCommentReplies();

        verify(service).processCommentReplies(false);
        verifyNoMoreInteractions(service);
    }

    @Test
    void setupRunnerDoesNothingWhenRailsOwnsJobs() {
        MailProcessorService service = mock(MailProcessorService.class);

        new MailProcessorSetupRunner(new JobsOwner(new JobsProperties()), service)
            .run(mock(ApplicationArguments.class));

        verifyNoInteractions(service);
    }

    @Test
    void setupRunnerCreatesBothRailsMailboxFolderSetsInOrder() {
        MailProcessorService service = mock(MailProcessorService.class);
        JobsProperties properties = new JobsProperties();
        properties.setOwner("spring");

        new MailProcessorSetupRunner(new JobsOwner(properties), service).run(mock(ApplicationArguments.class));

        InOrder order = inOrder(service);
        order.verify(service).setup("email_dropbox");
        order.verify(service).setup("email_comment_replies");
        verifyNoMoreInteractions(service);
    }
}
