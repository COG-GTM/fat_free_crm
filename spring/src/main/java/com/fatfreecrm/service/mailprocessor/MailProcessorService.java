package com.fatfreecrm.service.mailprocessor;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.mail.Message;
import org.springframework.stereotype.Service;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed processor services are intentionally retained by this facade."
)
public class MailProcessorService {

    private final DropboxProcessor dropboxProcessor;
    private final CommentRepliesProcessor commentRepliesProcessor;

    public MailProcessorService(
        DropboxProcessor dropboxProcessor,
        CommentRepliesProcessor commentRepliesProcessor
    ) {
        this.dropboxProcessor = dropboxProcessor;
        this.commentRepliesProcessor = commentRepliesProcessor;
    }

    public void processDropbox(boolean dryRun) {
        dropboxProcessor.process(dryRun);
    }

    public void processCommentReplies(boolean dryRun) {
        commentRepliesProcessor.process(dryRun);
    }

    public void setup(String mailbox) {
        dropboxProcessor.setup(mailbox);
    }

    public static boolean isValid(Message message) throws Exception {
        return MailProcessorBase.isValid(message);
    }
}
