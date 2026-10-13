package com.fatfreecrm.service.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link TaskWriteService#parseCalendar} to Ruby {@code Time.parse} on the Rails
 * {@code calendar} accessor (always {@code "2012-10-28 06:28"} shaped, process-local zone) and the
 * {@code validate :specific_time} ArgumentError path that becomes {@code :invalid_date}.
 */
class TaskWriteServiceCalendarTest {

    private static final ZoneId JVM_ZONE = ZoneId.systemDefault();

    @Test
    void parsesRailsDatepickerFormatInProcessLocalZone() {
        assertThat(TaskWriteService.parseCalendar("2030-05-04 10:30"))
            .isEqualTo(LocalDateTime.of(2030, 5, 4, 10, 30).atZone(JVM_ZONE).toInstant());
    }

    @Test
    void acceptsIsoDateTimeAndSurroundingWhitespace() {
        assertThat(TaskWriteService.parseCalendar("  2030-05-04T10:30:15 "))
            .isEqualTo(LocalDateTime.of(2030, 5, 4, 10, 30, 15).atZone(JVM_ZONE).toInstant());
    }

    @Test
    void dateOnlyMeansMidnightLikeTimeParse() {
        assertThat(TaskWriteService.parseCalendar("2030-05-04"))
            .isEqualTo(LocalDate.of(2030, 5, 4).atStartOfDay(JVM_ZONE).toInstant());
    }

    @Test
    void unparseableCalendarRaisesSoTheValidatorAddsInvalidDate() {
        assertThatThrownBy(() -> TaskWriteService.parseCalendar("not a date"))
            .isInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> TaskWriteService.parseCalendar(""))
            .isInstanceOf(DateTimeParseException.class);
    }

    @Test
    void taskCreateDefaultsOnlyCarryTheNameColumnDefault() {
        assertThat(TaskWriteService.TASK_DEFAULTS).containsOnly(java.util.Map.entry("name", ""));
    }
}
