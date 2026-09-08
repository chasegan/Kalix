package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.stats.SeasonalMaskMode;
import java.util.Set;
import java.time.Month;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeSeriesAggregatorTest {

    @Test
    void dailyAggregationOfHourlyDataSumsEachDay() {
        // Two full days of hourly data: day 1 has values 1..24, day 2 has values 25..48.
        // Sum per day: day 1 = 300, day 2 = 876.
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        int n = 48;
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            dates[i] = start.plusHours(i);
            values[i] = i + 1;
        }
        TimeSeriesData hourly = new TimeSeriesData(dates, values);

        TimeSeriesData daily = TimeSeriesAggregator.aggregate(
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);

        assertEquals(2, daily.getPointCount(), "Should produce one row per day");
        assertEquals(300.0, daily.getValues()[0], 1e-9, "Day 1 sum");
        assertEquals(876.0, daily.getValues()[1], 1e-9, "Day 2 sum");
    }

    @Test
    void dailyAggregationMeanReturnsAverage() {
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        LocalDateTime[] dates = new LocalDateTime[24];
        double[] values = new double[24];
        for (int i = 0; i < 24; i++) {
            dates[i] = start.plusHours(i);
            values[i] = i + 1; // 1..24
        }
        TimeSeriesData hourly = new TimeSeriesData(dates, values);

        TimeSeriesData daily = TimeSeriesAggregator.aggregate(
            hourly, AggregationPeriod.DAILY, AggregationMethod.MEAN, SeasonalMaskMode.DISABLED);

        assertEquals(1, daily.getPointCount());
        assertEquals(12.5, daily.getValues()[0], 1e-9, "Mean of 1..24");
    }

    @Test
    void dailyAggregationFirstDayIncompleteIfStartsMidDay() {
        // Series starts at 12:00 on day 1 — first day is partial. Aggregation should
        // mark that day as NaN (incomplete) but day 2 should aggregate normally.
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 12, 0);
        int n = 36; // 12 hours of day 1, 24 hours of day 2
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            dates[i] = start.plusHours(i);
            values[i] = 1.0;
        }
        TimeSeriesData hourly = new TimeSeriesData(dates, values);

        TimeSeriesData daily = TimeSeriesAggregator.aggregate(
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);

        assertEquals(2, daily.getPointCount());
        assertTrue(Double.isNaN(daily.getValues()[0]),
            "Partial first day should be NaN, got: " + daily.getValues()[0]);
        assertEquals(24.0, daily.getValues()[1], 1e-9, "Full second day should sum to 24");
    }

    @Test
    void dailyAggregationOfDailyDataIsLossless() {
        // Aggregating daily data to "Daily" should be a no-op: each bucket has one value.
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        int n = 5;
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            dates[i] = start.plusDays(i);
            values[i] = (i + 1) * 10.0;
        }
        TimeSeriesData daily = new TimeSeriesData(dates, values);

        TimeSeriesData reaggregated = TimeSeriesAggregator.aggregate(
            daily, AggregationPeriod.DAILY, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);

        assertEquals(n, reaggregated.getPointCount());
        for (int i = 0; i < n; i++) {
            assertEquals(values[i], reaggregated.getValues()[i], 1e-9);
        }
    }

    @Test
    void dailyAggregationFinalDayIncompleteIfEndsMidDay() {
        // Day 1 is full hourly data (00:00..23:00); day 2 stops at 03:00. The partial
        // final day must be NaN — not an under-reported sum of the four present hours.
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        int n = 28; // 24 hours of day 1, 4 hours of day 2
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            dates[i] = start.plusHours(i);
            values[i] = 1.0;
        }
        TimeSeriesData hourly = new TimeSeriesData(dates, values);

        TimeSeriesData daily = TimeSeriesAggregator.aggregate(
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);

        assertEquals(2, daily.getPointCount());
        assertEquals(24.0, daily.getValues()[0], 1e-9, "Full first day should sum to 24");
        assertTrue(Double.isNaN(daily.getValues()[1]),
            "Partial final day should be NaN, got: " + daily.getValues()[1]);
    }

    @Test
    void dailyAggregationFinalDayCompleteWhenLastHourPresent() {
        // Two full days ending at 23:00 on day 2 — the final day is complete.
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        int n = 48;
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            dates[i] = start.plusHours(i);
            values[i] = 2.0;
        }
        TimeSeriesData hourly = new TimeSeriesData(dates, values);

        TimeSeriesData daily = TimeSeriesAggregator.aggregate(
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);

        assertEquals(2, daily.getPointCount());
        assertEquals(48.0, daily.getValues()[0], 1e-9);
        assertEquals(48.0, daily.getValues()[1], 1e-9,
            "Final day ending at 23:00 is complete and must aggregate normally");
    }

    @Test
    void dailyAggregationMinMaxOfHourlyData() {
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        LocalDateTime[] dates = new LocalDateTime[24];
        double[] values = new double[24];
        for (int i = 0; i < 24; i++) {
            dates[i] = start.plusHours(i);
            values[i] = i + 1; // 1..24
        }
        TimeSeriesData hourly = new TimeSeriesData(dates, values);

        TimeSeriesData min = TimeSeriesAggregator.aggregate(
            hourly, AggregationPeriod.DAILY, AggregationMethod.MIN, SeasonalMaskMode.DISABLED);
        TimeSeriesData max = TimeSeriesAggregator.aggregate(
            hourly, AggregationPeriod.DAILY, AggregationMethod.MAX, SeasonalMaskMode.DISABLED);

        assertEquals(1.0, min.getValues()[0], 1e-9);
        assertEquals(24.0, max.getValues()[0], 1e-9);
    }

    @Test
    void dailyAggregationNaNPointMakesDayNaN() {
        // Day 1 has one NaN hour; day 2 is clean. Day 1 must be NaN, day 2 must aggregate.
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        int n = 48;
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            dates[i] = start.plusHours(i);
            values[i] = 1.0;
        }
        values[5] = Double.NaN;
        TimeSeriesData hourly = new TimeSeriesData(dates, values);

        TimeSeriesData daily = TimeSeriesAggregator.aggregate(
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);

        assertEquals(2, daily.getPointCount());
        assertTrue(Double.isNaN(daily.getValues()[0]), "Day containing a NaN point is NaN");
        assertEquals(24.0, daily.getValues()[1], 1e-9);
    }

    @Test
    void dailyAggregationTrimsTrailingAllInvalidDay() {
        // Day 1 valid, day 2 entirely NaN: the output range ends at the last day with
        // valid data, so day 2 is trimmed rather than emitted.
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        int n = 48;
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            dates[i] = start.plusHours(i);
            values[i] = i < 24 ? 1.0 : Double.NaN;
        }
        TimeSeriesData hourly = new TimeSeriesData(dates, values);

        TimeSeriesData daily = TimeSeriesAggregator.aggregate(
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);

        assertEquals(1, daily.getPointCount());
        assertEquals(24.0, daily.getValues()[0], 1e-9);
    }

    @Test
    void dailyAggregationTimestampsAreMidnightOfEachDay() {
        LocalDateTime start = LocalDateTime.of(2020, 3, 5, 0, 0);
        int n = 48;
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            dates[i] = start.plusHours(i);
            values[i] = 1.0;
        }
        TimeSeriesData hourly = new TimeSeriesData(dates, values);

        TimeSeriesData daily = TimeSeriesAggregator.aggregate(
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);

        long day0 = start.toInstant(ZoneOffset.UTC).toEpochMilli();
        assertEquals(day0, daily.getTimestamps()[0]);
        assertEquals(day0 + 86_400_000L, daily.getTimestamps()[1]);
    }

    @Test
    void monthlyAggregationOfDailyDataHandlesBoundaries() {
        // Daily data 2020-01-01 .. 2020-03-15: Jan and Feb (leap: 29 days) are complete,
        // March is partial and must be NaN.
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        int n = 31 + 29 + 15;
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            dates[i] = start.plusDays(i);
            values[i] = 1.0;
        }
        TimeSeriesData dailyData = new TimeSeriesData(dates, values);

        TimeSeriesData monthly = TimeSeriesAggregator.aggregate(
            dailyData, AggregationPeriod.MONTHLY, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);

        assertEquals(3, monthly.getPointCount());
        assertEquals(31.0, monthly.getValues()[0], 1e-9, "January");
        assertEquals(29.0, monthly.getValues()[1], 1e-9, "February (leap year)");
        assertTrue(Double.isNaN(monthly.getValues()[2]), "Partial March should be NaN");

        long jan1 = LocalDateTime.of(2020, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
        long feb1 = LocalDateTime.of(2020, 2, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
        long mar1 = LocalDateTime.of(2020, 3, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
        assertArrayEquals(new long[] {jan1, feb1, mar1}, monthly.getTimestamps());
    }

    @Test
    void monthlyAggregationEmitsNaNForFullyMissingInteriorMonth() {
        // Daily data for Jan and Mar 2021 only — Feb has no points at all. The monthly
        // grid stays complete with a NaN slot for February.
        LocalDateTime jan = LocalDateTime.of(2021, 1, 1, 0, 0);
        LocalDateTime mar = LocalDateTime.of(2021, 3, 1, 0, 0);
        int n = 31 + 31;
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < 31; i++) {
            dates[i] = jan.plusDays(i);
            values[i] = 1.0;
            dates[31 + i] = mar.plusDays(i);
            values[31 + i] = 2.0;
        }
        TimeSeriesData dailyData = new TimeSeriesData(dates, values);

        TimeSeriesData monthly = TimeSeriesAggregator.aggregate(
            dailyData, AggregationPeriod.MONTHLY, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);

        assertEquals(3, monthly.getPointCount());
        assertEquals(31.0, monthly.getValues()[0], 1e-9, "January");
        assertTrue(Double.isNaN(monthly.getValues()[1]), "Fully-missing February is a NaN slot");
        assertEquals(62.0, monthly.getValues()[2], 1e-9, "March");
    }

    @Test
    void monthlyAggregationNaNDayMakesMonthNaN() {
        LocalDateTime start = LocalDateTime.of(2021, 1, 1, 0, 0);
        int n = 31 + 28;
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            dates[i] = start.plusDays(i);
            values[i] = 1.0;
        }
        values[10] = Double.NaN; // a NaN day in January
        TimeSeriesData dailyData = new TimeSeriesData(dates, values);

        TimeSeriesData monthly = TimeSeriesAggregator.aggregate(
            dailyData, AggregationPeriod.MONTHLY, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);

        assertEquals(2, monthly.getPointCount());
        assertTrue(Double.isNaN(monthly.getValues()[0]), "Month containing a NaN day is NaN");
        assertEquals(28.0, monthly.getValues()[1], 1e-9, "February");
    }

    @Test
    void annualJulJunWaterYearAggregation() {
        // Daily data 2019-07-01 .. 2020-06-30: exactly one Jul-Jun water year (366 days,
        // spans Feb 2020 which is leap). SUM = 366; period start stamps 2019-07-01.
        LocalDateTime start = LocalDateTime.of(2019, 7, 1, 0, 0);
        int n = 366;
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            dates[i] = start.plusDays(i);
            values[i] = 1.0;
        }
        assertEquals(LocalDateTime.of(2020, 6, 30, 0, 0), dates[n - 1], "test construction sanity");
        TimeSeriesData dailyData = new TimeSeriesData(dates, values);

        TimeSeriesData annual = TimeSeriesAggregator.aggregate(
            dailyData, AggregationPeriod.ANNUAL_JUL_JUN, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);

        assertEquals(1, annual.getPointCount());
        assertEquals(366.0, annual.getValues()[0], 1e-9);
        assertEquals(start.toInstant(ZoneOffset.UTC).toEpochMilli(), annual.getTimestamps()[0]);
    }

    @Test
    void annualAggregationMarksPartialYearNaN() {
        // Daily data 2019-07-01 .. 2020-12-31 with a Jul-Jun water year: WY2019 is complete,
        // WY2020 (Jul 2020 - Jun 2021) is partial and must be NaN.
        LocalDateTime start = LocalDateTime.of(2019, 7, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2020, 12, 31, 0, 0);
        int n = (int) java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            dates[i] = start.plusDays(i);
            values[i] = 1.0;
        }
        TimeSeriesData dailyData = new TimeSeriesData(dates, values);

        TimeSeriesData annual = TimeSeriesAggregator.aggregate(
            dailyData, AggregationPeriod.ANNUAL_JUL_JUN, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);

        assertEquals(2, annual.getPointCount());
        assertEquals(366.0, annual.getValues()[0], 1e-9, "Complete WY2019");
        assertTrue(Double.isNaN(annual.getValues()[1]), "Partial WY2020 should be NaN");
    }

    @Test
    void emptySeriesReturnsOriginal() {
        TimeSeriesData empty = new TimeSeriesData(new long[0], new double[0]);
        assertSame(empty, TimeSeriesAggregator.aggregate(
            empty, AggregationPeriod.DAILY, AggregationMethod.SUM, SeasonalMaskMode.DISABLED));
    }

    @Test
    void allInvalidSeriesYieldsEmptyAggregation() {
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        LocalDateTime[] dates = new LocalDateTime[24];
        double[] values = new double[24];
        for (int i = 0; i < 24; i++) {
            dates[i] = start.plusHours(i);
            values[i] = Double.NaN;
        }
        TimeSeriesData hourly = new TimeSeriesData(dates, values);

        TimeSeriesData daily = TimeSeriesAggregator.aggregate(
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);

        assertEquals(0, daily.getPointCount());
    }

    @Test
    void dailyAggregationEmitsNaNForFullyMissingInteriorDay() {
        // Hourly data for day 1 and day 3; day 2 is entirely absent from the input.
        LocalDateTime day1 = LocalDateTime.of(2020, 1, 1, 0, 0);
        int n = 48;
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int h = 0; h < 24; h++) {
            dates[h] = day1.plusHours(h);
            values[h] = 1.0;
        }
        for (int h = 0; h < 24; h++) {
            dates[24 + h] = day1.plusDays(2).plusHours(h);
            values[24 + h] = 2.0;
        }
        TimeSeriesData hourly = new TimeSeriesData(dates, values);

        TimeSeriesData daily = TimeSeriesAggregator.aggregate(
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);

        assertEquals(3, daily.getPointCount(), "missing day 2 is emitted as a gap, not dropped");
        assertTrue(daily.isContiguous(), "the filled daily grid is contiguous");
        assertEquals(24.0, daily.getValues()[0], 1e-9, "day 1 sum");
        assertFalse(daily.getValidPoints()[1], "fully-missing day 2 is a NaN slot");
        assertEquals(48.0, daily.getValues()[2], 1e-9, "day 3 sum");
    }
    /** Daily points valued 1, from 1 Jan 2020 up to (not including) {@code endExclusive}. */
    private static TimeSeriesData daily2020(LocalDate endExclusive) {
        LocalDate start = LocalDate.of(2020, 1, 1);
        int days = (int) (endExclusive.toEpochDay() - start.toEpochDay());
        long[] timestamps = new long[days];
        double[] values = new double[days];
        for (int i = 0; i < days; i++) {
            timestamps[i] = start.plusDays(i).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            values[i] = 1;
        }
        return new TimeSeriesData(timestamps, values);
    }

    @Test
    void annualAggregationUnderSeasonalMaskCoversSelectedMonthsOnly() {
        TimeSeriesData annual = TimeSeriesAggregator.aggregate(
            daily2020(LocalDate.of(2021, 1, 1)), AggregationPeriod.ANNUAL_JAN_DEC,
            AggregationMethod.SUM, SeasonalMaskMode.of(Set.of(Month.JANUARY)));

        assertEquals(1, annual.getPointCount());
        assertEquals(31.0, annual.getValues()[0], 0.0,
            "only January's days are summed - the year is not judged incomplete for the rest");
    }

    @Test
    void deselectingThePeriodStartMonthStillYieldsAValue() {
        TimeSeriesData annual = TimeSeriesAggregator.aggregate(
            daily2020(LocalDate.of(2021, 1, 1)), AggregationPeriod.ANNUAL_JAN_DEC,
            AggregationMethod.SUM, SeasonalMaskMode.of(Set.of(Month.FEBRUARY)));

        assertEquals(29.0, annual.getValues()[0], 0.0,
            "the annual point is stamped 1 Jan, but January being deselected must not empty it");
    }

    @Test
    void seasonalMaskUnderNativeResolutionFiltersPoints() {
        TimeSeriesData masked = TimeSeriesAggregator.aggregate(
            daily2020(LocalDate.of(2020, 3, 1)), AggregationPeriod.ORIGINAL,
            AggregationMethod.SUM, SeasonalMaskMode.of(Set.of(Month.FEBRUARY)));

        assertEquals(29, masked.getPointCount(),
            "ORIGINAL aggregates nothing, but the mask still applies");
    }

    @Test
    void monthlyAggregationEmitsGapsForWhollyExcludedMonths() {
        // Two full years; only January and February selected.
        TimeSeriesData monthly = TimeSeriesAggregator.aggregate(
            daily(LocalDate.of(2020, 1, 1), LocalDate.of(2022, 1, 1)), AggregationPeriod.MONTHLY,
            AggregationMethod.SUM, SeasonalMaskMode.of(Set.of(Month.JANUARY, Month.FEBRUARY)));

        // Leading and trailing excluded months are trimmed (so the output runs Jan 2020 to
        // Feb 2021); interior ones survive as NaN so the calendar grid stays whole.
        assertEquals(14, monthly.getPointCount());
        assertEquals(31.0, monthly.getValues()[0], 0.0);
        assertEquals(29.0, monthly.getValues()[1], 0.0);
        for (int i = 2; i < 12; i++) {
            assertTrue(Double.isNaN(monthly.getValues()[i]),
                "March-December are excluded outright, so the month has nothing to report");
        }
        assertEquals(31.0, monthly.getValues()[12], 0.0);
        assertEquals(28.0, monthly.getValues()[13], 0.0);
    }

    @Test
    void monthlyGapsKeepTheCalendarGridUnbroken() {
        // The NaN placeholders are load-bearing, not padding: calendar periods carry no
        // nominal interval, so the renderer's gap heuristic never fires on them and only a
        // missing point breaks the line. Every month between the first and last output must
        // be present, or an excluded season would render as a straight bridge instead.
        TimeSeriesData monthly = TimeSeriesAggregator.aggregate(
            daily(LocalDate.of(2020, 1, 1), LocalDate.of(2022, 1, 1)), AggregationPeriod.MONTHLY,
            AggregationMethod.SUM, SeasonalMaskMode.of(Set.of(Month.JUNE, Month.JULY)));

        long[] timestamps = monthly.getTimestamps();
        LocalDate expected = LocalDate.of(2020, 6, 1);
        for (long timestamp : timestamps) {
            assertEquals(expected, LocalDate.ofEpochDay(Math.floorDiv(timestamp, 86_400_000L)),
                "consecutive calendar months, with no month skipped");
            expected = expected.plusMonths(1);
        }
        assertEquals(LocalDate.of(2021, 8, 1), expected, "trailing excluded months are trimmed");
    }

    @Test
    void dailyAggregationTreatsExcludedMonthsTheSameWayAsMonthly() {
        // A daily bucket cannot straddle a month boundary either, so the same rule applies:
        // days in an unselected month have nothing to report and come back as NaN.
        TimeSeriesData daily = TimeSeriesAggregator.aggregate(
            daily(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 4, 1)), AggregationPeriod.DAILY,
            AggregationMethod.SUM, SeasonalMaskMode.of(Set.of(Month.JANUARY, Month.MARCH)));

        assertEquals(91, daily.getPointCount(), "1 Jan through 31 Mar, grid intact");
        assertEquals(1.0, daily.getValues()[0], 0.0);
        for (int i = 31; i < 60; i++) {
            assertTrue(Double.isNaN(daily.getValues()[i]), "February is excluded outright");
        }
        assertEquals(1.0, daily.getValues()[60], 0.0);
    }

    /** Daily points valued 1, from {@code start} up to (not including) {@code endExclusive}. */
    private static TimeSeriesData daily(LocalDate start, LocalDate endExclusive) {
        int days = (int) (endExclusive.toEpochDay() - start.toEpochDay());
        long[] timestamps = new long[days];
        double[] values = new double[days];
        for (int i = 0; i < days; i++) {
            timestamps[i] = start.plusDays(i).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            values[i] = 1;
        }
        return new TimeSeriesData(timestamps, values);
    }

    @Test
    void truncationIsStillIncompleteUnderASeasonalMask() {
        // Data stops 15 January, so the selected month is only half present.
        TimeSeriesData annual = TimeSeriesAggregator.aggregate(
            daily2020(LocalDate.of(2020, 1, 16)), AggregationPeriod.ANNUAL_JAN_DEC,
            AggregationMethod.SUM, SeasonalMaskMode.of(Set.of(Month.JANUARY)));

        assertTrue(Double.isNaN(annual.getValues()[0]),
            "a genuinely truncated January must still read NaN, not an under-reported sum");
    }

    // === Seasonal mask on long records: the completeness window before 1970 ===

    /**
     * Month starts are epoch millis, negative before 1970. The narrowed completeness
     * window used a {@code -1} "no selected month" sentinel behind a {@code >= 0} test, so
     * every valid pre-1970 answer was discarded and the period fell back to the unmasked
     * test: an edge year that merely STARTED in a deselected month read NaN. Long gauge
     * records routinely begin in the 1880s, so this is the common case, not the corner.
     */
    @Test
    void seasonalCompletenessNarrowsForRecordsStartingBefore1970() {
        // Record commissioned 1 March 1890; June is fully present in every year.
        TimeSeriesData annual = TimeSeriesAggregator.aggregate(
            daily(LocalDate.of(1890, 3, 1), LocalDate.of(1893, 1, 1)), AggregationPeriod.ANNUAL_JAN_DEC,
            AggregationMethod.SUM, SeasonalMaskMode.of(Set.of(Month.JUNE)));

        assertEquals(3, annual.getPointCount());
        assertEquals(30.0, annual.getValues()[0], 0.0,
            "1890 starts in March, but June is all that was asked for and June is complete");
        assertEquals(30.0, annual.getValues()[1], 0.0);
        assertEquals(30.0, annual.getValues()[2], 0.0);
    }

    @Test
    void seasonalCompletenessNarrowsForRecordsEndingBefore1970() {
        // Record ends 30 September 1965: the final year is truncated, but not in June.
        TimeSeriesData annual = TimeSeriesAggregator.aggregate(
            daily(LocalDate.of(1963, 1, 1), LocalDate.of(1965, 10, 1)), AggregationPeriod.ANNUAL_JAN_DEC,
            AggregationMethod.SUM, SeasonalMaskMode.of(Set.of(Month.JUNE)));

        assertEquals(3, annual.getPointCount());
        assertEquals(30.0, annual.getValues()[2], 0.0,
            "1965 ends in September, after the only selected month, so it is complete");
    }

    @Test
    void seasonalCompletenessIsContinuousAcrossTheEpoch() {
        // WY1969 (Jul 1969 - Jun 1970) straddles 1 Jan 1970 (epoch zero). December 1969
        // is entirely before the epoch, January 1970 starts exactly at it: both must be
        // judged the same way, or the mask behaves differently either side of a date
        // that means nothing hydrologically.
        TimeSeriesData series = daily(LocalDate.of(1969, 9, 1), LocalDate.of(1972, 7, 1));

        TimeSeriesData december = TimeSeriesAggregator.aggregate(series,
            AggregationPeriod.ANNUAL_JUL_JUN, AggregationMethod.SUM,
            SeasonalMaskMode.of(Set.of(Month.DECEMBER)));
        TimeSeriesData january = TimeSeriesAggregator.aggregate(series,
            AggregationPeriod.ANNUAL_JUL_JUN, AggregationMethod.SUM,
            SeasonalMaskMode.of(Set.of(Month.JANUARY)));

        assertEquals(31.0, december.getValues()[0], 0.0, "WY1969 starts in September, but December is complete");
        assertEquals(31.0, january.getValues()[0], 0.0, "and so is January 1970");
    }

    @Test
    void truncationIsStillIncompleteBefore1970() {
        // The narrowed window must still catch a genuinely truncated selected month:
        // data stops 15 June 1890.
        TimeSeriesData annual = TimeSeriesAggregator.aggregate(
            daily(LocalDate.of(1890, 1, 1), LocalDate.of(1890, 6, 16)), AggregationPeriod.ANNUAL_JAN_DEC,
            AggregationMethod.SUM, SeasonalMaskMode.of(Set.of(Month.JUNE)));

        assertTrue(Double.isNaN(annual.getValues()[0]), "half of June is present: incomplete");
    }

    // === Seasonal mask: the claims the comments make, pinned ===

    @Test
    void aNaNInsideADeselectedMonthDoesNotPoisonThePeriod() {
        // The whole point of masking inside aggregation: a deselected month is absent on
        // purpose, so its missing data must not NaN the period the way it would unmasked.
        long[] timestamps = new long[366];
        double[] values = new double[366];
        LocalDate start = LocalDate.of(2020, 1, 1);
        for (int i = 0; i < 366; i++) {
            LocalDate d = start.plusDays(i);
            timestamps[i] = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            values[i] = d.getMonth() == Month.FEBRUARY ? Double.NaN : 1;
        }
        TimeSeriesData series = new TimeSeriesData(timestamps, values);

        TimeSeriesData unmasked = TimeSeriesAggregator.aggregate(series,
            AggregationPeriod.ANNUAL_JAN_DEC, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);
        TimeSeriesData masked = TimeSeriesAggregator.aggregate(series,
            AggregationPeriod.ANNUAL_JAN_DEC, AggregationMethod.SUM,
            SeasonalMaskMode.of(Set.of(Month.JUNE)));

        assertTrue(Double.isNaN(unmasked.getValues()[0]), "unmasked, a NaN February NaNs the year");
        assertEquals(30.0, masked.getValues()[0], 0.0,
            "with February deselected its NaNs are neither counted nor treated as missing");
    }

    @Test
    void nonJanuaryWaterYearAggregatesTheSelectedMonthsOfItsOwnSpan() {
        // A Jul-Jun water year with June and July selected: July belongs to the START of
        // the water year and June to its END, so WY2019 sums Jul 2019 and Jun 2020.
        TimeSeriesData annual = TimeSeriesAggregator.aggregate(
            daily(LocalDate.of(2019, 7, 1), LocalDate.of(2021, 7, 1)), AggregationPeriod.ANNUAL_JUL_JUN,
            AggregationMethod.SUM, SeasonalMaskMode.of(Set.of(Month.JUNE, Month.JULY)));

        assertEquals(2, annual.getPointCount());
        assertEquals(61.0, annual.getValues()[0], 0.0, "WY2019: 31 (Jul 2019) + 30 (Jun 2020)");
        assertEquals(61.0, annual.getValues()[1], 0.0, "WY2020: 31 (Jul 2020) + 30 (Jun 2021)");
    }

    @Test
    void subDailyDataUnderASeasonalMaskSumsTheSelectedMonthsHours() {
        // Hourly data for January and February 2020; only January selected.
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        int hours = (31 + 29) * 24;
        long[] timestamps = new long[hours];
        double[] values = new double[hours];
        for (int i = 0; i < hours; i++) {
            timestamps[i] = start.plusHours(i).toInstant(ZoneOffset.UTC).toEpochMilli();
            values[i] = 1;
        }
        TimeSeriesData hourly = new TimeSeriesData(timestamps, values);

        TimeSeriesData annual = TimeSeriesAggregator.aggregate(hourly,
            AggregationPeriod.ANNUAL_JAN_DEC, AggregationMethod.SUM,
            SeasonalMaskMode.of(Set.of(Month.JANUARY)));

        assertEquals(1, annual.getPointCount());
        assertEquals(31 * 24.0, annual.getValues()[0], 0.0, "every hour of January, none of February");
    }
}
