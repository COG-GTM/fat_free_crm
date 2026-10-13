package com.fatfreecrm.service.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.support.RailsYaml;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AB-273 (settings-i18n): decodes the Rails {@code settings.value} text column and encodes the
 * decoded model back to strict JSON for the AB-274 YAML-to-JSON conversion.
 *
 * <p>Rails serializes setting values with Psych ({@code serialize :value}); the stored text is
 * therefore normally a YAML document beginning with {@code ---}. Values seeded by other means can
 * be bare JSON objects/arrays, which Psych also parses. Decode rules: null in, null out; text whose
 * trimmed form starts with {@code ---} is read as Rails YAML via {@link RailsYaml#read}; otherwise a
 * strict JSON parse (no trailing tokens) is attempted and a JSON failure falls back to Rails YAML.
 *
 * <p>The JSON form of a Ruby symbol is the string {@code ":name"} — exactly what SnakeYAML yields —
 * so {@code decode(encodeJson(decode(yaml)))} deep-equals {@code decode(yaml)}. Map keys are kept as
 * decoded ({@code ":server"} stays {@code ":server"}) in JSON.
 */
public final class SettingValueCodec {

    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Ruby object tags Psych deserializes by class name ({@code !ruby/object:X} etc.). Rails only
     * permits the classes in {@code config.active_record.yaml_column_permitted_classes}
     * (config/application.rb:88-99); anything else raises Psych::DisallowedClass, so the codec
     * rejects it too. Class-less tags ({@code !ruby/sym}, {@code !ruby/regexp}) carry no class
     * name and stay tolerated.
     */
    private static final Pattern RUBY_TAGGED_CLASS = Pattern.compile("!ruby/\\w+:([\\w:]+)");
    private static final Set<String> PERMITTED_CLASSES = Set.of(
        "ActiveRecord::Type::Time::Value",
        "ActiveSupport::HashWithIndifferentAccess",
        "ActiveSupport::TimeWithZone",
        "ActiveSupport::TimeZone",
        "ActsAsTaggableOn::TagList",
        "ActsAsTaggableOn::DefaultParser",
        "BigDecimal",
        "Date",
        "Symbol",
        "Time");

    private SettingValueCodec() {
    }

    public static Object decode(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.stripLeading().startsWith("---")) {
            return readRailsYaml(raw);
        }
        try {
            return JSON.readValue(raw, Object.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return readRailsYaml(raw);
        }
    }

    private static Object readRailsYaml(String raw) {
        Matcher tags = RUBY_TAGGED_CLASS.matcher(raw);
        while (tags.find()) {
            if (!PERMITTED_CLASSES.contains(tags.group(1))) {
                throw new IllegalArgumentException("Psych::DisallowedClass " + tags.group(1));
            }
        }
        return RailsYaml.read(raw);
    }

    public static String encodeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot encode settings value as JSON", exception);
        }
    }

    /** True when {@code raw} is strict JSON (and not a {@code ---} YAML document). */
    public static boolean isJson(String raw) {
        if (raw == null || raw.stripLeading().startsWith("---")) {
            return false;
        }
        try {
            JSON.readValue(raw, Object.class);
            return true;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return false;
        }
    }

    /** Structural equality for the conversion guard; numbers compare by value, map keys by String.valueOf. */
    @SuppressWarnings("unchecked")
    public static boolean deepEquals(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof Map<?, ?> leftMap && right instanceof Map<?, ?> rightMap) {
            if (leftMap.size() != rightMap.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : leftMap.entrySet()) {
                Object rightValue = null;
                boolean found = false;
                for (Map.Entry<?, ?> candidate : rightMap.entrySet()) {
                    if (String.valueOf(candidate.getKey()).equals(String.valueOf(entry.getKey()))) {
                        rightValue = candidate.getValue();
                        found = true;
                        break;
                    }
                }
                if (!found || !deepEquals(entry.getValue(), rightValue)) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
            if (leftList.size() != rightList.size()) {
                return false;
            }
            for (int i = 0; i < leftList.size(); i++) {
                if (!deepEquals(leftList.get(i), rightList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return new BigDecimal(leftNumber.toString()).compareTo(new BigDecimal(rightNumber.toString())) == 0;
        }
        return left.equals(right);
    }
}
