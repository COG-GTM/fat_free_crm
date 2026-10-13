package com.fatfreecrm.service.mailprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class MailProcessorGoldenTest {

    @Test
    void matchesRailsProcessorCorpus() throws Exception {
        Map<String, Map<String, Object>> goldens = new ObjectMapper().readValue(
            getClass().getResourceAsStream("/mail/processor.json"), new TypeReference<>() { });
        for (Map.Entry<String, Map<String, Object>> golden : goldens.entrySet()) {
            String path = "/mail/eml/" + golden.getKey();
            String raw;
            try (var input = getClass().getResourceAsStream(path)) {
                raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                new java.io.ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
            String body = MailProcessorBase.plainTextBody(message);
            Map<String, Object> expected = golden.getValue();
            assertEquals(golden.getValue().get("is_valid"), MailProcessorBase.isValid(message), golden.getKey());
            assertEquals(expected.get("plain_text_body"), body, golden.getKey());
            assertEquals(expected.get("parsed_reply"), EmailReplyParser.parseReply(body), golden.getKey());
            assertEquals(expected.get("subject_line"), CommentRepliesProcessor.subjectLine(message.getSubject()),
                golden.getKey());
            assertEquals(expected.get("explicit_keyword"), DropboxProcessor.explicitKeyword(body), golden.getKey());
            assertEquals(expected.get("recipients"), DropboxProcessor.recipients(message, Map.of(
                "address", "dropbox@example.test",
                "address_aliases", List.of("alias@example.test"))), golden.getKey());
            String forwardedRecipient = DropboxProcessor.firstForwardedRecipient(body);
            assertEquals(expected.get("forwarded_recipient"),
                forwardedRecipient == null ? null : List.of(forwardedRecipient), golden.getKey());
            assertEquals(expected.get("from"), addresses(message.getFrom()), golden.getKey());
            assertEquals(expected.get("to"), addresses(message.getRecipients(Message.RecipientType.TO)),
                golden.getKey());
            assertEquals(expected.get("cc"), addresses(message.getRecipients(Message.RecipientType.CC)),
                golden.getKey());
            assertEquals(expected.get("message_id"), messageId(message), golden.getKey());
            assertEquals(expected.get("subject"), message.getSubject(), golden.getKey());
            assertEquals(expected.get("date"), date(message), golden.getKey());
        }
    }

    private static List<String> addresses(jakarta.mail.Address[] addresses) {
        return MailProcessorBase.addressList(addresses);
    }

    private static String messageId(MimeMessage message) throws Exception {
        String messageId = message.getMessageID();
        return messageId == null ? null : messageId.replaceAll("^<|>$", "");
    }

    private static String date(MimeMessage message) throws Exception {
        if (message.getSentDate() == null) {
            return null;
        }
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            message.getSentDate().toInstant().atOffset(ZoneOffset.UTC)).replace("Z", "+00:00");
    }
}
