package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DbAssertMaskTest {

    @Test
    void maskYamlMasksScalarTopLevelKeys() {
        String yaml = "---\ncreated_at: 2026-01-01\nname: keep\n";
        String masked = DbAssert.maskYaml(yaml, List.of("created_at"));
        assertTrue(masked.contains("created_at: __volatile__"));
        assertTrue(masked.contains("name: keep"));
    }

    @Test
    void maskYamlMasksSequenceEntries() {
        String yaml = "---\nupdated_at:\n- \n- 2026-01-01T00:00:00Z\nname:\n- a\n- b\n";
        String masked = DbAssert.maskYaml(yaml, List.of("updated_at"));
        assertTrue(masked.contains("- __volatile__"));
        assertFalse(masked.contains("2026-01-01"));
        assertTrue(masked.contains("- a\n- b"));
    }

    @Test
    void maskYamlMasksUtcAndTimeInsideMaskedBlock() {
        String yaml = "---\ndue_at:\n- !ruby/object:ActiveSupport::TimeWithZone\n"
            + "  utc: 2026-01-01T00:00:00Z\n  time: 2026-01-01T00:00:00Z\nother: x\n";
        String masked = DbAssert.maskYaml(yaml, List.of("due_at"));
        assertTrue(masked.contains("utc: __volatile__"));
        assertTrue(masked.contains("time: __volatile__"));
        assertTrue(masked.contains("other: x"));
    }

    @Test
    void maskYamlEmptyKeysReturnsInput() {
        assertEquals("---\nx: 1\n", DbAssert.maskYaml("---\nx: 1\n", List.of()));
    }
}
