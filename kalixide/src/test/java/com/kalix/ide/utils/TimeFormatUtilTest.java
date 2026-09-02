package com.kalix.ide.utils;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeFormatUtilTest {

    private static final long MS_2024_01_15_14_30 =
        LocalDateTime.of(2024, 1, 15, 14, 30, 0).toEpochSecond(ZoneOffset.UTC) * 1000L;

    private static final long MS_2024_01_15_MIDNIGHT =
        LocalDateTime.of(2024, 1, 15, 0, 0, 0).toEpochSecond(ZoneOffset.UTC) * 1000L;

    // ---- formatForStepSize ----

    @Test
    void dailyStepSizeFormatsAsDateOnly() {
        assertEquals("2024-01-15", TimeFormatUtil.formatForStepSize(MS_2024_01_15_14_30, 86400));
    }

    @Test
    void hourlyStepSizeFormatsAsIsoDatetime() {
        assertEquals("2024-01-15T14:30:00", TimeFormatUtil.formatForStepSize(MS_2024_01_15_14_30, 3600));
    }

    @Test
    void hourlyStepSizeKeepsTimeEvenAtMidnight() {
        // The bug we fixed: midnight rows in an hourly file should still show the time component
        assertEquals("2024-01-15T00:00:00", TimeFormatUtil.formatForStepSize(MS_2024_01_15_MIDNIGHT, 3600));
    }

    @Test
    void unknownStepSizeFallsBackToDateOnly() {
        assertEquals("2024-01-15", TimeFormatUtil.formatForStepSize(MS_2024_01_15_14_30, 0));
    }

    @Test
    void weeklyStepSizeFormatsAsDateOnly() {
        // Anything that is a multiple of a day is "daily-or-coarser" by our convention
        assertEquals("2024-01-15", TimeFormatUtil.formatForStepSize(MS_2024_01_15_14_30, 7 * 86400));
    }

    // ---- formatForTickInterval ----

    @Test
    void dailyTickIntervalUsesDateOnly() {
        assertEquals("2024-01-15", TimeFormatUtil.formatForTickInterval(MS_2024_01_15_14_30, 86400000L));
    }

    @Test
    void hourlyTickIntervalIncludesDateAndTime() {
        // Hourly ticks can span multiple days, so the format keeps the date
        assertEquals("01-15 14:30", TimeFormatUtil.formatForTickInterval(MS_2024_01_15_14_30, 3600000L));
    }

    @Test
    void minuteTickIntervalDropsDate() {
        assertEquals("14:30", TimeFormatUtil.formatForTickInterval(MS_2024_01_15_14_30, 60000L));
    }

    @Test
    void subMinuteTickIntervalIncludesSeconds() {
        assertEquals("14:30:00", TimeFormatUtil.formatForTickInterval(MS_2024_01_15_14_30, 1000L));
    }

    // ---- parseFlexible ----

    @Test
    void parseFlexibleAcceptsDateOnly() {
        assertEquals(MS_2024_01_15_MIDNIGHT, TimeFormatUtil.parseFlexible("2024-01-15"));
    }

    @Test
    void parseFlexibleAcceptsIsoDatetime() {
        assertEquals(MS_2024_01_15_14_30, TimeFormatUtil.parseFlexible("2024-01-15T14:30:00"));
    }

    @Test
    void parseFlexibleAcceptsSpaceSeparatedDatetime() {
        assertEquals(MS_2024_01_15_14_30, TimeFormatUtil.parseFlexible("2024-01-15 14:30:00"));
    }

    @Test
    void parseFlexibleTrimsWhitespace() {
        assertEquals(MS_2024_01_15_MIDNIGHT, TimeFormatUtil.parseFlexible("  2024-01-15  "));
    }

    @Test
    void parseFlexibleRejectsUnrecognisedText() {
        assertThrows(DateTimeParseException.class, () -> TimeFormatUtil.parseFlexible("not a date"));
    }

    // ---- parseFlexible: input formats (issue #223) ----

    @Test
    void parseFlexibleAcceptsUnpaddedYearFirst() {
        assertEquals(MS_2024_01_15_MIDNIGHT, TimeFormatUtil.parseFlexible("2024-1-15"));
        assertEquals(MS_2024_01_15_MIDNIGHT, TimeFormatUtil.parseFlexible("2024-01-15"));
    }

    @Test
    void parseFlexibleAcceptsDayFirst() {
        assertEquals(MS_2024_01_15_MIDNIGHT, TimeFormatUtil.parseFlexible("15-01-2024"));
        assertEquals(MS_2024_01_15_MIDNIGHT, TimeFormatUtil.parseFlexible("15-1-2024"));
    }

    /** Day-first is day-month-year, never month-day-year. */
    @Test
    void parseFlexibleReadsDayFirstAsDayMonthYear() {
        assertEquals(TimeFormatUtil.parseFlexible("2024-02-01"), TimeFormatUtil.parseFlexible("01-02-2024"));
    }

    @Test
    void parseFlexibleAcceptsSlashAndDotSeparators() {
        assertEquals(MS_2024_01_15_MIDNIGHT, TimeFormatUtil.parseFlexible("2024/01/15"));
        assertEquals(MS_2024_01_15_MIDNIGHT, TimeFormatUtil.parseFlexible("15/1/2024"));
        assertEquals(MS_2024_01_15_MIDNIGHT, TimeFormatUtil.parseFlexible("15.01.2024"));
    }

    @Test
    void parseFlexibleAcceptsTimeWithoutSeconds() {
        assertEquals(MS_2024_01_15_14_30, TimeFormatUtil.parseFlexible("2024-01-15 14:30"));
        assertEquals(MS_2024_01_15_14_30, TimeFormatUtil.parseFlexible("15/1/2024T14:30:00"));
    }

    /**
     * The example from the issue: a range typed with unpadded day numbers, as one half of
     * "2024-02-1, 2025-02-1".
     */
    @Test
    void parseFlexibleAcceptsIssueExample() {
        assertEquals(TimeFormatUtil.parseFlexible("2024-02-01"), TimeFormatUtil.parseFlexible("2024-02-1"));
    }

    @Test
    void parseFlexibleRejectsTwoDigitYear() {
        assertThrows(DateTimeParseException.class, () -> TimeFormatUtil.parseFlexible("01/02/03"));
        assertThrows(DateTimeParseException.class, () -> TimeFormatUtil.parseFlexible("15-01-24"));
    }

    @Test
    void parseFlexibleRejectsImpossibleDate() {
        assertThrows(DateTimeParseException.class, () -> TimeFormatUtil.parseFlexible("2024-02-31"));
        assertThrows(DateTimeParseException.class, () -> TimeFormatUtil.parseFlexible("2024-13-01"));
    }

    @Test
    void parseFlexibleRejectsImpossibleTime() {
        assertThrows(DateTimeParseException.class, () -> TimeFormatUtil.parseFlexible("2024-01-15 25:00"));
        assertThrows(DateTimeParseException.class, () -> TimeFormatUtil.parseFlexible("2024-01-15 14:61"));
    }

    @Test
    void parseFlexibleRejectsPartialDate() {
        assertThrows(DateTimeParseException.class, () -> TimeFormatUtil.parseFlexible("2024-01"));
        assertThrows(DateTimeParseException.class, () -> TimeFormatUtil.parseFlexible("2024"));
        assertThrows(DateTimeParseException.class, () -> TimeFormatUtil.parseFlexible(""));
    }

    @Test
    void parseFlexibleRejectsSubSecondPrecision() {
        assertThrows(DateTimeParseException.class,
            () -> TimeFormatUtil.parseFlexible("2024-01-15T14:30:00.123"));
    }
}
