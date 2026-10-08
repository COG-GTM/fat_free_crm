package com.fatfreecrm.service.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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
            new MailSettingsService(mock(SettingRepository.class), "", "en-US", "Public",
                "Fat Free CRM <noreply@fatfreecrm.com>", "noreply@fatfreecrm.com", ""));

        RenderedMail mail = renderer.assignment("taylor@example.test", "Example Account", "Account",
            "https://crm.example.test/accounts/9", "Casey Sender");

        assertEquals("Fat Free CRM: You have been assigned Example Account Account", mail.subject());
        assertTrue(mail.textBody().contains(
            "Your colleague Casey Sender has assigned the Example Account Account to you."));
        assertTrue(mail.textBody().contains("https://crm.example.test/accounts/9"));
    }

    private static TemplateEngine templateEngine(String suffix, String mode) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("mail/");
        resolver.setSuffix(suffix);
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        TemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
