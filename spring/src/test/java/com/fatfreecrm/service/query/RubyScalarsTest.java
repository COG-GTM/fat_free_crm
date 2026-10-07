package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Ruby coercion semantics used by pagination and Ransack value casting. */
class RubyScalarsTest {

    @Test
    void toLongParsesLeadingDigitsLikeRubyToI() {
        assertThat(RubyScalars.toLong("abc")).isZero();
        assertThat(RubyScalars.toLong("")).isZero();
        assertThat(RubyScalars.toLong("   3.7")).isEqualTo(3);
        assertThat(RubyScalars.toLong("-12x")).isEqualTo(-12);
        assertThat(RubyScalars.toLong("+7")).isEqualTo(7);
        assertThat(RubyScalars.toLong("1_000")).isEqualTo(1000);
        assertThat(RubyScalars.toLong("x5")).isZero();
        assertThat(RubyScalars.toLong("99999999999999999999999999")).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void toDecimalParsesLeadingNumericPrefix() {
        assertThat(RubyScalars.toDecimal("abc")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(RubyScalars.toDecimal("1.5x")).isEqualByComparingTo("1.5");
        assertThat(RubyScalars.toDecimal("-2.25")).isEqualByComparingTo("-2.25");
        assertThat(RubyScalars.toDecimal("7")).isEqualByComparingTo("7");
    }

    @Test
    void toBooleanFollowsActiveModelRules() {
        assertThat(RubyScalars.toBoolean("0")).isFalse();
        assertThat(RubyScalars.toBoolean("f")).isFalse();
        assertThat(RubyScalars.toBoolean("F")).isFalse();
        assertThat(RubyScalars.toBoolean("false")).isFalse();
        assertThat(RubyScalars.toBoolean("FALSE")).isFalse();
        assertThat(RubyScalars.toBoolean("off")).isFalse();
        assertThat(RubyScalars.toBoolean("OFF")).isFalse();
        assertThat(RubyScalars.toBoolean("1")).isTrue();
        assertThat(RubyScalars.toBoolean("anything")).isTrue();
    }

    @Test
    void toLocalDateAcceptsOnlyIsoDates() {
        assertThat(RubyScalars.toLocalDate("2025-01-05")).isEqualTo(LocalDate.of(2025, 1, 5));
        assertThat(RubyScalars.toLocalDate("yesterday")).isNull();
        assertThat(RubyScalars.toLocalDate("2025-13-01")).isNull();
    }

    @Test
    void toInstantMapsIsoDateToNoonUtcAndIsoDatetimeExactly() {
        assertThat(RubyScalars.toInstant("2025-01-02")).isEqualTo(Instant.parse("2025-01-02T12:00:00Z"));
        assertThat(RubyScalars.toInstant("2025-01-02T03:04:05")).isEqualTo(Instant.parse("2025-01-02T03:04:05Z"));
        assertThat(RubyScalars.toInstant("2025-01-02 03:04:05")).isEqualTo(Instant.parse("2025-01-02T03:04:05Z"));
        assertThat(RubyScalars.toInstant("2024-01-01T10:00Z")).isEqualTo(Instant.parse("2024-01-01T10:00:00Z"));
        assertThat(RubyScalars.toInstant("2024-01-01 10:00")).isEqualTo(Instant.parse("2024-01-01T10:00:00Z"));
        assertThat(RubyScalars.toInstant("2024-01-01T10:00+02:00")).isEqualTo(Instant.parse("2024-01-01T08:00:00Z"));
        assertThat(RubyScalars.toInstant("2025-01-02T03:04:05.123"))
            .isEqualTo(Instant.parse("2025-01-02T03:04:05.123Z"));
        assertThat(RubyScalars.toInstant("yesterday")).isNull();
    }
}
