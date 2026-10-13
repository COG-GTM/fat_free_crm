package com.fatfreecrm.service.mailprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmailReplyParserGoldenTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void matchesEveryUpstreamEmailReplyParserFixture() throws IOException {
        Map<String, String> cases = objectMapper.readValue(
            getClass().getResourceAsStream("/mail/email_reply_parser_golden.json"),
            new TypeReference<>() { });

        for (Map.Entry<String, String> golden : cases.entrySet()) {
            String body;
            try (var input = getClass().getResourceAsStream("/mail/email_reply_parser/" + golden.getKey())) {
                body = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertEquals(golden.getValue(), EmailReplyParser.parseReply(body), golden.getKey());
        }
    }

    @Test
    void exposesFragmentFlagsAndVisibleText() {
        EmailReplyParser.ParsedEmail parsed = EmailReplyParser.read(
            "Reply\n\nOn Monday, Sam wrote:\n> quoted");

        assertEquals("Reply", parsed.visibleText());
        assertEquals(2, parsed.fragments().size());
        assertEquals(false, parsed.fragments().get(0).hidden());
        assertEquals(true, parsed.fragments().get(1).quoted());
    }
}
