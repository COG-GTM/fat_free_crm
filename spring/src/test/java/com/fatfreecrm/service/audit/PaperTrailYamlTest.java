package com.fatfreecrm.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
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
    void dumpChangesQuotingMatchesPsych() {
        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("body", new Object[] {"", "<b>hi</b>"});
        String yaml = PaperTrailYaml.dumpChanges(changes);
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("- ''"));
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("- \"<b>hi</b>\""));
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
    void destroyChangesUseBeforeNilPairs() {
        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("subject", new Object[] {"Hello", null});
        String yaml = PaperTrailYaml.dumpChanges(changes);
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("subject:\n- Hello\n-\n"));
    }
}
