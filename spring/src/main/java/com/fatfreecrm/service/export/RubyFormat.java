package com.fatfreecrm.service.export;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Ruby/ActiveSupport {@code to_s} for the values Rails exports (lib/fat_free_crm/export_csv.rb, *.xls.builder). */
public final class RubyFormat {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");

    private RubyFormat() {
    }

    /** {@code value.respond_to?(:abs)}: Integer, BigDecimal and Float cells are SpreadsheetML Numbers. */
    public static boolean numeric(Object value) {
        return value instanceof Number;
    }

    public static String toS(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return bigDecimal(decimal);
        }
        if (value instanceof Double || value instanceof Float) {
            return rubyFloat(((Number) value).doubleValue());
        }
        if (value instanceof Timestamp timestamp) {
            return TIME.format(timestamp.toLocalDateTime());
        }
        if (value instanceof LocalDateTime dateTime) {
            return TIME.format(dateTime);
        }
        if (value instanceof OffsetDateTime dateTime) {
            return TIME.format(dateTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        }
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof List<?> list) {
            return inspect(list);
        }
        return value.toString();
    }

    /** ActiveSupport {@code BigDecimal#to_s}: plain notation, always one fractional digit. */
    static String bigDecimal(BigDecimal decimal) {
        String plain = decimal.stripTrailingZeros().toPlainString();
        return plain.contains(".") ? plain : plain + ".0";
    }

    /** Ruby {@code Float#to_s} for the ranges CRM amounts use. */
    static String rubyFloat(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        double magnitude = Math.abs(value);
        if (magnitude != 0 && (magnitude >= 1e16 || magnitude < 1e-4)) {
            String java = Double.toString(value);
            int exponent = java.indexOf('E');
            String mantissa = java.substring(0, exponent);
            String power = java.substring(exponent + 1);
            String sign = power.startsWith("-") ? "-" : "+";
            String digits = power.replace("-", "");
            return mantissa + "e" + sign + (digits.length() == 1 ? "0" + digits : digits);
        }
        return bigDecimal(new BigDecimal(Double.toString(value)));
    }

    /** Ruby {@code Array#inspect} of a YAML-serialized array. */
    static String inspect(List<?> list) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < list.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            Object item = list.get(index);
            if (item == null) {
                builder.append("nil");
            } else if (item instanceof Number || item instanceof Boolean) {
                builder.append(toS(item));
            } else {
                builder.append('"');
                for (char character : item.toString().toCharArray()) {
                    switch (character) {
                        case '"' -> builder.append("\\\"");
                        case '\\' -> builder.append("\\\\");
                        case '\n' -> builder.append("\\n");
                        case '\t' -> builder.append("\\t");
                        case '\r' -> builder.append("\\r");
                        case '#' -> builder.append('#');
                        default -> builder.append(character);
                    }
                }
                builder.append('"');
            }
        }
        return builder.append(']').toString();
    }
}
