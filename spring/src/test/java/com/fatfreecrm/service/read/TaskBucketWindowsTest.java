package com.fatfreecrm.service.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Pins the bucket boundaries to the Rails {@code Task} scope expressions in
 * {@code app/models/polymorphic/task.rb}; expected instants were derived by hand from those expressions.
 */
class TaskBucketWindowsTest {

    @Test
    void calendarStepsHappenInTheRequestZoneLikeTimeZoneNow() {
        // Wednesday 2026-03-04 12:30 in São Paulo (UTC-3, no DST).
        TaskBucketWindows windows = TaskBucketWindows.at(
            Instant.parse("2026-03-04T15:30:00Z"), ZoneId.of("America/Sao_Paulo"));

        assertThat(windows.midnight()).isEqualTo(Instant.parse("2026-03-04T03:00:00Z"));
        assertThat(windows.tomorrow()).isEqualTo(Instant.parse("2026-03-05T03:00:00Z"));
        assertThat(windows.yesterday()).isEqualTo(Instant.parse("2026-03-03T03:00:00Z"));
        assertThat(windows.beginningOfWeek()).isEqualTo(Instant.parse("2026-03-02T03:00:00Z"));
        assertThat(windows.nextWeek()).isEqualTo(Instant.parse("2026-03-09T03:00:00Z"));
        assertThat(windows.beginningOfMonth()).isEqualTo(Instant.parse("2026-03-01T03:00:00Z"));
    }

    @Test
    void absoluteStepsAreTwentyFourHoursOnUtcTimes() {
        TaskBucketWindows windows = TaskBucketWindows.at(
            Instant.parse("2026-03-04T15:30:00Z"), ZoneId.of("America/Sao_Paulo"));

        // Time.zone.now.midnight.tomorrow.utc + 1.day
        assertThat(windows.dayAfterTomorrow()).isEqualTo(Instant.parse("2026-03-06T03:00:00Z"));
        // Time.zone.now.beginning_of_week.utc - 7.days
        assertThat(windows.beginningOfLastWeek()).isEqualTo(Instant.parse("2026-02-23T03:00:00Z"));
        // Time.zone.now.next_week.end_of_week.utc + 1.day  (end_of_week is 23:59:59.999999)
        assertThat(windows.afterNextWeek()).isEqualTo(Instant.parse("2026-03-17T02:59:59.999999Z"));
    }

    @Test
    void beginningOfLastMonthIsResolvedOnTheUtcDateLikeRails() {
        // (Time.zone.now.beginning_of_month.utc - 1.day).beginning_of_month.utc: the second
        // beginning_of_month runs on a UTC time, so for UTC+13 the local 1 March maps to 28 Feb UTC.
        TaskBucketWindows tonga = TaskBucketWindows.at(
            Instant.parse("2026-03-01T05:00:00Z"), ZoneId.of("Pacific/Tongatapu"));

        assertThat(tonga.midnight()).isEqualTo(Instant.parse("2026-02-28T11:00:00Z"));
        assertThat(tonga.beginningOfMonth()).isEqualTo(Instant.parse("2026-02-28T11:00:00Z"));
        assertThat(tonga.beginningOfLastMonth()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));

        TaskBucketWindows utc = TaskBucketWindows.at(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
        assertThat(utc.beginningOfMonth()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(utc.beginningOfLastMonth()).isEqualTo(Instant.parse("2025-12-01T00:00:00Z"));
    }

    @Test
    void weeksStartOnMondayAndSundayStillBelongsToTheCurrentWeek() {
        // Sunday 2026-03-08 in UTC: Rails beginning_of_week defaults to Monday.
        TaskBucketWindows windows = TaskBucketWindows.at(Instant.parse("2026-03-08T12:00:00Z"), ZoneOffset.UTC);

        assertThat(windows.beginningOfWeek()).isEqualTo(Instant.parse("2026-03-02T00:00:00Z"));
        assertThat(windows.nextWeek()).isEqualTo(Instant.parse("2026-03-09T00:00:00Z"));
        assertThat(windows.afterNextWeek()).isEqualTo(Instant.parse("2026-03-16T23:59:59.999999Z"));
        assertThat(windows.beginningOfLastWeek()).isEqualTo(Instant.parse("2026-02-23T00:00:00Z"));
    }
}
