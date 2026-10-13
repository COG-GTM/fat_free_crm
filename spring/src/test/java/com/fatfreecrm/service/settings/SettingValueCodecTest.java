package com.fatfreecrm.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Rails {@code Setting} column codec: Psych YAML in, strict JSON out, structural equality for the migration check. */
class SettingValueCodecTest {

    @Test
    void decodesRailsYamlScalarsCollectionsAndKeepsSymbolPrefixes() {
        assertThat(SettingValueCodec.decode("--- foo\n")).isEqualTo("foo");
        assertThat(SettingValueCodec.decode("--- 42\n")).isEqualTo(42);
        assertThat(SettingValueCodec.decode("--- true\n")).isEqualTo(true);
        assertThat(SettingValueCodec.decode("--- \n...\n")).isNull();
        assertThat(SettingValueCodec.decode("---\n- :one\n- :two\n")).isEqualTo(List.of(":one", ":two"));
        assertThat(SettingValueCodec.decode("---\n:key: value\nnested:\n  :inner: 1\n"))
            .isEqualTo(Map.of(":key", "value", "nested", Map.of(":inner", 1)));
    }

    @Test
    void decodesStrictJsonAndIdentifiesIt() {
        assertThat(SettingValueCodec.isJson("{\"a\":[1,2]}")).isTrue();
        assertThat(SettingValueCodec.isJson("  [1, 2]  ")).isTrue();
        assertThat(SettingValueCodec.isJson("--- foo\n")).isFalse();
        assertThat(SettingValueCodec.isJson(null)).isFalse();
        assertThat(SettingValueCodec.isJson("")).isFalse();
        Object decoded = SettingValueCodec.decode("{\"a\":[1,2],\"b\":null}");
        assertThat(decoded).isInstanceOf(Map.class);
        Map<String, Object> expected = new java.util.HashMap<>();
        expected.put("a", List.of(1, 2));
        expected.put("b", null);
        assertThat(decoded).isEqualTo(expected);
    }

    @Test
    void nullAndBlankValuesDecodeToNull() {
        assertThat(SettingValueCodec.decode(null)).isNull();
        assertThat(SettingValueCodec.decode("")).isNull();
        assertThat(SettingValueCodec.decode("   ")).isNull();
    }

    @Test
    void unpermittedRubyObjectClassesAreRejectedLikePsychDisallowedClass() {
        assertThatThrownBy(() -> SettingValueCodec.decode("--- !ruby/object:OpenStruct\ntable: {}\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Psych::DisallowedClass")
            .hasMessageContaining("OpenStruct");
        assertThatThrownBy(() -> SettingValueCodec.decode("--- !ruby/hash:Some::CustomHash\na: 1\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Some::CustomHash");
    }

    @Test
    void encodesJsonWithStableKeyOrderAndRoundTrips() {
        Object decoded = SettingValueCodec.decode("---\n:b: 2\n:a:\n- x\n- 1.5\n");

        String json = SettingValueCodec.encodeJson(decoded);

        assertThat(json).isEqualTo("{\":b\":2,\":a\":[\"x\",1.5]}");
        assertThat(SettingValueCodec.deepEquals(SettingValueCodec.decode(json), decoded)).isTrue();
        assertThat(SettingValueCodec.encodeJson(null)).isEqualTo("null");
    }

    @Test
    void deepEqualsIsStructuralAndNumericallyTolerantOfIntegerVersusDecimal() {
        assertThat(SettingValueCodec.deepEquals(Map.of("a", List.of(1, "x")), Map.of("a", List.of(1L, "x")))).isTrue();
        assertThat(SettingValueCodec.deepEquals(1, 1.0)).isTrue();
        assertThat(SettingValueCodec.deepEquals(null, null)).isTrue();
        assertThat(SettingValueCodec.deepEquals(null, "x")).isFalse();
        assertThat(SettingValueCodec.deepEquals(Map.of("a", 1), Map.of("a", 2))).isFalse();
        assertThat(SettingValueCodec.deepEquals(List.of(1, 2), List.of(2, 1))).isFalse();
        assertThat(SettingValueCodec.deepEquals(Map.of("a", 1), Map.of("a", 1, "b", 2))).isFalse();
    }
}
