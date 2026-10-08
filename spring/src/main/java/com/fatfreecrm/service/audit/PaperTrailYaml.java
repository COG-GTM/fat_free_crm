package com.fatfreecrm.service.audit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes {@code versions.object} / {@code versions.object_changes} in the exact YAML shape Ruby
 * Psych emits for PaperTrail: {@code ---} then {@code key: value} entries, {@code key:} for nil,
 * {@code ''} for empty strings, block-form {@code !ruby/object:ActiveSupport::TimeWithZone} values
 * with {@code utc}/{@code zone}/{@code time} members, and Psych anchors: objects referenced more
 * than once get {@code &N} at first use and {@code *N} later, numbered in document order. The
 * {@code ActiveSupport::TimeZone} instance is shared by every TimeWithZone in the document, and
 * repeated UTC {@link Instant} instances share anchors. The {@code time:} member is always literal.
 */
public final class PaperTrailYaml {

    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    // Scalars Psych would parse back as non-strings must be quoted.
    private static final Pattern IMPLICIT = Pattern.compile(
        "(?i)null|~|true|false|yes|no|on|off|[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?"
            + "|[-+]?\\.(inf|nan)|0x[0-9a-f]+|0o[0-7]+|\\d{4}-\\d{2}-\\d{2}.*");

    private PaperTrailYaml() {
    }

    /** Rails {@code version.object}: "---\n" plus every attribute in column order. */
    public static String dumpObject(Map<String, Object> attributes) {
        StringBuilder out = new StringBuilder("---\n");
        AnchorState anchors = preScan(values(attributes));
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            out.append(entry.getKey()).append(':');
            writeValue(out, entry.getValue(), " ", anchors);
            out.append('\n');
        }
        return out.toString();
    }

    /** Rails {@code version.object_changes}: "---\n" plus {@code key:\n- before\n- after} entries. */
    public static String dumpChanges(Map<String, Object[]> changes) {
        StringBuilder out = new StringBuilder("---\n");
        List<Object> values = new java.util.ArrayList<>();
        changes.values().forEach(pair -> java.util.Collections.addAll(values, pair));
        AnchorState anchors = preScan(values);
        for (Map.Entry<String, Object[]> entry : changes.entrySet()) {
            out.append(entry.getKey()).append(":\n");
            for (Object value : entry.getValue()) {
                if (value == null) {
                    out.append('-');
                } else {
                    out.append('-');
                    writeValue(out, value, " ", anchors);
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static List<Object> values(Map<String, Object> attributes) {
        return new java.util.ArrayList<>(attributes.values());
    }

    private static void writeValue(StringBuilder out, Object value, String prefix, AnchorState anchors) {
        if (value == null) {
            return;
        }
        switch (value) {
            case List<?> list -> {
                for (Object item : list) {
                    out.append('\n').append("- ").append(scalar(item));
                }
            }
            case BigDecimal decimal -> out.append(prefix).append(bigDecimal(decimal));
            case LocalDate date -> out.append(prefix).append(date);
            case Instant instant -> {
                out.append(prefix);
                writeTimeWithZone(out, instant, anchors);
            }
            case Boolean bool -> out.append(prefix).append(bool ? "true" : "false");
            case Number number -> out.append(prefix).append(number);
            default -> out.append(prefix).append(quote(value.toString()));
        }
    }

    private static String scalar(Object item) {
        if (item == null) {
            return "";
        }
        if (item instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        if (item instanceof BigDecimal decimal) {
            return bigDecimal(decimal);
        }
        if (item instanceof LocalDate date) {
            return date.toString();
        }
        if (item instanceof Number number) {
            return number.toString();
        }
        return quote(item.toString());
    }

    private static String bigDecimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.signum() == 0) {
            return "!ruby/object:BigDecimal 9:0.0";
        }
        String digits = normalized.unscaledValue().abs().toString();
        int exponent = digits.length() - normalized.scale();
        int precision = bigDecimalPrecision(normalized);
        return "!ruby/object:BigDecimal " + precision + ":"
            + (normalized.signum() < 0 ? "-0." : "0.") + digits + "e" + exponent;
    }

    private static int bigDecimalPrecision(BigDecimal value) {
        int digits = value.precision();
        int scale = value.scale();
        int words = scale > 0
            ? (Math.max(0, digits - scale) + 8) / 9 + (scale + 8) / 9
            : (digits + 8) / 9;
        return Math.max(1, words) * 9;
    }

    private static void writeTimeWithZone(StringBuilder out, Instant instant, AnchorState anchors) {
        out.append("!ruby/object:ActiveSupport::TimeWithZone\n  utc: ")
            .append(instantScalar(instant, anchors));
        out.append("\n  zone: ");
        String zoneAnchor = anchors.zoneAnchor();
        if (zoneAnchor.startsWith("*")) {
            out.append(zoneAnchor);
        } else {
            out.append(zoneAnchor.isEmpty() ? "" : zoneAnchor + " ");
            out.append("!ruby/object:ActiveSupport::TimeZone\n    name: Etc/UTC");
        }
        // Psych emits `time:` as a raw scalar inside the custom TWZ coder — always the literal,
        // never an anchor (verified live on a create where created_at == updated_at).
        out.append("\n  time: ").append(formatInstant(instant));
    }

    /** An already-emitted Instant serializes as the bare {@code *N} alias — no literal after it. */
    private static String instantScalar(Instant instant, AnchorState anchors) {
        String anchor = anchors.anchorFor(instant);
        if (anchor == null) {
            return formatInstant(instant);
        }
        return anchor.startsWith("*") ? anchor : anchor + " " + formatInstant(instant);
    }

    private static String formatInstant(Instant instant) {
        return TIMESTAMP.format(instant) + String.format(Locale.ROOT, ".%09d Z", instant.getNano());
    }

    static boolean needsQuoting(String text) {
        if (text.isEmpty()) {
            return true;
        }
        if (text.codePoints().anyMatch(Character::isSupplementaryCodePoint)) {
            return true;
        }
        if (!text.equals(text.trim()) || text.contains(": ") || text.contains(" #")
            || text.endsWith(":") || text.contains("\n") || text.contains("\t")) {
            return true;
        }
        char first = text.charAt(0);
        if ("!&*?{}[],#%@`|>\"'<-".indexOf(first) >= 0 || ": ".indexOf(first) >= 0) {
            return true;
        }
        return IMPLICIT.matcher(text).matches();
    }

    static String quote(String text) {
        if (text.isEmpty()) {
            return "''"; // Psych emits an empty string as ''
        }
        if (!needsQuoting(text)) {
            return text;
        }
        if (text.contains("\n")) {
            return "|-\n  " + text.replace("\n", "\n  ");
        }
        if (text.startsWith(" ")
            || text.startsWith("<")
            || text.codePoints().anyMatch(Character::isSupplementaryCodePoint)
            || text.codePoints().anyMatch(Character::isISOControl)) {
            StringBuilder quoted = new StringBuilder("\"");
            text.codePoints().forEach(codePoint -> {
                switch (codePoint) {
                    case '\\' -> quoted.append("\\\\");
                    case '"' -> quoted.append("\\\"");
                    case '\t' -> quoted.append("\\t");
                    case '\r' -> quoted.append("\\r");
                    default -> {
                        if (Character.isSupplementaryCodePoint(codePoint)) {
                            quoted.append(String.format(Locale.ROOT, "\\U%08X", codePoint));
                        } else {
                            quoted.appendCodePoint(codePoint);
                        }
                    }
                }
            });
            return quoted.append('"').toString();
        }
        return "'" + text.replace("'", "''") + "'";
    }

    /** Psych only anchors objects referenced more than once; scan the document up front. */
    private static AnchorState preScan(List<Object> values) {
        Map<Instant, int[]> counts = new IdentityHashMap<>();
        int zones = 0;
        for (Object value : values) {
            if (value instanceof Instant instant) {
                counts.computeIfAbsent(instant, ignored -> new int[1])[0]++;
                zones++;
            }
        }
        return new AnchorState(counts, zones);
    }

    private static final class AnchorState {
        private final Map<Instant, int[]> occurrences;
        private final Map<Instant, Integer> emitted = new IdentityHashMap<>();
        private final boolean zoneShared;
        private int count;
        private Integer zoneId;

        AnchorState(Map<Instant, int[]> occurrences, int zoneOccurrences) {
            this.occurrences = occurrences;
            this.zoneShared = zoneOccurrences > 1;
        }

        String anchorFor(Instant instant) {
            int[] total = occurrences.get(instant);
            if (total == null || total[0] < 2) {
                return null;
            }
            Integer prior = emitted.get(instant);
            if (prior != null) {
                return "*" + prior;
            }
            emitted.put(instant, ++count);
            return "&" + count;
        }

        /** Null when the zone object occurs once: Psych emits it literally, unanchored. */
        String zoneAnchor() {
            if (!zoneShared) {
                return "";
            }
            if (zoneId != null) {
                return "*" + zoneId;
            }
            zoneId = ++count;
            return "&" + zoneId;
        }
    }
}
