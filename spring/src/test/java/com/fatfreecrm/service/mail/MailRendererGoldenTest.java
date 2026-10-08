package com.fatfreecrm.service.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.repository.SettingRepository;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class MailRendererGoldenTest {

    @Test
    void matchesRailsMailerHeadersAndBodies() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, JsonNode> cases = mapper.readValue(
            getClass().getResourceAsStream("/mail/mailers.json"), new TypeReference<>() { });

        for (Map.Entry<String, JsonNode> entry : cases.entrySet()) {
            String name = entry.getKey();
            JsonNode testCase = entry.getValue();
            Map<String, Object> input = mapper.convertValue(
                testCase.path("input"), new TypeReference<>() { });
            Map<String, Object> expected = mapper.convertValue(
                testCase.path("output"), new TypeReference<>() { });
            MailSettingsService settings = settings(input.get("settings"));
            MailRendererService renderer = new MailRendererService(
                templateEngine(".html", "HTML"),
                templateEngine(".txt", "TEXT"),
                settings,
                new EnUsMailText());
            RenderedMail rendered = render(renderer, input);

            assertEquals(expected.get("subject"), rendered.subject(), name);
            assertEquals(expected.get("raw_from"), rendered.from(), name);
            assertEquals(expected.get("raw_to"), rendered.to(), name);
            assertEquals(expected.get("content_type"), rendered.contentType(), name);
            assertEquals(expected.get("body"), rendered.body(), name);
            assertEquals(expected.get("from"), addresses(rendered.from()), name);
            assertEquals(expected.get("to"), addresses(rendered.to()), name);
            assertSinglePartUtf8(rendered, name);
        }
    }

    private static RenderedMail render(MailRendererService renderer, Map<String, Object> input) {
        return switch (input.get("kind").toString()) {
            case "assignment" -> renderer.assignment(
                value(input, "to"),
                value(input, "entity_name"),
                value(input, "entity_type"),
                value(input, "entity_url"),
                value(input, "assigner_name"));
            case "comment" -> renderer.commentNotification(
                value(input, "to"),
                value(input, "from_user_name"),
                value(input, "entity_name"),
                value(input, "entity_type"),
                Long.parseLong(value(input, "entity_id")),
                value(input, "tags"),
                value(input, "comment"));
            case "dropbox" -> renderer.dropboxNotification(
                value(input, "to"),
                value(input, "from"),
                value(input, "subject"),
                value(input, "body"),
                mediatorLinks(input.get("mediator_links")));
            case "devise_confirmation" -> renderer.deviseConfirmation(
                value(input, "to"), value(input, "token"));
            case "devise_reset" -> renderer.deviseResetPassword(
                value(input, "to"), value(input, "token"));
            case "devise_password_change" -> renderer.devisePasswordChange(value(input, "to"));
            default -> throw new IllegalArgumentException("Unknown mail golden kind " + input.get("kind"));
        };
    }

    private static MailSettingsService settings(Object configuredSettings) {
        SettingRepository repository = mock(SettingRepository.class);
        when(repository.findByName(anyString())).thenReturn(Optional.empty());
        if (configuredSettings instanceof Map<?, ?> settings) {
            settings.forEach((name, value) -> {
                Setting setting = mock(Setting.class);
                when(setting.getParsedValue()).thenReturn(value);
                when(repository.findByName(String.valueOf(name))).thenReturn(Optional.of(setting));
            });
        }
        return new MailSettingsService(repository, "https://crm.example.test", "en-US", "Public",
            "", "", "");
    }

    private static String value(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value == null ? "" : value.toString();
    }

    private static String mediatorLinks(Object value) {
        if (!(value instanceof List<?> values)) {
            return "";
        }
        List<String> links = new ArrayList<>();
        values.forEach(link -> links.add(String.valueOf(link)));
        return String.join("\n", links);
    }

    private static List<String> addresses(String value) throws Exception {
        return java.util.Arrays.stream(InternetAddress.parse(value))
            .map(InternetAddress::getAddress)
            .toList();
    }

    private static void assertSinglePartUtf8(RenderedMail rendered, String name) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(rendered.from()));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(rendered.to()));
        message.setSubject(rendered.subject(), StandardCharsets.UTF_8.name());
        message.setContent(rendered.body(), rendered.contentType() + "; charset=UTF-8");
        message.saveChanges();

        assertTrue(message.getContentType().contains("charset=UTF-8"), name);
        assertFalse(message.isMimeType("multipart/*"), name);
        assertEquals(rendered.body(), message.getContent(), name);
    }

    private static TemplateEngine templateEngine(String suffix, String mode) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/mail/");
        resolver.setSuffix(suffix);
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);
        TemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
