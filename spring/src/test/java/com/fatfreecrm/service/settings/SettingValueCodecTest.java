package com.fatfreecrm.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

/** Direct coverage of the Psych-compatible settings value codec used by the AB-274 conversion guard. */
class SettingValueCodecTest {

    @Test
    void nullInNullOut() {
        assertThat(SettingValueCodec.decode(null)).isNull();
        assertThat(SettingValueCodec.isJson(null)).isFalse();
    }

    @Test
    void railsYamlDocumentsDecodeSymbolsAsColonStrings() {
        assertThat(SettingValueCodec.decode("--- :planned\n")).isEqualTo(":planned");
        assertThat(SettingValueCodec.decode("  --- 7\n")).isEqualTo(7);
        assertThat(SettingValueCodec.decode("--- false\n")).isEqualTo(false);
        assertThat(SettingValueCodec.decode("---\n- :a\n- plain\n")).isEqualTo(List.of(":a", "plain"));
        Object hash = SettingValueCodec.decode("---\n:server: imap.test\n:port: 993\n");
        assertThat(hash).isInstanceOf(Map.class);
        assertThat(hash).asInstanceOf(InstanceOfAssertFactories.MAP)
            .containsEntry(":server", "imap.test").containsEntry(":port", 993);
    }

    @Test
    void strictJsonDecodesDirectly() {
        Object decoded = SettingValueCodec.decode("{\"a\": [1, 2], \"b\": null}");
        assertThat(decoded).isInstanceOf(Map.class);
        assertThat(decoded).asInstanceOf(InstanceOfAssertFactories.MAP)
            .containsEntry("a", List.of(1, 2)).containsEntry("b", null);
        assertThat(SettingValueCodec.decode("[1, \"x\"]")).isEqualTo(List.of(1, "x"));
        assertThat(SettingValueCodec.decode("\"quoted\"")).isEqualTo("quoted");
    }

    @Test
    void nonJsonTextWithoutDocumentMarkerFallsBackToYaml() {
        assertThat(SettingValueCodec.decode("- :a\n- :b\n")).isEqualTo(List.of(":a", ":b"));
        assertThat(SettingValueCodec.decode("server: imap.test\n")).isEqualTo(Map.of("server", "imap.test"));
        assertThat(SettingValueCodec.decode("{\"a\": 1} trailing")).isNotNull();
    }

    @Test
    void permittedRubyClassesDecodeAndOthersRaiseLikePsychDisallowedClass() {
        Object hwia = SettingValueCodec.decode(
            "--- !ruby/hash:ActiveSupport::HashWithIndifferentAccess\na: 1\n");
        assertThat(hwia).isInstanceOf(Map.class);
        assertThat(hwia).asInstanceOf(InstanceOfAssertFactories.MAP).containsEntry("a", 1);

        assertThatThrownBy(() -> SettingValueCodec.decode("--- !ruby/object:OpenStruct\ntable: {}\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Psych::DisallowedClass OpenStruct");
        assertThatThrownBy(() -> SettingValueCodec.decode("--- !ruby/object:Gem::Installer\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Gem::Installer");
        assertThatThrownBy(() -> SettingValueCodec.decode("- !ruby/object:Set\n  hash: {}\n"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isJsonDistinguishesStrictJsonFromYamlDocuments() {
        assertThat(SettingValueCodec.isJson("{\"a\":1}")).isTrue();
        assertThat(SettingValueCodec.isJson("[]")).isTrue();
        assertThat(SettingValueCodec.isJson("7")).isTrue();
        assertThat(SettingValueCodec.isJson("--- 7\n")).isFalse();
        assertThat(SettingValueCodec.isJson("--- {\"a\":1}\n")).isFalse();
        assertThat(SettingValueCodec.isJson("{\"a\":1} x")).isFalse();
        assertThat(SettingValueCodec.isJson(":planned")).isFalse();
    }

    @Test
    void encodeJsonRoundTripsDecodedYamlIncludingSymbolKeys() {
        Object decoded = SettingValueCodec.decode("---\n:server: x\n:nested:\n  :list:\n  - 1\n  - :b\n");
        String json = SettingValueCodec.encodeJson(decoded);
        assertThat(json).isEqualTo("{\":server\":\"x\",\":nested\":{\":list\":[1,\":b\"]}}");
        assertThat(SettingValueCodec.isJson(json)).isTrue();
        assertThat(SettingValueCodec.deepEquals(SettingValueCodec.decode(json), decoded)).isTrue();
        assertThat(SettingValueCodec.encodeJson(null)).isEqualTo("null");
    }

    @Test
    void deepEqualsComparesNumbersByValueAndKeysByString() {
        assertThat(SettingValueCodec.deepEquals(1, 1L)).isTrue();
        assertThat(SettingValueCodec.deepEquals(1.5, new BigDecimal("1.50"))).isTrue();
        assertThat(SettingValueCodec.deepEquals(1, 2)).isFalse();
        assertThat(SettingValueCodec.deepEquals(Map.of(1, "a"), Map.of("1", "a"))).isTrue();
        assertThat(SettingValueCodec.deepEquals(Map.of("a", List.of(1, 2)), Map.of("a", List.of(1L, 2L)))).isTrue();
        assertThat(SettingValueCodec.deepEquals(Map.of("a", 1), Map.of("a", 1, "b", 2))).isFalse();
        assertThat(SettingValueCodec.deepEquals(Map.of("a", 1), Map.of("b", 1))).isFalse();
        assertThat(SettingValueCodec.deepEquals(List.of(1, 2), List.of(2, 1))).isFalse();
        assertThat(SettingValueCodec.deepEquals(List.of(1), List.of(1, 1))).isFalse();
        assertThat(SettingValueCodec.deepEquals(Arrays.asList((Object) null), Arrays.asList((Object) null)))
            .isTrue();
        assertThat(SettingValueCodec.deepEquals(null, null)).isTrue();
        assertThat(SettingValueCodec.deepEquals(null, "x")).isFalse();
        assertThat(SettingValueCodec.deepEquals("x", null)).isFalse();
        assertThat(SettingValueCodec.deepEquals("1", 1)).isFalse();
        assertThat(SettingValueCodec.deepEquals(List.of(1), Map.of("0", 1))).isFalse();
    }
}
