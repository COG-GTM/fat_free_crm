package com.fatfreecrm.customfields;

import java.util.ArrayList;
import java.util.List;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads and writes the Psych (Ruby YAML) documents Rails stores in
 * {@code check_boxes} cf_* columns ({@code serialize(name, type: Array)}).
 */
@Component
public final class CheckBoxesYamlCodec {

    private final Yaml reader = new Yaml(new SafeConstructor(new LoaderOptions()));

    /**
     * Decodes a YAML array into a list of strings. Null/blank input yields an
     * empty list; non-string scalars are stringified (e.g. {@code 1} -> "1").
     * Anything that isn't a sequence is rejected.
     */
    public List<String> decode(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return List.of();
        }
        Object parsed = reader.load(yaml);
        if (parsed == null) {
            return List.of();
        }
        if (!(parsed instanceof List<?> list)) {
            throw new IllegalArgumentException("check_boxes YAML is not a sequence: " + yaml);
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            out.add(item == null ? null : item.toString());
        }
        return out;
    }

    /**
     * Encodes a list of strings as a Psych-readable YAML sequence. Double-quoted
     * scalars are always safe for arbitrary string content.
     */
    public String encode(List<String> values) {
        StringBuilder sb = new StringBuilder("---\n");
        for (String v : values) {
            sb.append("- ").append(quote(v)).append('\n');
        }
        if (values.isEmpty()) {
            return "--- []\n";
        }
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
