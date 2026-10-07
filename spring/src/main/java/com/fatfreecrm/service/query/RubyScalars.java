package com.fatfreecrm.service.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Coercions mirroring Ruby {@code to_i}/{@code to_d}/ActiveModel casting and Rails/Chronic noon dates. */
public final class RubyScalars {

    private static final Pattern RUBY_INT = Pattern.compile("^\\s*([+-]?)(\\d[\\d_]*)");
    private static final Pattern RUBY_DECIMAL = Pattern.compile("^\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern ISO_DATE_TIME = Pattern.compile(
        "^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,3})?)?(Z|[+-]\\d{2}:\\d{2})?$");

    public static final Set<String> TRUE_VALUES = Set.of("1", "t", "T", "true", "TRUE");
    public static final Set<String> FALSE_VALUES = Set.of("0", "f", "F", "false", "FALSE");

    private RubyScalars() {
    }

    /** Ruby {@code String#to_i}: leading whitespace, optional sign, digits ('_' allowed), else 0. */
    public static long toLong(String value) {
        if (value == null) {
            return 0;
        }
        Matcher matcher = RUBY_INT.matcher(value);
        if (!matcher.find()) {
            return 0;
        }
        String digits = matcher.group(2).replace("_", "");
        try {
            return Long.parseLong(matcher.group(1) + digits);
        } catch (NumberFormatException exception) {
            return "-".equals(matcher.group(1)) ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    /** Ruby {@code to_d}/{@code to_f}: leading numeric run, else 0. */
    public static BigDecimal toDecimal(String value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        Matcher matcher = RUBY_DECIMAL.matcher(value);
        if (!matcher.find()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(matcher.group(1));
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    /** ActiveModel boolean cast: {@code 0/f/F/false/FALSE/off/OFF} are false, everything else is true. */
    public static boolean toBoolean(String value) {
        return !Set.of("0", "f", "F", "false", "FALSE", "off", "OFF").contains(value);
    }

    /** Rails date cast: strict ISO {@code yyyy-MM-dd}, otherwise uncastable. */
    public static LocalDate toLocalDate(String value) {
        if (value == null || !ISO_DATE.matcher(value).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /**
     * Rails datetime cast: a bare ISO date is noon UTC (Chronic semantics);
     * an ISO date-time is exact (no zone means UTC); anything else is uncastable.
     */
    public static Instant toInstant(String value) {
        if (value == null) {
            return null;
        }
        if (ISO_DATE.matcher(value).matches()) {
            try {
                return LocalDate.parse(value).atTime(12, 0).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException exception) {
                return null;
            }
        }
        if (!ISO_DATE_TIME.matcher(value).matches()) {
            return null;
        }
        String normalized = value.replace(' ', 'T');
        try {
            if (normalized.endsWith("Z")) {
                return java.time.OffsetDateTime.parse(normalized).toInstant();
            }
            if (normalized.matches(".*[+-]\\d{2}:\\d{2}$")) {
                return java.time.OffsetDateTime.parse(normalized).toInstant();
            }
            return LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
