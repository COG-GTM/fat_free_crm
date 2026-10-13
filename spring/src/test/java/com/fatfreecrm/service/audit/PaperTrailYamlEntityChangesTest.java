package com.fatfreecrm.service.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * AB-272 Phase B additions to {@link PaperTrailYaml}: list-valued attributes ({@code tag_list} on
 * entity versions) and the shared-UTC anchor rule when a third timestamp attribute is present.
 * Expected strings come from Psych output of Rails' {@code object_changes} for an account create.
 */
class PaperTrailYamlEntityChangesTest {

    @Test
    void emptyListBeforeAndPopulatedListAfterMatchPsychBlockSequences() {
        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("name", new Object[] {"", "Acme"});
        changes.put("tag_list", new Object[] {List.of(), List.of("vip", "contract")});
        String yaml = PaperTrailYaml.dumpChanges(changes);
        assertThat(yaml).isEqualTo("---\nname:\n- ''\n- Acme\ntag_list:\n- []\n- - vip\n  - contract\n");
        assertThat(parsed(yaml)).isEqualTo(Map.of(
            "name", List.of("", "Acme"), "tag_list", List.of(List.of(), List.of("vip", "contract"))));
    }

    @Test
    void listElementsAreQuotedLikeScalars() {
        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("tag_list", new Object[] {List.of("a b"), List.of("yes", "1", "", "x: y")});
        String yaml = PaperTrailYaml.dumpChanges(changes);
        assertThat(yaml).isEqualTo("---\ntag_list:\n- - a b\n- - \"yes\"\n  - \"1\"\n  - ''\n  - \"x: y\"\n");
        assertThat(parsed(yaml)).isEqualTo(Map.of(
            "tag_list", List.of(List.of("a b"), List.of("yes", "1", "", "x: y"))));
    }

    @Test
    void nilToEmptyListOnDestroyAndListToNilAreBothEmitted() {
        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("tag_list", new Object[] {List.of("vip"), null});
        assertThat(PaperTrailYaml.dumpChanges(changes)).isEqualTo("---\ntag_list:\n- - vip\n-\n");
        changes.put("tag_list", new Object[] {null, List.of()});
        assertThat(PaperTrailYaml.dumpChanges(changes)).isEqualTo("---\ntag_list:\n-\n- []\n");
    }

    @Test
    void dumpObjectSerialisesListsAndNumbersInColumnOrder() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "Acme");
        attributes.put("rating", 3);
        attributes.put("tag_list", List.of("vip"));
        attributes.put("website", null);
        attributes.put("subscribed_users", List.of(1L, 2L));
        String yaml = PaperTrailYaml.dumpObject(attributes);
        assertThat(yaml).contains("name: Acme\n").contains("rating: 3\n").contains("tag_list:\n- vip\n")
            .contains("subscribed_users:\n- 1\n- 2\n");
        assertThat(yaml).contains("website:\n");
        assertThat(parsed(yaml)).isInstanceOf(Map.class);
    }

    @Test
    void thirdTimestampSharingTheInstantReusesTheSameAnchor() {
        Instant same = Instant.parse("2026-10-13T06:00:00.123456Z");
        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("created_at", new Object[] {null, same});
        changes.put("updated_at", new Object[] {null, same});
        changes.put("deleted_at", new Object[] {null, same});
        String yaml = PaperTrailYaml.dumpChanges(changes);
        assertThat(yaml.lines().filter(line -> line.trim().startsWith("utc: &")).count()).isEqualTo(1);
        assertThat(yaml.lines().filter(line -> line.trim().equals("utc: *1")).count()).isEqualTo(2);
        assertThat(yaml.lines().filter(line -> line.trim().startsWith("time:")).count()).isEqualTo(3);
        assertThat(yaml.lines().filter(line -> line.trim().startsWith("zone: &")).count()).isEqualTo(1);
    }

    @Test
    void distinctTimestampsAreNotAnchored() {
        Map<String, Object[]> changes = new LinkedHashMap<>();
        changes.put("created_at", new Object[] {null, Instant.parse("2026-10-13T06:00:00Z")});
        changes.put("updated_at", new Object[] {null, Instant.parse("2026-10-13T06:00:01Z")});
        String yaml = PaperTrailYaml.dumpChanges(changes);
        assertThat(yaml).doesNotContain("utc: &").doesNotContain("utc: *");
        assertThat(yaml.lines().filter(line -> line.trim().startsWith("utc: ")).count()).isEqualTo(2);
    }

    private static Object parsed(String yaml) {
        return new Yaml().load(yaml);
    }
}
