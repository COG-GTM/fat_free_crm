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
import org.springframework.boot.DefaultApplicationArguments;

/** Rails rake parity: {@code ffcrm:dropbox:run[dry_run]}, {@code ffcrm:comment_replies:run[dry_run]}, {@code ffcrm:*:setup}. */
class MailProcessorRunnersTest {

    private final MailProcessorService mailProcessorService = mock(MailProcessorService.class);

    private static JobsProperties properties(String owner) {
        JobsProperties properties = new JobsProperties();
        properties.setOwner(owner);
        return properties;
    }

    @Test
    void dropboxRunPassesItsOwnDryRunFlag() {
        JobsProperties properties = properties("spring");
        properties.getDropbox().setDryRun(true);
        properties.getCommentReplies().setDryRun(false);

        new MailProcessorRunner(mailProcessorService, properties).runDropbox();

        verify(mailProcessorService).processDropbox(true);
        verifyNoMoreInteractions(mailProcessorService);
    }

    @Test
    void commentRepliesRunPassesItsOwnDryRunFlag() {
        JobsProperties properties = properties("spring");
        properties.getDropbox().setDryRun(true);
        properties.getCommentReplies().setDryRun(false);

        new MailProcessorRunner(mailProcessorService, properties).runCommentReplies();

        verify(mailProcessorService).processCommentReplies(false);
        verifyNoMoreInteractions(mailProcessorService);
    }

    @Test
    void setupRunnerCreatesBothRailsMailboxFoldersForTheSpringOwner() {
        new MailProcessorSetupRunner(new JobsOwner(properties("spring")), mailProcessorService)
            .run(new DefaultApplicationArguments());

        var order = inOrder(mailProcessorService);
        order.verify(mailProcessorService).setup("email_dropbox");
        order.verify(mailProcessorService).setup("email_comment_replies");
        verifyNoMoreInteractions(mailProcessorService);
    }

    @Test
    void setupRunnerIsANoOpWhenRailsOwnsJobs() {
        new MailProcessorSetupRunner(new JobsOwner(properties("rails")), mailProcessorService)
            .run(new DefaultApplicationArguments());

        verifyNoInteractions(mailProcessorService);
    }
}
