package com.fatfreecrm.service.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fatfreecrm.repository.SettingRepository;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class MailRendererServiceTest {

    @Test
    void rendersAssignmentWithRailsSubjectAndBodyText() {
        MailRendererService renderer = new MailRendererService(
            templateEngine(".html", "HTML"),
            templateEngine(".txt", "TEXT"),
            new MailSettingsService(mock(SettingRepository.class), "", "en-US", "Public", "", "", ""),
            new EnUsMailText());

        RenderedMail mail = renderer.assignment("taylor@example.test", "Example Account", "Account",
            "https://crm.example.test/accounts/9", "Casey Sender");

        assertEquals("Fat Free CRM: You have been assigned Example Account Account", mail.subject());
        assertEquals("Fat Free CRM <noreply@fatfreecrm.com>", mail.from());
        assertEquals("text/html", mail.contentType());
        assertTrue(mail.body().contains(
            "Your colleague Casey Sender has assigned the Example Account Account to you."));
        assertTrue(mail.body().contains("https://crm.example.test/accounts/9"));
    }

    @Test
    void resolvesCurrentHostForEachCommentRender() {
        MailSettingsService settings = mock(MailSettingsService.class);
        when(settings.locale()).thenReturn("en-US");
        when(settings.smtpFrom()).thenReturn("noreply@example.test");
        when(settings.commentReplyFrom("Casey Sender")).thenReturn("Casey Sender <noreply@example.test>");
        when(settings.host()).thenReturn("https://first.example.test", "https://second.example.test");
        MailRendererService renderer = new MailRendererService(
            templateEngine(".html", "HTML"),
            templateEngine(".txt", "TEXT"),
            settings,
            new EnUsMailText());

        String first = renderer.commentNotification(
            "taylor@example.test", "Casey Sender", "Example Account", "Account", 9, "", "Hello").body();
        String second = renderer.commentNotification(
            "taylor@example.test", "Casey Sender", "Example Account", "Account", 9, "", "Hello").body();

        assertTrue(first.contains("https://first.example.test/accounts/9"));
        assertTrue(second.contains("https://second.example.test/accounts/9"));
    }

    private static TemplateEngine templateEngine(String suffix, String mode) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/mail/");
        resolver.setSuffix(suffix);
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        TemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
