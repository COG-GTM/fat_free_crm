package com.fatfreecrm.service.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link TaskBucketWindows} to the Rails bucket boundaries from {@code app/models/polymorphic/task.rb}:
 * {@code Time.zone.now.midnight}, {@code midnight.tomorrow}, {@code midnight.yesterday},
 * {@code next_week}, {@code next_week.end_of_week + 1.day}, {@code beginning_of_week},
 * {@code beginning_of_week - 7.days}, {@code beginning_of_month} and
 * {@code (beginning_of_month.utc - 1.day).beginning_of_month}. Expected instants were derived by hand
 * from those Rails expressions, not from the Java implementation.
 */
class TaskBucketWindowsTest {

    @Test
    void dstTransitionDayUsesLocalMidnightsNotFixedTwentyFourHourOffsets() {
        ZoneId newYork = ZoneId.of("America/New_York");
        // Sunday 2025-03-09, the day US DST starts (02:00 EST -> 03:00 EDT).
        Instant now = Instant.parse("2025-03-09T16:00:00Z");

        TaskBucketWindows windows = TaskBucketWindows.at(now, newYork);

        assertThat(windows.midnight()).isEqualTo(Instant.parse("2025-03-09T05:00:00Z"));
        // Time.zone.now.midnight.tomorrow is local midnight, only 23 hours later on DST day.
        assertThat(windows.tomorrow()).isEqualTo(Instant.parse("2025-03-10T04:00:00Z"));
        assertThat(windows.dayAfterTomorrow()).isEqualTo(Instant.parse("2025-03-11T04:00:00Z"));
        assertThat(windows.yesterday()).isEqualTo(Instant.parse("2025-03-08T05:00:00Z"));
        // next_week => Monday 2025-03-10 00:00 EDT
        assertThat(windows.nextWeek()).isEqualTo(Instant.parse("2025-03-10T04:00:00Z"));
        // next_week.end_of_week.utc + 1.day => Sunday 2025-03-16 23:59:59.999999 EDT + 1 day
        assertThat(windows.afterNextWeek()).isEqualTo(Instant.parse("2025-03-18T03:59:59.999999Z"));
        // beginning_of_week (Monday 2025-03-03 00:00 EST)
        assertThat(windows.beginningOfWeek()).isEqualTo(Instant.parse("2025-03-03T05:00:00Z"));
        assertThat(windows.beginningOfLastWeek()).isEqualTo(Instant.parse("2025-02-24T05:00:00Z"));
        assertThat(windows.beginningOfMonth()).isEqualTo(Instant.parse("2025-03-01T05:00:00Z"));
        // (beginning_of_month.utc - 1.day).beginning_of_month.utc is computed in UTC, not the user zone.
        assertThat(windows.beginningOfLastMonth()).isEqualTo(Instant.parse("2025-02-01T00:00:00Z"));
    }

    @Test
    void mondayIsStartOfWeekLikeRailsDefaultBeginningOfWeek() {
        Instant mondayNoon = Instant.parse("2025-06-02T12:00:00Z");

        TaskBucketWindows windows = TaskBucketWindows.at(mondayNoon, ZoneOffset.UTC);

        assertThat(windows.beginningOfWeek()).isEqualTo(Instant.parse("2025-06-02T00:00:00Z"));
        assertThat(windows.nextWeek()).isEqualTo(Instant.parse("2025-06-09T00:00:00Z"));
        assertThat(windows.afterNextWeek()).isEqualTo(Instant.parse("2025-06-16T23:59:59.999999Z"));
        assertThat(windows.beginningOfLastWeek()).isEqualTo(Instant.parse("2025-05-26T00:00:00Z"));
    }

    @Test
    void sundayBelongsToTheWeekThatStartedOnThePreviousMonday() {
        Instant sundayEvening = Instant.parse("2025-06-08T22:00:00Z");

        TaskBucketWindows windows = TaskBucketWindows.at(sundayEvening, ZoneOffset.UTC);

        assertThat(windows.beginningOfWeek()).isEqualTo(Instant.parse("2025-06-02T00:00:00Z"));
        assertThat(windows.nextWeek()).isEqualTo(Instant.parse("2025-06-09T00:00:00Z"));
    }

    @Test
    void zoneAheadOfUtcCrossesMonthBoundaryTheWayRailsDoes() {
        ZoneId tonga = ZoneId.of("Pacific/Tongatapu"); // UTC+13, no DST
        // 2025-01-01 13:30 local; UTC is still 2025-01-01 00:30.
        Instant now = Instant.parse("2025-01-01T00:30:00Z");

        TaskBucketWindows windows = TaskBucketWindows.at(now, tonga);

        assertThat(windows.midnight()).isEqualTo(Instant.parse("2024-12-31T11:00:00Z"));
        assertThat(windows.beginningOfMonth()).isEqualTo(Instant.parse("2024-12-31T11:00:00Z"));
        // beginning_of_month.utc - 1.day => 2024-12-30T11:00Z; its UTC beginning_of_month => 2024-12-01T00:00Z
        assertThat(windows.beginningOfLastMonth()).isEqualTo(Instant.parse("2024-12-01T00:00:00Z"));
        assertThat(windows.beginningOfWeek()).isEqualTo(Instant.parse("2024-12-29T11:00:00Z"));
    }
}
