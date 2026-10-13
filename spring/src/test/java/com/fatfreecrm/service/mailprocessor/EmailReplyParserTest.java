package com.fatfreecrm.service.mailprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmailReplyParserTest {

    @Test
    void matchesEveryUpstreamFixtureOutput() throws IOException {
        Map<String, String> goldens = new ObjectMapper().readValue(
            getClass().getResourceAsStream("/mail/email_reply_parser_golden.json"), new TypeReference<>() { });
        for (Map.Entry<String, String> golden : goldens.entrySet()) {
            String name = golden.getKey().replaceFirst("\\.txt$", "");
            assertEquals(golden.getValue(), EmailReplyParser.parseReply(fixture(name)), golden.getKey());
        }
    }

    @Test
    void readsSimpleBodyAndSignatureFragments() throws IOException {
        EmailReplyParser.ParsedEmail reply = email("email_1_1");
        assertEquals(3, reply.fragments().size());
        assertEquals(List.of(false, false, false), reply.fragments().stream()
            .map(EmailReplyParser.Fragment::quoted).toList());
        assertEquals(List.of(false, true, true), reply.fragments().stream()
            .map(EmailReplyParser.Fragment::signature).toList());
        assertEquals(List.of(false, true, true), reply.fragments().stream()
            .map(EmailReplyParser.Fragment::hidden).toList());
        assertEquals("Hi folks\n\nWhat is the best way to clear a Riak bucket of all key, values after\n"
            + "running a test?\nI am currently using the Java HTTP API.\n", reply.fragments().get(0).content());
        assertEquals("-Abhishek Kona\n\n", reply.fragments().get(1).content());
    }

    @Test
    void readsTopPostFragments() throws IOException {
        EmailReplyParser.ParsedEmail reply = email("email_1_3");
        assertEquals(5, reply.fragments().size());
        assertEquals(List.of(false, false, true, false, false), flags(reply, 0));
        assertEquals(List.of(false, true, true, true, true), flags(reply, 2));
        assertEquals(List.of(false, true, false, false, true), flags(reply, 1));
        assertTrue(reply.fragments().get(0).content().startsWith("Oh thanks.\n\nHaving"));
        assertTrue(reply.fragments().get(1).content().startsWith("-A"));
        assertTrue(hasLineStartingWith(reply.fragments().get(2).content(), "On "));
        assertTrue(reply.fragments().get(4).content().startsWith("_"));
    }

    @Test
    void readsBottomPostFragments() throws IOException {
        EmailReplyParser.ParsedEmail reply = email("email_1_2");
        assertEquals(6, reply.fragments().size());
        assertEquals(List.of(false, true, false, true, false, false), flags(reply, 0));
        assertEquals(List.of(false, false, false, false, false, true), flags(reply, 1));
        assertEquals(List.of(false, false, false, true, true, true), flags(reply, 2));
        assertEquals("Hi,", reply.fragments().get(0).content());
        assertTrue(hasLineStartingWith(reply.fragments().get(1).content(), "On "));
        assertTrue(hasLineStartingWith(reply.fragments().get(2).content(), "You can list"));
        assertTrue(hasLineStartingWith(reply.fragments().get(3).content(), "> "));
        assertTrue(hasLineStartingWith(reply.fragments().get(5).content(), "_"));
    }

    @Test
    void recognizesDateStringAboveQuote() throws IOException {
        EmailReplyParser.ParsedEmail reply = email("email_1_4");
        assertTrue(reply.fragments().get(0).content().startsWith("Awesome"));
        assertTrue(hasLineStartingWith(reply.fragments().get(1).content(), "On"));
        assertTrue(reply.fragments().get(1).content().contains("Loader"));
    }

    @Test
    void keepsComplexBodyInOneFragment() throws IOException {
        assertEquals(1, email("email_1_5").fragments().size());
    }

    @Test
    void recognizesCorrectSignature() throws IOException {
        EmailReplyParser.ParsedEmail reply = email("correct_sig");
        assertEquals(2, reply.fragments().size());
        assertEquals(List.of(false, false), flags(reply, 0));
        assertEquals(List.of(false, true), flags(reply, 1));
        assertEquals(List.of(false, true), flags(reply, 2));
        assertTrue(reply.fragments().get(1).content().startsWith("-- \nrick"));
    }

    @Test
    void readsMultilineReplyHeaders() throws IOException {
        EmailReplyParser.ParsedEmail reply = email("email_1_6");
        assertTrue(reply.fragments().get(0).content().startsWith("I get"));
        assertTrue(hasLineStartingWith(reply.fragments().get(1).content(), "On"));
        assertTrue(reply.fragments().get(1).content().contains("Was this"));
    }

    @Test
    void doesNotModifyInput() {
        String original = "The Quick Brown Fox Jumps Over The Lazy Dog";
        EmailReplyParser.read(original);
        assertEquals("The Quick Brown Fox Jumps Over The Lazy Dog", original);
    }

    @Test
    void returnsVisibleFragmentsOnly() throws IOException {
        EmailReplyParser.ParsedEmail reply = email("email_2_1");
        String expected = reply.fragments().stream()
            .filter(fragment -> !fragment.hidden())
            .map(EmailReplyParser.Fragment::content)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("")
            .stripTrailing();
        assertEquals(expected, reply.visibleText());
    }

    private static boolean hasLineStartingWith(String text, String prefix) {
        return text.lines().anyMatch(line -> line.startsWith(prefix));
    }

    @Test
    void parsesOutlookReply() throws IOException {
        assertEquals("Outlook with a reply", EmailReplyParser.parseReply(fixture("email_2_1")));
    }

    @Test
    void parsesOutlookReplyDirectlyAboveLine() throws IOException {
        assertEquals("Outlook with a reply directly above line", EmailReplyParser.parseReply(fixture("email_2_2")));
    }

    @Test
    void parsesMobileSignatures() throws IOException {
        assertEquals("Here is another email", EmailReplyParser.parseReply(fixture("email_iPhone")));
        assertEquals("Here is another email", EmailReplyParser.parseReply(fixture("email_BlackBerry")));
        assertEquals("Here is another email",
            EmailReplyParser.parseReply(fixture("email_multi_word_sent_from_my_mobile_device")));
    }

    @Test
    void doesNotRemoveSentFromInRegularSentence() throws IOException {
        assertEquals("Here is another email\n\nSent from my desk, is much easier then my mobile phone.",
            EmailReplyParser.parseReply(fixture("email_sent_from_my_not_signature")));
    }

    @Test
    void retainsBullets() throws IOException {
        assertEquals("test 2 this should list second\n\nand have spaces\n\nand retain this formatting\n\n\n"
                + "   - how about bullets\n   - and another",
            EmailReplyParser.parseReply(fixture("email_bullets")));
    }

    @Test
    void parseReplyMatchesVisibleText() throws IOException {
        String body = fixture("email_1_2");
        assertEquals(EmailReplyParser.read(body).visibleText(), EmailReplyParser.parseReply(body));
    }

    private static List<Boolean> flags(EmailReplyParser.ParsedEmail reply, int flag) {
        return reply.fragments().stream().map(fragment -> switch (flag) {
            case 0 -> fragment.quoted();
            case 1 -> fragment.signature();
            default -> fragment.hidden();
        }).toList();
    }

    private static EmailReplyParser.ParsedEmail email(String name) throws IOException {
        return EmailReplyParser.read(fixture(name));
    }

    private static String fixture(String name) throws IOException {
        try (var input = EmailReplyParserTest.class.getResourceAsStream("/mail/email_reply_parser/" + name + ".txt")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
