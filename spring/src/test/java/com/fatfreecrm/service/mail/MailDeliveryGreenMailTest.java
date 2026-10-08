package com.fatfreecrm.service.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.config.JobsProperties;
import com.fatfreecrm.service.jobs.JobsOwner;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class MailDeliveryGreenMailTest {

    @Test
    void sendsSinglePartUtf8MailOverSmtp() throws Exception {
        GreenMail greenMail = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        greenMail.start();
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost("127.0.0.1");
            sender.setPort(greenMail.getSmtp().getPort());
            MailSettingsService settings = mock(MailSettingsService.class);
            when(settings.section("smtp")).thenReturn(Map.of());
            JobsProperties properties = new JobsProperties();
            properties.setOwner("spring");
            MailDeliveryService service = new MailDeliveryService(
                sender,
                new ObjectMapper(),
                mock(EntityManager.class),
                mock(MailRendererService.class),
                settings,
                new JobsOwner(properties));

            service.deliver(new RenderedMail(
                "UTF-8 subject", "sender@example.test", "recipient@example.test", "text/html", "<p>Café</p>"));

            assertTrue(greenMail.waitForIncomingEmail(3_000, 1));
            MimeMessage received = greenMail.getReceivedMessages()[0];
            assertEquals("text/html", received.getContentType().split(";")[0]);
            assertTrue(received.getContentType().contains("charset=UTF-8"));
            assertEquals("<p>Café</p>", received.getContent());
            assertFalse(received.isMimeType("multipart/*"));
        } finally {
            greenMail.stop();
        }
    }
}
