package com.fatfreecrm.service.read;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * The instants Rails {@code Task} bucket scopes compare {@code due_at}/{@code completed_at} against,
 * computed exactly like {@code app/models/polymorphic/task.rb} from {@code Time.zone.now}: calendar steps
 * ({@code midnight}, {@code tomorrow}, {@code next_week}, {@code beginning_of_week/month}) happen in the
 * request zone, while {@code .utc + 1.day} / {@code - 7.days} are absolute 24-hour steps on UTC times.
 */
record TaskBucketWindows(
    Instant midnight,
    Instant tomorrow,
    Instant yesterday,
    Instant nextWeek,
    Instant afterNextWeek,
    Instant beginningOfWeek,
    Instant beginningOfMonth,
    Instant beginningOfLastMonth
) {

    private static final Duration DAY = Duration.ofDays(1);
    private static final Duration END_OF_DAY = DAY.minusNanos(1_000);

    static TaskBucketWindows at(Instant now, ZoneId zone) {
        ZonedDateTime local = now.atZone(zone);
        LocalDate today = local.toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate nextMonday = monday.plusWeeks(1);
        Instant nextWeek = nextMonday.atStartOfDay(zone).toInstant();
        Instant nextWeekEnd = nextMonday.plusDays(6).atStartOfDay(zone).plus(END_OF_DAY).toInstant();
        Instant beginningOfMonth = today.withDayOfMonth(1).atStartOfDay(zone).toInstant();
        Instant beginningOfLastMonth = beginningOfMonth.minus(DAY).atZone(ZoneOffset.UTC).toLocalDate()
            .withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new TaskBucketWindows(
            today.atStartOfDay(zone).toInstant(),
            today.plusDays(1).atStartOfDay(zone).toInstant(),
            today.minusDays(1).atStartOfDay(zone).toInstant(),
            nextWeek,
            nextWeekEnd.plus(DAY),
            monday.atStartOfDay(zone).toInstant(),
            beginningOfMonth,
            beginningOfLastMonth
        );
    }

    /** {@code Time.zone.now.midnight.tomorrow.utc + 1.day}. */
    Instant dayAfterTomorrow() {
        return tomorrow.plus(DAY);
    }

    /** {@code Time.zone.now.beginning_of_week.utc - 7.days}. */
    Instant beginningOfLastWeek() {
        return beginningOfWeek.minus(Duration.ofDays(7));
    }
}
