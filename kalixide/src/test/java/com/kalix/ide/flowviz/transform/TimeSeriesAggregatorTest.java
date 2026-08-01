package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.TimeSeriesData;
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
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM);

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
            hourly, AggregationPeriod.DAILY, AggregationMethod.MEAN);

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
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM);

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
            daily, AggregationPeriod.DAILY, AggregationMethod.SUM);

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
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM);

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
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM);

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
            hourly, AggregationPeriod.DAILY, AggregationMethod.MIN);
        TimeSeriesData max = TimeSeriesAggregator.aggregate(
            hourly, AggregationPeriod.DAILY, AggregationMethod.MAX);

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
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM);

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
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM);

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
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM);

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
            dailyData, AggregationPeriod.MONTHLY, AggregationMethod.SUM);

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
            dailyData, AggregationPeriod.MONTHLY, AggregationMethod.SUM);

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
            dailyData, AggregationPeriod.MONTHLY, AggregationMethod.SUM);

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
            dailyData, AggregationPeriod.ANNUAL_JUL_JUN, AggregationMethod.SUM);

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
            dailyData, AggregationPeriod.ANNUAL_JUL_JUN, AggregationMethod.SUM);

        assertEquals(2, annual.getPointCount());
        assertEquals(366.0, annual.getValues()[0], 1e-9, "Complete WY2019");
        assertTrue(Double.isNaN(annual.getValues()[1]), "Partial WY2020 should be NaN");
    }

    @Test
    void emptySeriesReturnsOriginal() {
        TimeSeriesData empty = new TimeSeriesData(new long[0], new double[0]);
        assertSame(empty, TimeSeriesAggregator.aggregate(
            empty, AggregationPeriod.DAILY, AggregationMethod.SUM));
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
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM);

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
            hourly, AggregationPeriod.DAILY, AggregationMethod.SUM);

        assertEquals(3, daily.getPointCount(), "missing day 2 is emitted as a gap, not dropped");
        assertTrue(daily.isContiguous(), "the filled daily grid is contiguous");
        assertEquals(24.0, daily.getValues()[0], 1e-9, "day 1 sum");
        assertFalse(daily.getValidPoints()[1], "fully-missing day 2 is a NaN slot");
        assertEquals(48.0, daily.getValues()[2], 1e-9, "day 3 sum");
    }
}
