package com.fatfreecrm.service.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link TaskBucketWindows} to the Rails {@code Task} bucket scope boundaries
 * ({@code Time.zone.now.midnight}, {@code .tomorrow}, {@code .next_week}, {@code .end_of_week + 1.day},
 * {@code .beginning_of_week}, {@code .beginning_of_month} and
 * {@code (beginning_of_month.utc - 1.day).beginning_of_month.utc}).
 *
 * <p>Expected instants were produced by running the Rails expressions from {@code app/models/polymorphic/task.rb}
 * under ActiveSupport with {@code Time.zone} set to each zone, so they cover DST transitions, month boundaries,
 * far-east offsets and the UTC-anchored "last month" window that Rails computes outside the session zone.
 */
class TaskBucketWindowsTest {

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void springForwardDayInNewYorkUsesCalendarDaysNotTwentyFourHours() {
        TaskBucketWindows windows = TaskBucketWindows.at(
            at("2026-03-08T12:00:00Z"), ZoneId.of("America/New_York"));

        assertThat(windows.midnight()).isEqualTo(at("2026-03-08T05:00:00Z"));
        assertThat(windows.tomorrow()).isEqualTo(at("2026-03-09T04:00:00Z"));
        assertThat(windows.dayAfterTomorrow()).isEqualTo(at("2026-03-10T04:00:00Z"));
        assertThat(windows.yesterday()).isEqualTo(at("2026-03-07T05:00:00Z"));
        assertThat(windows.nextWeek()).isEqualTo(at("2026-03-09T04:00:00Z"));
        assertThat(windows.afterNextWeek()).isEqualTo(at("2026-03-17T03:59:59.999999Z"));
        assertThat(windows.beginningOfWeek()).isEqualTo(at("2026-03-02T05:00:00Z"));
        assertThat(windows.beginningOfLastWeek()).isEqualTo(at("2026-02-23T05:00:00Z"));
        assertThat(windows.beginningOfMonth()).isEqualTo(at("2026-03-01T05:00:00Z"));
        assertThat(windows.beginningOfLastMonth()).isEqualTo(at("2026-02-01T00:00:00Z"));
    }

    @Test
    void farEastZoneStillOnPreviousUtcDayAndLastMonthIsAnchoredInUtc() {
        TaskBucketWindows windows = TaskBucketWindows.at(
            at("2026-03-01T03:00:00Z"), ZoneId.of("Pacific/Auckland"));

        assertThat(windows.midnight()).isEqualTo(at("2026-02-28T11:00:00Z"));
        assertThat(windows.tomorrow()).isEqualTo(at("2026-03-01T11:00:00Z"));
        assertThat(windows.dayAfterTomorrow()).isEqualTo(at("2026-03-02T11:00:00Z"));
        assertThat(windows.yesterday()).isEqualTo(at("2026-02-27T11:00:00Z"));
        assertThat(windows.nextWeek()).isEqualTo(at("2026-03-01T11:00:00Z"));
        assertThat(windows.afterNextWeek()).isEqualTo(at("2026-03-09T10:59:59.999999Z"));
        assertThat(windows.beginningOfWeek()).isEqualTo(at("2026-02-22T11:00:00Z"));
        assertThat(windows.beginningOfLastWeek()).isEqualTo(at("2026-02-15T11:00:00Z"));
        assertThat(windows.beginningOfMonth()).isEqualTo(at("2026-02-28T11:00:00Z"));
        assertThat(windows.beginningOfLastMonth()).isEqualTo(at("2026-02-01T00:00:00Z"));
    }

    @Test
    void newYearInUtcRollsWeekAndMonthWindowsIntoPreviousYear() {
        TaskBucketWindows windows = TaskBucketWindows.at(at("2026-01-01T00:30:00Z"), ZoneId.of("Etc/UTC"));

        assertThat(windows.midnight()).isEqualTo(at("2026-01-01T00:00:00Z"));
        assertThat(windows.tomorrow()).isEqualTo(at("2026-01-02T00:00:00Z"));
        assertThat(windows.dayAfterTomorrow()).isEqualTo(at("2026-01-03T00:00:00Z"));
        assertThat(windows.yesterday()).isEqualTo(at("2025-12-31T00:00:00Z"));
        assertThat(windows.nextWeek()).isEqualTo(at("2026-01-05T00:00:00Z"));
        assertThat(windows.afterNextWeek()).isEqualTo(at("2026-01-12T23:59:59.999999Z"));
        assertThat(windows.beginningOfWeek()).isEqualTo(at("2025-12-29T00:00:00Z"));
        assertThat(windows.beginningOfLastWeek()).isEqualTo(at("2025-12-22T00:00:00Z"));
        assertThat(windows.beginningOfMonth()).isEqualTo(at("2026-01-01T00:00:00Z"));
        assertThat(windows.beginningOfLastMonth()).isEqualTo(at("2025-12-01T00:00:00Z"));
    }

    @Test
    void fallBackDayInAmsterdamHasTwentyFiveHourTomorrowWindow() {
        TaskBucketWindows windows = TaskBucketWindows.at(
            at("2026-10-25T00:30:00Z"), ZoneId.of("Europe/Amsterdam"));

        assertThat(windows.midnight()).isEqualTo(at("2026-10-24T22:00:00Z"));
        assertThat(windows.tomorrow()).isEqualTo(at("2026-10-25T23:00:00Z"));
        assertThat(windows.dayAfterTomorrow()).isEqualTo(at("2026-10-26T23:00:00Z"));
        assertThat(windows.yesterday()).isEqualTo(at("2026-10-23T22:00:00Z"));
        assertThat(windows.nextWeek()).isEqualTo(at("2026-10-25T23:00:00Z"));
        assertThat(windows.afterNextWeek()).isEqualTo(at("2026-11-02T22:59:59.999999Z"));
        assertThat(windows.beginningOfWeek()).isEqualTo(at("2026-10-18T22:00:00Z"));
        assertThat(windows.beginningOfLastWeek()).isEqualTo(at("2026-10-11T22:00:00Z"));
        assertThat(windows.beginningOfMonth()).isEqualTo(at("2026-09-30T22:00:00Z"));
        assertThat(windows.beginningOfLastMonth()).isEqualTo(at("2026-09-01T00:00:00Z"));
    }

    @Test
    void sundayBelongsToTheWeekStartingOnThePrecedingMonday() {
        TaskBucketWindows windows = TaskBucketWindows.at(at("2026-03-15T23:59:59Z"), ZoneId.of("Etc/UTC"));

        assertThat(windows.beginningOfWeek()).isEqualTo(at("2026-03-09T00:00:00Z"));
        assertThat(windows.nextWeek()).isEqualTo(at("2026-03-16T00:00:00Z"));
        assertThat(windows.afterNextWeek()).isEqualTo(at("2026-03-23T23:59:59.999999Z"));
    }
}
