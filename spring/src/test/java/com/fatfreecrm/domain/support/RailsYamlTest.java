package com.fatfreecrm.domain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.error.YAMLException;

class RailsYamlTest {

    @Test
    void treatsNullAndEmptyColumnsAsAbsentValues() {
        assertThat(RailsYaml.read(null)).isNull();
        assertThat(RailsYaml.read("")).isNull();
        assertThat(RailsYaml.read("---\n")).isNull();
        assertThat(RailsYaml.readStringList(null)).isEmpty();
        assertThat(RailsYaml.readStringList("")).isEmpty();
        assertThat(RailsYaml.readStringMap(null)).isEmpty();
        assertThat(RailsYaml.readStringMap("")).isEmpty();
    }

    @Test
    void readsTheDocumentsRailsSerializeWritesForSettingsAndFields() {
        assertThat(RailsYaml.read("--- string value\n")).isEqualTo("string value");
        assertThat(RailsYaml.read("---\n- :one\n- :two\n")).isEqualTo(List.of(":one", ":two"));
        assertThat(RailsYaml.read("---\nnested:\n- value\n- 2\n"))
            .isEqualTo(Map.of("nested", List.of("value", 2)));
        assertThat(RailsYaml.readStringList("--- []\n")).isEmpty();
        assertThat(RailsYaml.readStringList("---\n- one\n- two\n")).containsExactly("one", "two");
        assertThat(RailsYaml.readStringMap("--- {}\n")).isEmpty();
        assertThat(RailsYaml.readStringMap("---\nmultiple: true\nsize: 3\n"))
            .containsExactly(Map.entry("multiple", true), Map.entry("size", 3));
    }

    @Test
    void readStringListRejectsNonSequenceDocumentsAndNonStringItems() {
        assertThatThrownBy(() -> RailsYaml.readStringList("--- scalar\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Expected a YAML sequence");
        assertThatThrownBy(() -> RailsYaml.readStringList("---\nkey: value\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Expected a YAML sequence");
        assertThatThrownBy(() -> RailsYaml.readStringList("---\n- 1\n- two\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Expected string sequence elements");
        assertThatThrownBy(() -> RailsYaml.readStringList("---\n- \n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Expected string sequence elements");
    }

    @Test
    void readStringMapRejectsNonMappingDocumentsAndNonStringKeys() {
        assertThatThrownBy(() -> RailsYaml.readStringMap("---\n- one\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Expected a YAML mapping");
        assertThatThrownBy(() -> RailsYaml.readStringMap("--- scalar\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Expected a YAML mapping");
        assertThatThrownBy(() -> RailsYaml.readStringMap("---\n1: one\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Expected string mapping keys");
    }

    @Test
    void unwrapsRubyTaggedMappingsSequencesAndScalars() {
        assertThat(RailsYaml.read("--- !ruby/hash:ActiveSupport::HashWithIndifferentAccess\nmultiple: true\n"))
            .isEqualTo(Map.of("multiple", true));
        assertThat(RailsYaml.readStringMap("--- !ruby/hash-with-ivars:Hash\nkey: value\n"))
            .containsExactly(Map.entry("key", "value"));
        assertThat(RailsYaml.read("--- !ruby/array:CustomArray\n- a\n- b\n")).isEqualTo(List.of("a", "b"));
        assertThat(RailsYaml.read("--- !ruby/string:CustomString value\n")).isEqualTo("value");
        assertThat(RailsYaml.read("--- !ruby/object\nname: value\n")).isEqualTo(Map.of("name", "value"));
        assertThat(RailsYaml.read("--- !ruby/object:Set\nhash:\n  1: true\n  2: true\n"))
            .isEqualTo(Map.of("hash", Map.of(1, true, 2, true)));
        assertThat(RailsYaml.read("---\nnested: !ruby/hash:Custom\n  inner: !ruby/array:Custom\n  - 1\n"))
            .isEqualTo(Map.of("nested", Map.of("inner", List.of(1))));
    }

    @Test
    void refusesArbitraryJavaTypes() {
        assertThatThrownBy(() -> RailsYaml.read("--- !!java.io.File [\"/tmp\"]\n"))
            .isInstanceOf(YAMLException.class);
        assertThatThrownBy(() -> RailsYaml.read("--- !!javax.script.ScriptEngineManager []\n"))
            .isInstanceOf(YAMLException.class);
    }
}
