package com.kalix.ide.utils;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
