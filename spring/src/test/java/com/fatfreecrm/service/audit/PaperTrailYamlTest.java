package com.fatfreecrm.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Golden tests for {@link PaperTrailYaml}, matching bytes captured from live Rails
 * PaperTrail rows (versions 22/25/30/36 in the contract database).
 */
class PaperTrailYamlTest {

    private static final Instant T1 = Instant.parse("2026-10-08T03:04:05.123456789Z");

    private static String tzBlock(Instant instant) {
        return "!ruby/object:ActiveSupport::TimeWithZone\n"
            + "  utc: " + instant + "\n"
            + "  zone: &1 !ruby/object:ActiveSupport::TimeZone\n"
            + "    name: Etc/UTC\n"
            + "  time: " + instant + "\n";
    }

    @Test
    void dumpChangesSingleTimestampUsesAnchoredZone() {
        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("name", new Object[] {null, "Call Alice"});
        changes.put("due_at", new Object[] {null, T1});
        // Two timestamps (due_at and updated_at) share the TimeZone anchor.
        changes.put("updated_at", new Object[] {null, T1});
        String yaml = PaperTrailYaml.dumpChanges(changes);
        // Same Instant twice: first utc anchored &1, shared zone anchored &2, then *1/*2.
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("utc: &1"));
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("zone: &2 !ruby/object:ActiveSupport::TimeZone"));
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("utc: *1"));
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("zone: *2"));
    }

    @Test
    void dumpChangesSharedUtcTimestampIsAnchored() {
        Instant same = Instant.parse("2026-10-08T03:04:05.999999999Z");
        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("created_at", new Object[] {null, same});
        changes.put("updated_at", new Object[] {null, same});
        String yaml = PaperTrailYaml.dumpChanges(changes);
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("utc: &"), "shared utc anchored");
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("utc: *"), "second utc aliased");
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("zone: &"), "shared zone anchored");
    }

    @Test
    void equalButDistinctUtcTimestampsAreNotAnchored() {
        Instant first = Instant.ofEpochSecond(1_791_431_045L, 999_999_999);
        Instant second = Instant.ofEpochSecond(1_791_431_045L, 999_999_999);
        assertNotSame(first, second);
        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("created_at", new Object[] {null, first});
        changes.put("updated_at", new Object[] {null, second});

        String yaml = PaperTrailYaml.dumpChanges(changes);

        org.junit.jupiter.api.Assertions.assertFalse(yaml.contains("utc: &"), yaml);
        org.junit.jupiter.api.Assertions.assertFalse(yaml.contains("utc: *"), yaml);
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("zone: &1"), yaml);
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("zone: *1"), yaml);
    }

    @Test
    void dumpChangesQuotingMatchesPsych() {
        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("body", new Object[] {"", "yes"});
        changes.put("notes", new Object[] {null, "a: b"});
        changes.put("street1", new Object[] {null, "  leading"});
        changes.put("suffix", new Object[] {null, "leading "});
        changes.put("imap_message_id", new Object[] {null, "<contract-701@ffcrm>"});
        String yaml = PaperTrailYaml.dumpChanges(changes);
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("- ''"));
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("- 'yes'"));
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("- 'a: b'"));
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("- \"  leading\""));
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("- 'leading '"));
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("- \"<contract-701@ffcrm>\""));
    }

    @Test
    void contractEmailMessageIdMatchesRailsDestroyGolden() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("id", 701L);
        attributes.put("imap_message_id", "<contract-701@ffcrm>");

        assertEquals(
            "---\nid: 701\nimap_message_id: \"<contract-701@ffcrm>\"\n",
            PaperTrailYaml.dumpObject(attributes));
    }

    @Test
    void dumpsRailsDateAndBigDecimalShapes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("starts_on", LocalDate.parse("2025-02-03"));
        attributes.put("budget", new BigDecimal("12.50"));
        attributes.put("long_budget", new BigDecimal("12345678901234567890.123456789"));
        attributes.put("tiny_budget", new BigDecimal("-0.000000001"));
        attributes.put("zero_budget", BigDecimal.ZERO);

        assertEquals(
            "---\nstarts_on: 2025-02-03\nbudget: !ruby/object:BigDecimal 18:0.125e2\n"
                + "long_budget: !ruby/object:BigDecimal 36:0.12345678901234567890123456789e20\n"
                + "tiny_budget: !ruby/object:BigDecimal 9:-0.1e-8\n"
                + "zero_budget: !ruby/object:BigDecimal 9:0.0\n",
            PaperTrailYaml.dumpObject(attributes));
    }

    @Test
    void dumpObjectDropsNullAttributesAndOrdersColumns() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("id", 701L);
        attrs.put("imap_message_id", null);
        attrs.put("sent_from", "alice@example.com");
        attrs.put("subscribed_users", List.of(2, 3));
        String yaml = PaperTrailYaml.dumpObject(attrs);
        assertEquals(
            "---\nid: 701\nimap_message_id:\nsent_from: alice@example.com\nsubscribed_users:\n- 2\n- 3\n",
            yaml);
    }

    @Test
    void dumpsTagListSnapshotAsRubyArrayAndEmptyChangesAsSequences() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("tag_list", new PaperTrailYaml.RubyTagList(List.of("alpha", "beta")));
        assertEquals(
            "---\ntag_list: !ruby/array:ActsAsTaggableOn::TagList\n"
                + "  internal:\n  - alpha\n  - beta\n"
                + "  ivars:\n    :@parser: !ruby/class 'ActsAsTaggableOn::DefaultParser'\n",
            PaperTrailYaml.dumpObject(attributes));

        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("tag_list", new Object[] {List.of(), List.of("alpha", "beta")});
        assertEquals(
            "---\ntag_list:\n- []\n- - alpha\n  - beta\n",
            PaperTrailYaml.dumpChanges(changes));
    }

    @Test
    void destroyTagListChangesUseTaggedRubyArraySnapshot() {
        PaperTrailYaml.RubyTagList tagList = new PaperTrailYaml.RubyTagList(List.of("delta", "epsilon"));
        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("tag_list", new Object[] {tagList, null});

        assertEquals(
            "---\ntag_list:\n- !ruby/array:ActsAsTaggableOn::TagList\n"
                + "  internal:\n  - delta\n  - epsilon\n"
                + "  ivars:\n    :@parser: !ruby/class 'ActsAsTaggableOn::DefaultParser'\n-\n",
            PaperTrailYaml.dumpChanges(changes));
    }

    @Test
    void destroyChangesUseBeforeNilPairs() {
        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("subject", new Object[] {"Hello", null});
        String yaml = PaperTrailYaml.dumpChanges(changes);
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("subject:\n- Hello\n-\n"));
    }

    @Test
    void sharedInstantAliasEmitsNoLiteralAndTimeStaysLiteral() {
        // Verified live on a Rails task-create version: `utc: *N` for the second occurrence and
        // `time:` always the literal timestamp (Psych's TWZ coder doesn't anchor `time:`).
        Instant same = Instant.parse("2026-10-08T03:32:15.444801421Z");
        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("created_at", new Object[] {null, same});
        changes.put("updated_at", new Object[] {null, same});
        String yaml = PaperTrailYaml.dumpChanges(changes);
        String literal = "2026-10-08 03:32:15.444801421 Z";
        org.junit.jupiter.api.Assertions.assertTrue(
            yaml.contains("utc: &1 " + literal + "\n"), yaml);
        org.junit.jupiter.api.Assertions.assertTrue(
            yaml.contains("utc: *1\n"), yaml); // bare alias — no literal after it
        org.junit.jupiter.api.Assertions.assertFalse(
            yaml.contains("utc: *1 " + literal), yaml);
        long timeLines = yaml.lines().filter(line -> line.trim().startsWith("time:")).count();
        org.junit.jupiter.api.Assertions.assertEquals(2, timeLines, yaml);
    }

    @Test
    void everyGeneratedYamlParsesWithSnakeYaml() {
        Instant same = Instant.parse("2026-10-08T03:32:15.444801421Z");
        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("name", new Object[] {null, "<b>Call</b>"});
        changes.put("body", new Object[] {"", "line one\nline two"});
        changes.put("created_at", new Object[] {null, same});
        changes.put("updated_at", new Object[] {null, same});
        changes.put("subscribed_users", new Object[] {null, List.of(2, 3)});
        String yaml = PaperTrailYaml.dumpChanges(changes);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> parseStripTags(yaml), yaml);

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("id", 1L);
        attrs.put("created_at", same);
        attrs.put("updated_at", same);
        attrs.put("note", "it's here");
        attrs.put("unicode", "Zoë😀");
        String objectYaml = PaperTrailYaml.dumpObject(attrs);
        org.junit.jupiter.api.Assertions.assertTrue(
            objectYaml.contains("unicode: \"Zoë\\U0001F600\""), objectYaml);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> parseStripTags(objectYaml));
    }

    /** SnakeYAML can't construct {@code !ruby/object:} tags — strip them and parse structure. */
    private static Object parseStripTags(String yaml) {
        return new org.yaml.snakeyaml.Yaml().load(
            yaml.replace("!ruby/object:ActiveSupport::TimeWithZone", "")
                .replace("!ruby/object:ActiveSupport::TimeZone", ""));
    }
}
