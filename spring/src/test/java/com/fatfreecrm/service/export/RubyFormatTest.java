package com.fatfreecrm.service.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link RubyFormat} to the strings Ruby 4.0.2 + ActiveSupport produce for the same values
 * ({@code BigDecimal#to_s}, {@code Float#to_s}, {@code Array#inspect}, {@code Time#to_s}), as captured
 * with {@code bundle exec ruby} against the Rails app.
 */
class RubyFormatTest {

    @Test
    void nullStaysNull() {
        assertThat(RubyFormat.toS(null)).isNull();
    }

    @Test
    void bigDecimalUsesActiveSupportPlainNotationWithOneFractionalDigit() {
        assertThat(RubyFormat.toS(new BigDecimal("1500.75"))).isEqualTo("1500.75");
        assertThat(RubyFormat.toS(new BigDecimal("100"))).isEqualTo("100.0");
        assertThat(RubyFormat.toS(new BigDecimal("100.00"))).isEqualTo("100.0");
        assertThat(RubyFormat.toS(new BigDecimal("0.5"))).isEqualTo("0.5");
        assertThat(RubyFormat.toS(new BigDecimal("-0.001"))).isEqualTo("-0.001");
        assertThat(RubyFormat.toS(new BigDecimal("0"))).isEqualTo("0.0");
        assertThat(RubyFormat.toS(new BigDecimal("1E+3"))).isEqualTo("1000.0");
        assertThat(RubyFormat.toS(new BigDecimal("0.000001"))).isEqualTo("0.000001");
        assertThat(RubyFormat.toS(new BigDecimal("-25"))).isEqualTo("-25.0");
        assertThat(RubyFormat.toS(new BigDecimal("12345678901234567890.5"))).isEqualTo("12345678901234567890.5");
    }

    @Test
    void floatsMatchRubyFloatToSInTheFixedRange() {
        assertThat(RubyFormat.toS(1.0)).isEqualTo("1.0");
        assertThat(RubyFormat.toS(0.1)).isEqualTo("0.1");
        assertThat(RubyFormat.toS(100.0)).isEqualTo("100.0");
        assertThat(RubyFormat.toS(1234.5678)).isEqualTo("1234.5678");
        assertThat(RubyFormat.toS(0.001)).isEqualTo("0.001");
        assertThat(RubyFormat.toS(0.0001)).isEqualTo("0.0001");
        assertThat(RubyFormat.toS(123456789.123)).isEqualTo("123456789.123");
        assertThat(RubyFormat.toS(100000000000000.0)).isEqualTo("100000000000000.0");
        assertThat(RubyFormat.toS(0.0)).isEqualTo("0.0");
        assertThat(RubyFormat.toS(2.5f)).isEqualTo("2.5");
    }

    @Test
    void floatsMatchRubyExponentNotationOutsideTheFixedRange() {
        assertThat(RubyFormat.toS(1e16)).isEqualTo("1.0e+16");
        assertThat(RubyFormat.toS(-1e20)).isEqualTo("-1.0e+20");
        assertThat(RubyFormat.toS(2.5e22)).isEqualTo("2.5e+22");
        assertThat(RubyFormat.toS(1e100)).isEqualTo("1.0e+100");
        assertThat(RubyFormat.toS(0.00001)).isEqualTo("1.0e-05");
        assertThat(RubyFormat.toS(9.9e-5)).isEqualTo("9.9e-05");
        assertThat(RubyFormat.toS(1.5e-7)).isEqualTo("1.5e-07");
    }

    @Test
    void nonFiniteFloatsUseRubyNames() {
        assertThat(RubyFormat.toS(Double.NaN)).isEqualTo("NaN");
        assertThat(RubyFormat.toS(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
        assertThat(RubyFormat.toS(Double.NEGATIVE_INFINITY)).isEqualTo("-Infinity");
    }

    @Test
    void integersAndBooleansUseToString() {
        assertThat(RubyFormat.toS(42)).isEqualTo("42");
        assertThat(RubyFormat.toS(42L)).isEqualTo("42");
        assertThat(RubyFormat.toS(Boolean.TRUE)).isEqualTo("true");
        assertThat(RubyFormat.toS("plain")).isEqualTo("plain");
    }

    @Test
    void timesRenderAsActiveSupportUtcToS() {
        LocalDateTime at = LocalDateTime.of(2024, 3, 5, 14, 7, 9);
        assertThat(RubyFormat.toS(at)).isEqualTo("2024-03-05 14:07:09 UTC");
        assertThat(RubyFormat.toS(Timestamp.valueOf(at))).isEqualTo("2024-03-05 14:07:09 UTC");
        assertThat(RubyFormat.toS(OffsetDateTime.of(at, ZoneOffset.ofHours(2))))
            .isEqualTo("2024-03-05 12:07:09 UTC");
        assertThat(RubyFormat.toS(Timestamp.valueOf(at.withNano(123_456_000))))
            .isEqualTo("2024-03-05 14:07:09 UTC");
    }

    @Test
    void datesRenderAsIsoDate() {
        assertThat(RubyFormat.toS(LocalDate.of(2024, 3, 5))).isEqualTo("2024-03-05");
        assertThat(RubyFormat.toS(Date.valueOf(LocalDate.of(2024, 3, 5)))).isEqualTo("2024-03-05");
    }

    @Test
    void listsRenderAsRubyArrayInspect() {
        assertThat(RubyFormat.toS(List.of())).isEqualTo("[]");
        assertThat(RubyFormat.toS(Arrays.asList("a", null))).isEqualTo("[\"a\", nil]");
        assertThat(RubyFormat.toS(List.of(1, 2.5, true, false))).isEqualTo("[1, 2.5, true, false]");
        assertThat(RubyFormat.toS(List.of("q\"x", "back\\slash", "nl\n", "tab\t", "cr\r", "#x", "é")))
            .isEqualTo("[\"q\\\"x\", \"back\\\\slash\", \"nl\\n\", \"tab\\t\", \"cr\\r\", \"#x\", \"é\"]");
    }

    @Test
    void numericMirrorsRespondToAbs() {
        assertThat(RubyFormat.numeric(1)).isTrue();
        assertThat(RubyFormat.numeric(new BigDecimal("1.5"))).isTrue();
        assertThat(RubyFormat.numeric(2.5)).isTrue();
        assertThat(RubyFormat.numeric("1")).isFalse();
        assertThat(RubyFormat.numeric(null)).isFalse();
        assertThat(RubyFormat.numeric(Boolean.TRUE)).isFalse();
    }
}
