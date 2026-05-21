package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

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
}
