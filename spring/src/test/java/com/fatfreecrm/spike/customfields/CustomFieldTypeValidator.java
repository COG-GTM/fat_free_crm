package com.fatfreecrm.spike.customfields;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Enforces Rails {@code cf_*} custom-field type semantics on a single JSONB
 * document. Mirrors the rules of {@code app/models/fields/custom_field.rb}
 * (required/minlength/maxlength), {@code custom_field_pair.rb} and
 * {@code custom_field_date_pair.rb} (endbeforestart), and the column types from
 * {@code Field::BASE_FIELD_TYPES} (string/text/select/radio_buttons/check_boxes/
 * boolean/date/datetime/decimal/integer/float plus date_pair/datetime_pair).
 */
public class CustomFieldTypeValidator {

    /** WRITE validates and normalizes incoming data; READ normalizes stored data without errors. */
    public enum Mode { WRITE, READ }

    private static final Set<String> STRING_TYPES =
        Set.of("string", "email", "url", "tel", "text");
    private static final Set<String> SINGLE_CHOICE_TYPES =
        Set.of("select", "radio_buttons");
    private static final Set<String> TRUE_WORDS = Set.of("1", "true", "t");
    private static final Set<String> FALSE_WORDS = Set.of("0", "false", "f");
    private static final DateTimeFormatter DATETIME_OUT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    /**
     * Validates {@code input} (Jackson-parsed values) against {@code fields}.
     * In READ mode no required/collection/length errors are produced and a
     * type-conversion failure keeps the raw value instead of failing.
     */
    public ValidationResult validate(List<FieldDefinition> fields, Map<String, Object> input, Mode mode) {
        Map<String, FieldDefinition> byName =
            fields.stream().collect(Collectors.toMap(FieldDefinition::name, Function.identity()));
        Map<Long, FieldDefinition> byId =
            fields.stream().filter(f -> f.id() != null)
                .collect(Collectors.toMap(FieldDefinition::id, Function.identity()));
        Map<String, Object> normalized = new LinkedHashMap<>();
        Map<String, List<String>> errors = new LinkedHashMap<>();

        for (Map.Entry<String, Object> e : input.entrySet()) {
            if (!byName.containsKey(e.getKey())) {
                if (mode == Mode.WRITE) {
                    addError(errors, e.getKey(), e.getKey() + " is not a defined custom field.");
                }
                // READ: unknown keys are dropped from the normalized map.
            }
        }

        Map<String, Object> normalizedByName = new LinkedHashMap<>();
        for (FieldDefinition field : fields) {
            boolean present = input.containsKey(field.name());
            Object raw = input.get(field.name());
            Object value;
            if (present) {
                value = normalizeValue(field, raw, mode, errors);
            } else {
                // WRITE: absent behaves like null (required check); READ: skip absent keys.
                if (mode == Mode.READ) {
                    continue;
                }
                value = null;
            }
            normalizedByName.put(field.name(), value);

            boolean required = effectiveRequired(field, byId);
            if (mode == Mode.WRITE && required && isBlank(value)) {
                // Rails quirk: required uses blank? semantics, and false is blank,
                // so a required boolean can never be submitted false. Parity.
                addError(errors, field.name(), field.label() + " is required.");
            }
            if (mode == Mode.WRITE && value instanceof CharSequence s) {
                int len = (int) s.codePoints().count();
                if (field.minlength() != null && field.minlength() > 0 && len < field.minlength()) {
                    addError(errors, field.name(), field.label() + " is too short.");
                }
                if (field.maxlength() != null && field.maxlength() > 0 && len > field.maxlength()) {
                    addError(errors, field.name(), field.label() + " is too long.");
                }
            }
            if (present || mode == Mode.WRITE) {
                normalized.put(field.name(), value);
            }
        }

        // pair check: both halves present and end < start -> error on the end field
        for (FieldDefinition field : fields) {
            if (field.pairId() == null) {
                continue;
            }
            FieldDefinition start = byId.get(field.pairId());
            if (start == null) {
                continue;
            }
            Object from = normalizedByName.get(start.name());
            Object to = normalizedByName.get(field.name());
            if (from != null && to != null && isBefore(to, from)) {
                addError(errors, field.name(), start.label() + " cannot end before it begins.");
            }
        }

        return new ValidationResult(normalized, errors);
    }

    private static boolean effectiveRequired(FieldDefinition field, Map<Long, FieldDefinition> byId) {
        // the end half of a pair copies required from the start half
        if (field.pairId() != null) {
            FieldDefinition start = byId.get(field.pairId());
            if (start != null) {
                return start.required();
            }
        }
        return field.required();
    }

    private static boolean isBefore(Object to, Object from) {
        if (to instanceof Comparable<?> tc && from instanceof Comparable<?> fc) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            int cmp = ((Comparable) tc).compareTo(fc);
            return cmp < 0;
        }
        return to.toString().compareTo(from.toString()) < 0;
    }

    private static Object normalizeValue(FieldDefinition field, Object raw, Mode mode,
        Map<String, List<String>> errors) {
        String as = switch (field.as()) {
            case "date_pair" -> "date";
            case "datetime_pair" -> "datetime";
            default -> field.as();
        };
        try {
            return normalize(as, field, raw, mode);
        } catch (FieldConversionException ex) {
            if (mode == Mode.READ) {
                return raw; // stored data we cannot coerce: keep it, don't fail
            }
            addError(errors, field.name(), String.format(ex.getMessage(), field.label()));
            return null;
        }
    }

    private static Object normalize(String as, FieldDefinition field, Object raw, Mode mode)
        throws FieldConversionException {
        if (raw == null) {
            return null;
        }
        if (STRING_TYPES.contains(as)) {
            if (raw instanceof CharSequence s) {
                return s.toString();
            }
            throw new FieldConversionException("%s is not a string.");
        }
        if (SINGLE_CHOICE_TYPES.contains(as)) {
            if (!(raw instanceof CharSequence s)) {
                throw new FieldConversionException("%s is not a string.");
            }
            String v = s.toString();
            if (mode == Mode.WRITE && field.collection() != null && !field.collection().isEmpty()
                && !field.collection().contains(v)) {
                throw new FieldConversionException("%s is not included in the list.");
            }
            return v;
        }
        switch (as) {
            case "check_boxes":
                return normalizeCheckBoxes(field, raw, mode);
            case "boolean":
                return normalizeBoolean(raw);
            case "date":
                return normalizeDate(raw, mode);
            case "datetime":
                return normalizeDateTime(raw, mode);
            case "decimal":
                return normalizeDecimal(raw);
            case "integer":
                return normalizeInteger(raw);
            case "float":
                return normalizeFloat(raw);
            default:
                return raw;
        }
    }

    private static Object normalizeCheckBoxes(FieldDefinition field, Object raw, Mode mode)
        throws FieldConversionException {
        List<?> items;
        if (raw instanceof List<?> list) {
            items = list;
        } else if (raw instanceof CharSequence s) {
            items = List.of(s.toString());
        } else {
            throw new FieldConversionException("%s is not a list.");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : items) {
            if (item == null) {
                continue;
            }
            String v = item.toString();
            if (v.isBlank()) {
                continue; // blanks dropped
            }
            seen.add(v); // duplicates removed, order preserved
        }
        if (mode == Mode.WRITE && field.collection() != null && !field.collection().isEmpty()) {
            for (String v : seen) {
                if (!field.collection().contains(v)) {
                    throw new FieldConversionException("%s is not included in the list.");
                }
            }
        }
        return new ArrayList<>(seen);
    }

    private static Object normalizeBoolean(Object raw) throws FieldConversionException {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof CharSequence s) {
            String v = s.toString().trim().toLowerCase();
            if (TRUE_WORDS.contains(v)) {
                return true;
            }
            if (FALSE_WORDS.contains(v)) {
                return false;
            }
        }
        throw new FieldConversionException("%s is not a boolean.");
    }

    private static Object normalizeDate(Object raw, Mode mode) throws FieldConversionException {
        if (!(raw instanceof CharSequence s)) {
            throw new FieldConversionException("%s is not a valid date.");
        }
        String v = s.toString().trim();
        try {
            return LocalDate.parse(v).toString();
        } catch (DateTimeParseException e) {
            // READ only: accept an ISO datetime stored by a former datetime column and truncate,
            // matching the safe Rails date <-> timestamp column transition.
            if (mode == Mode.READ) {
                try {
                    return LocalDateTime.parse(v).toLocalDate().toString();
                } catch (DateTimeParseException e2) {
                    try {
                        return OffsetDateTime.parse(v).toLocalDate().toString();
                    } catch (DateTimeParseException e3) {
                        // fall through to error
                    }
                }
            }
            throw new FieldConversionException("%s is not a valid date.");
        }
    }

    private static Object normalizeDateTime(Object raw, Mode mode) throws FieldConversionException {
        if (!(raw instanceof CharSequence s)) {
            throw new FieldConversionException("%s is not a valid datetime.");
        }
        String v = s.toString().trim();
        try {
            LocalDateTime utc;
            if (v.endsWith("Z") || v.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                utc = OffsetDateTime.parse(v).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            } else if (v.length() == 10) {
                // bare date: READ accepts it as midnight UTC (former date column)
                if (mode != Mode.READ) {
                    throw new DateTimeParseException("date only", v, 0);
                }
                utc = LocalDate.parse(v).atStartOfDay();
            } else {
                // no offset: treated as UTC, like Rails timestamp-without-time-zone
                utc = LocalDateTime.parse(v);
            }
            return utc.format(DATETIME_OUT);
        } catch (DateTimeParseException e) {
            throw new FieldConversionException("%s is not a valid datetime.");
        }
    }

    private static Object normalizeDecimal(Object raw) throws FieldConversionException {
        BigDecimal bd = toBigDecimal(raw);
        if (bd == null) {
            throw new FieldConversionException("%s is not a number.");
        }
        BigDecimal rounded = bd.setScale(2, RoundingMode.HALF_UP); // PG numeric(15,2) rounds
        if (rounded.abs().precision() - rounded.abs().scale() > 13) {
            throw new FieldConversionException("%s is out of range.");
        }
        return rounded;
    }

    private static Object normalizeInteger(Object raw) throws FieldConversionException {
        BigDecimal bd = toBigDecimal(raw);
        if (bd == null) {
            throw new FieldConversionException("%s is not a number.");
        }
        if (bd.stripTrailingZeros().scale() > 0) {
            // stricter than Rails, which truncates fractional values into an int column
            throw new FieldConversionException("%s must be an integer.");
        }
        BigDecimal v = bd.stripTrailingZeros();
        if (v.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0
            || v.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0) {
            throw new FieldConversionException("%s is out of range."); // PG integer is int32
        }
        return v.intValueExact();
    }

    private static Object normalizeFloat(Object raw) throws FieldConversionException {
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new FieldConversionException("%s is not a number.");
            }
            return d;
        }
        if (raw instanceof CharSequence s) {
            try {
                double d = Double.parseDouble(s.toString().trim());
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new FieldConversionException("%s is not a number.");
                }
                return d;
            } catch (NumberFormatException e) {
                throw new FieldConversionException("%s is not a number.");
            }
        }
        throw new FieldConversionException("%s is not a number.");
    }

    private static BigDecimal toBigDecimal(Object raw) {
        if (raw instanceof BigDecimal bd) {
            return bd;
        }
        if (raw instanceof Number n) {
            if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return null;
                }
            }
            return new BigDecimal(n.toString());
        }
        if (raw instanceof CharSequence s) {
            try {
                return new BigDecimal(s.toString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Rails blank? parity: null, "", whitespace-only, empty list, and false are all blank. */
    static boolean isBlank(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence s) {
            return s.toString().isBlank();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        if (value instanceof Boolean b) {
            return !b; // Rails quirk: false counts as blank for required checks
        }
        return false;
    }

    private static void addError(Map<String, List<String>> errors, String field, String message) {
        errors.computeIfAbsent(field, k -> new ArrayList<>()).add(message);
    }

    private static final class FieldConversionException extends Exception {
        private static final long serialVersionUID = 1L;

        FieldConversionException(String messageTemplate) {
            super(messageTemplate);
        }
    }
}
