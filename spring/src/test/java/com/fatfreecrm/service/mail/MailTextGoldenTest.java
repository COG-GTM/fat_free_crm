package com.fatfreecrm.service.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class MailTextGoldenTest {

    @Test
    void matchesRailsGeneratedEnUsText() throws IOException {
        Map<String, String> golden = new ObjectMapper().readValue(
            getClass().getResourceAsStream("/mail/mail_text.json"), new TypeReference<>() { });
        Properties properties = new Properties();
        try (var input = getClass().getResourceAsStream("/mail/mail_text_en_US.properties")) {
            properties.load(input);
        }
        Map<String, String> implementation = properties.stringPropertyNames().stream()
            .collect(java.util.stream.Collectors.toMap(key -> key, properties::getProperty));
        assertEquals(golden, implementation);
    }

    @Test
    void interpolatesMailTextArguments() {
        assertEquals("Hello Taylor!", new EnUsMailText().text(
            "devise.mailer.reset_password_instructions.greeting", Map.of("recipient", "Taylor")));
    }
}
