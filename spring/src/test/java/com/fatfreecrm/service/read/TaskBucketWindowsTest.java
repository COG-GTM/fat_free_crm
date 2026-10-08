package com.fatfreecrm.service.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link TaskBucketWindows} to the Rails {@code Task} scopes: zone-aware {@code midnight},
 * {@code tomorrow}, {@code next_week}, {@code beginning_of_week/month} and the absolute
 * {@code .utc + 1.day} / {@code - 7.days} / {@code (beginning_of_month.utc - 1.day).beginning_of_month} steps.
 */
class TaskBucketWindowsTest {

    @Test
    void localMidnightsAreComputedInTheRequestZone() {
        ZoneId saoPaulo = ZoneId.of("America/Sao_Paulo");
        TaskBucketWindows windows = TaskBucketWindows.at(Instant.parse("2026-03-11T01:00:00Z"), saoPaulo);

        assertThat(windows.midnight()).isEqualTo(Instant.parse("2026-03-10T03:00:00Z"));
        assertThat(windows.tomorrow()).isEqualTo(Instant.parse("2026-03-11T03:00:00Z"));
        assertThat(windows.yesterday()).isEqualTo(Instant.parse("2026-03-09T03:00:00Z"));
        assertThat(windows.beginningOfWeek()).isEqualTo(Instant.parse("2026-03-09T03:00:00Z"));
        assertThat(windows.nextWeek()).isEqualTo(Instant.parse("2026-03-16T03:00:00Z"));
        assertThat(windows.beginningOfMonth()).isEqualTo(Instant.parse("2026-03-01T03:00:00Z"));
    }

    @Test
    void afterNextWeekIsNextWeekEndOfWeekPlusOneDayAtMicrosecondPrecision() {
        TaskBucketWindows windows = TaskBucketWindows.at(
            Instant.parse("2026-03-11T01:00:00Z"), ZoneId.of("America/Sao_Paulo"));

        assertThat(windows.afterNextWeek()).isEqualTo(Instant.parse("2026-03-24T02:59:59.999999Z"));
    }

    @Test
    void beginningOfLastMonthFollowsRailsUtcConversionNotTheRequestZone() {
        TaskBucketWindows windows = TaskBucketWindows.at(
            Instant.parse("2026-03-11T01:00:00Z"), ZoneId.of("America/Sao_Paulo"));

        assertThat(windows.beginningOfLastMonth()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
    }

    @Test
    void beginningOfLastMonthUsesTheUtcDateAfterSteppingBackOneDay() {
        TaskBucketWindows windows = TaskBucketWindows.at(
            Instant.parse("2026-03-01T12:00:00Z"), ZoneId.of("Pacific/Tongatapu"));

        assertThat(windows.beginningOfMonth()).isEqualTo(Instant.parse("2026-02-28T11:00:00Z"));
        assertThat(windows.beginningOfLastMonth()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(windows.midnight()).isEqualTo(Instant.parse("2026-03-01T11:00:00Z"));
    }

    @Test
    void absoluteStepsIgnoreDaylightSavingTransitionsLikeRailsUtcArithmetic() {
        ZoneId amsterdam = ZoneId.of("Europe/Amsterdam");
        TaskBucketWindows windows = TaskBucketWindows.at(Instant.parse("2026-03-28T12:00:00Z"), amsterdam);

        assertThat(windows.midnight()).isEqualTo(Instant.parse("2026-03-27T23:00:00Z"));
        assertThat(windows.tomorrow()).isEqualTo(Instant.parse("2026-03-28T23:00:00Z"));
        assertThat(windows.dayAfterTomorrow()).isEqualTo(Instant.parse("2026-03-29T23:00:00Z"));
        assertThat(windows.nextWeek()).isEqualTo(Instant.parse("2026-03-29T22:00:00Z"));
        assertThat(windows.beginningOfWeek()).isEqualTo(Instant.parse("2026-03-22T23:00:00Z"));
        assertThat(windows.beginningOfLastWeek()).isEqualTo(Instant.parse("2026-03-15T23:00:00Z"));
    }

    @Test
    void sundayBelongsToTheWeekStartingOnThePreviousMonday() {
        TaskBucketWindows windows = TaskBucketWindows.at(Instant.parse("2026-03-15T10:00:00Z"), ZoneOffset.UTC);

        assertThat(windows.beginningOfWeek()).isEqualTo(Instant.parse("2026-03-09T00:00:00Z"));
        assertThat(windows.nextWeek()).isEqualTo(Instant.parse("2026-03-16T00:00:00Z"));
        assertThat(windows.afterNextWeek()).isEqualTo(Instant.parse("2026-03-23T23:59:59.999999Z"));
    }

    @Test
    void yearBoundaryRollsMonthsAndYearsBack() {
        TaskBucketWindows windows = TaskBucketWindows.at(Instant.parse("2026-01-01T05:00:00Z"), ZoneOffset.UTC);

        assertThat(windows.beginningOfMonth()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(windows.beginningOfLastMonth()).isEqualTo(Instant.parse("2025-12-01T00:00:00Z"));
        assertThat(windows.yesterday()).isEqualTo(Instant.parse("2025-12-31T00:00:00Z"));
    }
}
