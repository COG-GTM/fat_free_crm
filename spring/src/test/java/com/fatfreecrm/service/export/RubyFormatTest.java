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

/** Pins {@link RubyFormat} to Ruby {@code to_s} / {@code inspect} for the values Rails exports. */
class RubyFormatTest {

    @Test
    void nilIsNullAndBooleansAreRubyWords() {
        assertThat(RubyFormat.toS(null)).isNull();
        assertThat(RubyFormat.toS(true)).isEqualTo("true");
        assertThat(RubyFormat.toS(false)).isEqualTo("false");
        assertThat(RubyFormat.toS("plain")).isEqualTo("plain");
        assertThat(RubyFormat.toS(42L)).isEqualTo("42");
    }

    @Test
    void bigDecimalUsesPlainNotationWithOneFractionalDigit() {
        assertThat(RubyFormat.toS(new BigDecimal("1000.50"))).isEqualTo("1000.5");
        assertThat(RubyFormat.toS(new BigDecimal("0.00"))).isEqualTo("0.0");
        assertThat(RubyFormat.toS(new BigDecimal("1E+3"))).isEqualTo("1000.0");
        assertThat(RubyFormat.toS(new BigDecimal("-12.340"))).isEqualTo("-12.34");
        assertThat(RubyFormat.toS(new BigDecimal("123456789012345678.9"))).isEqualTo("123456789012345678.9");
    }

    @Test
    void floatsFollowRubyFloatToS() {
        assertThat(RubyFormat.toS(1.0d)).isEqualTo("1.0");
        assertThat(RubyFormat.toS(0.5f)).isEqualTo("0.5");
        assertThat(RubyFormat.toS(0.0d)).isEqualTo("0.0");
        assertThat(RubyFormat.toS(1e16)).isEqualTo("1.0e+16");
        assertThat(RubyFormat.toS(1e-5)).isEqualTo("1.0e-05");
        assertThat(RubyFormat.toS(0.0001)).isEqualTo("0.0001");
        assertThat(RubyFormat.toS(Double.NaN)).isEqualTo("NaN");
        assertThat(RubyFormat.toS(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
        assertThat(RubyFormat.toS(Double.NEGATIVE_INFINITY)).isEqualTo("-Infinity");
    }

    @Test
    void temporalValuesRenderAsUtcActiveSupportStrings() {
        LocalDateTime at = LocalDateTime.of(2025, 3, 4, 5, 6, 7);
        assertThat(RubyFormat.toS(Timestamp.valueOf(at))).isEqualTo("2025-03-04 05:06:07 UTC");
        assertThat(RubyFormat.toS(at)).isEqualTo("2025-03-04 05:06:07 UTC");
        assertThat(RubyFormat.toS(OffsetDateTime.of(at, ZoneOffset.ofHours(2))))
            .isEqualTo("2025-03-04 03:06:07 UTC");
        assertThat(RubyFormat.toS(Date.valueOf(LocalDate.of(2025, 12, 31)))).isEqualTo("2025-12-31");
        assertThat(RubyFormat.toS(LocalDate.of(2025, 1, 2))).isEqualTo("2025-01-02");
    }

    @Test
    void listsRenderAsRubyArrayInspect() {
        assertThat(RubyFormat.toS(List.of())).isEqualTo("[]");
        assertThat(RubyFormat.toS(List.of("a", "b"))).isEqualTo("[\"a\", \"b\"]");
        assertThat(RubyFormat.toS(Arrays.asList(null, 1, true, new BigDecimal("2.50"))))
            .isEqualTo("[nil, 1, true, 2.5]");
        assertThat(RubyFormat.toS(List.of("say \"hi\"", "back\\slash", "line\nbreak\ttab\rcr", "#tag")))
            .isEqualTo("[\"say \\\"hi\\\"\", \"back\\\\slash\", \"line\\nbreak\\ttab\\rcr\", \"#tag\"]");
    }

    @Test
    void onlyNumbersAreSpreadsheetNumbers() {
        assertThat(RubyFormat.numeric(1)).isTrue();
        assertThat(RubyFormat.numeric(new BigDecimal("1.5"))).isTrue();
        assertThat(RubyFormat.numeric(2.5d)).isTrue();
        assertThat(RubyFormat.numeric("1")).isFalse();
        assertThat(RubyFormat.numeric(null)).isFalse();
        assertThat(RubyFormat.numeric(true)).isFalse();
    }

    @Test
    void matchesRubyFloatNotationBoundaries() {
        // ruby -e output: 1.0e+15, 123456789012345.0, 9.999999999999998e+15,
        // 0.0001, 1.0e-05, -1.0e+15, -123456789012345.0,
        // -9.999999999999998e+15, -0.0001, -1.0e-05.
        assertThat(RubyFormat.rubyFloat(1e15)).isEqualTo("1.0e+15");
        assertThat(RubyFormat.rubyFloat(123456789012345.0)).isEqualTo("123456789012345.0");
        assertThat(RubyFormat.rubyFloat(9999999999999998.0)).isEqualTo("9.999999999999998e+15");
        assertThat(RubyFormat.rubyFloat(0.0001)).isEqualTo("0.0001");
        assertThat(RubyFormat.rubyFloat(0.00001)).isEqualTo("1.0e-05");
        assertThat(RubyFormat.rubyFloat(-1e15)).isEqualTo("-1.0e+15");
        assertThat(RubyFormat.rubyFloat(-123456789012345.0)).isEqualTo("-123456789012345.0");
        assertThat(RubyFormat.rubyFloat(-9999999999999998.0)).isEqualTo("-9.999999999999998e+15");
        assertThat(RubyFormat.rubyFloat(-0.0001)).isEqualTo("-0.0001");
        assertThat(RubyFormat.rubyFloat(-0.00001)).isEqualTo("-1.0e-05");
    }
}
