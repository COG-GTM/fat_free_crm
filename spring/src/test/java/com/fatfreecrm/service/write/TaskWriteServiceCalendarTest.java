package com.fatfreecrm.service.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;

/** Ruby {@code Time.parse(calendar)} mirror used by the {@code specific_time} validator and set_due_date. */
class TaskWriteServiceCalendarTest {

    private static final ZoneId JVM_ZONE = ZoneId.systemDefault();

    @Test
    void parsesDateTimeWithSpaceOrTSeparatorInProcessLocalZone() {
        assertEquals(LocalDateTime.of(2030, 5, 4, 10, 0).atZone(JVM_ZONE).toInstant(),
            TaskWriteService.parseCalendar("2030-05-04 10:00"));
        assertEquals(LocalDateTime.of(2030, 5, 4, 10, 0, 30).atZone(JVM_ZONE).toInstant(),
            TaskWriteService.parseCalendar("2030-05-04T10:00:30"));
    }

    @Test
    void parsesBareDateAsMidnightAndTrimsWhitespace() {
        assertEquals(LocalDate.of(2030, 5, 4).atStartOfDay(JVM_ZONE).toInstant(),
            TaskWriteService.parseCalendar(" 2030-05-04 "));
    }

    @Test
    void rejectsUnparseableInputWithDateTimeParseException() {
        assertThrows(DateTimeParseException.class, () -> TaskWriteService.parseCalendar("not a date"));
        assertThrows(DateTimeParseException.class, () -> TaskWriteService.parseCalendar("04/05/2030"));
        assertThrows(DateTimeParseException.class, () -> TaskWriteService.parseCalendar(""));
    }

    @Test
    void taskCreateDefaultsOnlyTreatNameAsEmptyString() {
        assertEquals(1, TaskWriteService.TASK_DEFAULTS.size());
        assertEquals("", TaskWriteService.TASK_DEFAULTS.get("name"));
    }
}
