package com.fatfreecrm.service.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.config.JobsProperties;
import com.fatfreecrm.service.jobs.JobsOwner;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class MailDeliveryServiceTest {

    @Test
    void usesRailsSmtpSettingsWhenAnAddressIsConfigured() {
        JavaMailSender fallback = mock(JavaMailSender.class);
        MailSettingsService settings = mock(MailSettingsService.class);
        when(settings.section("smtp")).thenReturn(Map.of(
            "address", "smtp.example.test",
            "port", "587",
            "user_name", "sender",
            "password", "password",
            "authentication", ":plain",
            "enable_starttls_auto", true));
        MailDeliveryService service = service(fallback, settings);

        JavaMailSenderImpl sender = assertInstanceOf(JavaMailSenderImpl.class, service.senderForSettings());

        assertEquals("smtp.example.test", sender.getHost());
        assertEquals(587, sender.getPort());
        assertEquals("sender", sender.getUsername());
        assertEquals("password", sender.getPassword());
        assertEquals("PLAIN", sender.getJavaMailProperties().getProperty("mail.smtp.auth.mechanisms"));
        assertEquals("true", sender.getJavaMailProperties().getProperty("mail.smtp.starttls.enable"));
    }

    @Test
    void usesBootMailSenderWhenRailsHasNoSmtpAddress() {
        JavaMailSender fallback = mock(JavaMailSender.class);
        MailSettingsService settings = mock(MailSettingsService.class);
        when(settings.section("smtp")).thenReturn(Map.of("from", "noreply@example.test"));

        assertSame(fallback, service(fallback, settings).senderForSettings());
    }

    @Test
    void deliversSinglePartUtf8MailWithDeclaredContentType() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
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
            "Subject", "sender@example.test", "recipient@example.test", "text/plain", "Café"));
        message.saveChanges();

        verify(sender).send(message);
        assertEquals("text/plain", message.getContentType().split(";")[0]);
        assertTrue(message.getContentType().contains("charset=UTF-8"));
        assertEquals("Café", message.getContent());
        assertFalse(message.isMimeType("multipart/*"));
    }

    private static MailDeliveryService service(JavaMailSender fallback, MailSettingsService settings) {
        return new MailDeliveryService(
            fallback,
            new ObjectMapper(),
            mock(EntityManager.class),
            mock(MailRendererService.class),
            settings,
            new JobsOwner(new JobsProperties()));
    }
}
