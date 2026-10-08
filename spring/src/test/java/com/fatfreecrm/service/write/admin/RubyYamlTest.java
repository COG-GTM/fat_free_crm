package com.fatfreecrm.service.write.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link RubyYaml#dump} to the bytes Psych writes for {@code Setting[key] = value} and
 * {@code fields.collection}/{@code fields.settings} (Rails {@code serialize}, Ruby 4 / Psych 5).
 */
class RubyYamlTest {

    private static final String HWIA = "--- !ruby/hash:ActiveSupport::HashWithIndifferentAccess";

    @Test
    void scalarsMatchPsychToYaml() {
        assertThat(RubyYaml.dump("crm.example")).isEqualTo("--- crm.example\n");
        assertThat(RubyYaml.dump("")).isEqualTo("--- ''\n");
        assertThat(RubyYaml.dump("1")).isEqualTo("--- '1'\n");
        assertThat(RubyYaml.dump("true")).isEqualTo("--- 'true'\n");
        assertThat(RubyYaml.dump(true)).isEqualTo("--- true\n");
        assertThat(RubyYaml.dump(false)).isEqualTo("--- false\n");
        assertThat(RubyYaml.dump(BigInteger.valueOf(25))).isEqualTo("--- 25\n");
        assertThat(RubyYaml.dump(1.5d)).isEqualTo("--- 1.5\n");
        assertThat(RubyYaml.dump(null)).isEqualTo("---\n");
    }

    @Test
    void symbolsSerializeWithLeadingColonLikeSettingUserSignup() {
        assertThat(RubyYaml.dump(new RubyYaml.Symbol("needs_approval"))).isEqualTo("--- :needs_approval\n");
    }

    @Test
    void arraysMatchPsychIncludingEmptyStringsAndEmptyArrays() {
        assertThat(RubyYaml.dump(List.of())).isEqualTo("--- []\n");
        assertThat(RubyYaml.dump(Arrays.asList("new", "", "contacted", null)))
            .isEqualTo("---\n- new\n- ''\n- contacted\n-\n");
    }

    @Test
    void mapsAreTaggedAsHashWithIndifferentAccessAndKeepInsertionOrder() {
        assertThat(RubyYaml.dump(Map.of())).isEqualTo(HWIA + " {}\n");

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("ssl", true);
        nested.put("aliases", List.of("x@y.z"));
        nested.put("none", List.of());
        nested.put("empty", Map.of());
        Map<String, Object> smtp = new LinkedHashMap<>();
        smtp.put("address", null);
        smtp.put("port", BigInteger.valueOf(25));
        smtp.put("enable_starttls_auto", "1");
        smtp.put("nested", nested);

        assertThat(RubyYaml.dump(smtp)).isEqualTo(HWIA + "\n"
            + "address:\n"
            + "port: 25\n"
            + "enable_starttls_auto: '1'\n"
            + "nested: !ruby/hash:ActiveSupport::HashWithIndifferentAccess\n"
            + "  ssl: true\n"
            + "  aliases:\n"
            + "  - x@y.z\n"
            + "  none: []\n"
            + "  empty: !ruby/hash:ActiveSupport::HashWithIndifferentAccess {}\n");
    }

    @Test
    void multiLineStringsUseLiteralBlockScalars() {
        Map<String, Object> prompts = new LinkedHashMap<>();
        prompts.put("about_my_business", "line one\nline two");
        assertThat(RubyYaml.dump(prompts))
            .isEqualTo(HWIA + "\nabout_my_business: |-\n  line one\n  line two\n");
    }

    @Test
    void psychQuotePrefersSingleQuotesUnlessAnEscapeIsNeeded() {
        assertThat(RubyYaml.psychQuote("1")).isEqualTo("'1'");
        assertThat(RubyYaml.psychQuote("")).isEqualTo("''");
        assertThat(RubyYaml.psychQuote("plain text")).isEqualTo("plain text");
        assertThat(RubyYaml.psychQuote("a\tb")).isEqualTo("\"a\\tb\"");
    }

    @Test
    void fromJsonMirrorsActionControllerParametersValueTypes() throws Exception {
        JsonNode node = new ObjectMapper().readTree(
            "{\"host\":\"crm.example\",\"port\":25,\"stage\":1.5,\"flag\":true,\"none\":null,"
                + "\"list\":[\"a\",null,2],\"nested\":{\"z\":\"1\",\"a\":\"2\"}}");

        Object value = RubyYaml.fromJson(node);

        assertThat(value).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) value;
        assertThat(new ArrayList<Object>(map.keySet()))
            .containsExactly("host", "port", "stage", "flag", "none", "list", "nested");
        assertThat(map.get("host")).isEqualTo("crm.example");
        assertThat(map.get("port")).isEqualTo(BigInteger.valueOf(25));
        assertThat(map.get("stage")).isEqualTo(1.5d);
        assertThat(map.get("flag")).isEqualTo(true);
        assertThat(map.get("none")).isNull();
        assertThat(map.get("list")).isEqualTo(Arrays.asList("a", null, BigInteger.valueOf(2)));
        assertThat(new ArrayList<Object>(((Map<?, ?>) map.get("nested")).keySet())).containsExactly("z", "a");
        assertThat(RubyYaml.fromJson(null)).isNull();
    }
}
