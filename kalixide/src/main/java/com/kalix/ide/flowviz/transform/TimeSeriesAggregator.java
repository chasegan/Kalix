package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.TimeSeriesData;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * Aggregates time series data to coarser temporal resolutions.
 */
public class TimeSeriesAggregator {

    /**
     * Aggregates time series data according to the specified period and method.
     *
     * @param original Original time series data
     * @param period Aggregation period
     * @param method Aggregation method
     * @return Aggregated time series, or original if period is ORIGINAL
     */
    public static TimeSeriesData aggregate(
        TimeSeriesData original,
        AggregationPeriod period,
        AggregationMethod method
    ) {
        if (original == null || period == AggregationPeriod.ORIGINAL) {
            return original;
        }

        if (period == AggregationPeriod.DAILY) {
            return aggregateDaily(original, method);
        } else if (period == AggregationPeriod.MONTHLY) {
            return aggregateMonthly(original, method);
        } else if (period.isAnnual()) {
            return aggregateAnnual(original, method, period.getStartMonth());
        }

        return original;
    }

    /**
     * Builds the aggregated series from the per-period values, emitting a point for <em>every</em>
     * period from the first to the last present one — fully-missing interior periods become
     * {@link Double#NaN} rather than being dropped. This keeps the aggregated series' grid
     * complete so a missing period reads as a gap (via the renderer's NaN break) instead of a
     * straight bridge, and makes the daily output contiguous (one entry per calendar day).
     *
     * @param periodValues present periods (period-start → aggregated value), sorted ascending
     * @param nextPeriod   steps a period start to the next (plusDays/plusMonths/plusYears)
     */
    private static TimeSeriesData buildFillingGaps(
        TreeMap<LocalDateTime, Double> periodValues,
        UnaryOperator<LocalDateTime> nextPeriod
    ) {
        List<LocalDateTime> dates = new ArrayList<>();
        List<Double> values = new ArrayList<>();

        if (!periodValues.isEmpty()) {
            LocalDateTime last = periodValues.lastKey();
            for (LocalDateTime p = periodValues.firstKey(); !p.isAfter(last); p = nextPeriod.apply(p)) {
                dates.add(p);
                Double v = periodValues.get(p);
                values.add(v != null ? v : Double.NaN);  // fully-missing interior period
            }
        }

        LocalDateTime[] dateArray = dates.toArray(new LocalDateTime[0]);
        double[] valueArray = values.stream().mapToDouble(Double::doubleValue).toArray();
        return new TimeSeriesData(dateArray, valueArray);
    }

    /**
     * Aggregates data to daily resolution. Intended for sub-daily series (e.g. hourly).
     * On daily-or-coarser series each bucket holds one value, so the aggregation
     * methods become no-ops.
     */
    private static TimeSeriesData aggregateDaily(TimeSeriesData original, AggregationMethod method) {
        long[] timestamps = original.getTimestamps();
        double[] values = original.getValues();
        boolean[] validPoints = original.getValidPoints();

        if (timestamps.length == 0) {
            return original;
        }

        // Get series temporal bounds
        LocalDateTime seriesStart = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(timestamps[0]), ZoneOffset.UTC);
        LocalDateTime seriesEnd = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(timestamps[timestamps.length - 1]), ZoneOffset.UTC);

        // Group data points by calendar day
        Map<YearMonthDay, List<Double>> dailyBuckets = new TreeMap<>();
        Set<YearMonthDay> bucketsWithMissingData = new HashSet<>();

        for (int i = 0; i < timestamps.length; i++) {
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamps[i]),
                ZoneOffset.UTC
            );

            YearMonthDay ymd = new YearMonthDay(
                dateTime.getYear(), dateTime.getMonthValue(), dateTime.getDayOfMonth());

            if (!validPoints[i]) {
                bucketsWithMissingData.add(ymd);
                continue;
            }

            dailyBuckets.computeIfAbsent(ymd, k -> new ArrayList<>()).add(values[i]);
        }

        // Aggregate each day with valid data
        TreeMap<LocalDateTime, Double> periodValues = new TreeMap<>();

        for (Map.Entry<YearMonthDay, List<Double>> entry : dailyBuckets.entrySet()) {
            YearMonthDay ymd = entry.getKey();
            List<Double> dayValues = entry.getValue();

            // Period bounds: start and end at 00:00 of the day. Mirrors the monthly
            // approximation (which uses last-day-of-month at 00:00 as periodEnd).
            LocalDateTime periodStart = LocalDateTime.of(ymd.year, ymd.month, ymd.day, 0, 0);
            LocalDateTime periodEnd = periodStart;

            boolean isComplete = !periodStart.isBefore(seriesStart) && !periodEnd.isAfter(seriesEnd);

            double aggregatedValue = (bucketsWithMissingData.contains(ymd) || !isComplete)
                ? Double.NaN
                : method.aggregate(dayValues);

            periodValues.put(periodStart, aggregatedValue);
        }

        return buildFillingGaps(periodValues, p -> p.plusDays(1));
    }

    /**
     * Aggregates data to monthly resolution.
     */
    private static TimeSeriesData aggregateMonthly(TimeSeriesData original, AggregationMethod method) {
        long[] timestamps = original.getTimestamps();
        double[] values = original.getValues();
        boolean[] validPoints = original.getValidPoints();

        if (timestamps.length == 0) {
            return original;
        }

        // Get series temporal bounds
        LocalDateTime seriesStart = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(timestamps[0]), ZoneOffset.UTC);
        LocalDateTime seriesEnd = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(timestamps[timestamps.length - 1]), ZoneOffset.UTC);

        // Group data points by year-month
        Map<YearMonth, List<Double>> monthlyBuckets = new TreeMap<>();
        Set<YearMonth> bucketsWithMissingData = new HashSet<>();

        for (int i = 0; i < timestamps.length; i++) {
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamps[i]),
                ZoneOffset.UTC
            );

            YearMonth yearMonth = new YearMonth(dateTime.getYear(), dateTime.getMonthValue());

            if (!validPoints[i]) {
                // Mark this period as containing missing data
                bucketsWithMissingData.add(yearMonth);
                continue;
            }

            monthlyBuckets.computeIfAbsent(yearMonth, k -> new ArrayList<>()).add(values[i]);
        }

        // Aggregate each month with valid data
        TreeMap<LocalDateTime, Double> periodValues = new TreeMap<>();

        for (Map.Entry<YearMonth, List<Double>> entry : monthlyBuckets.entrySet()) {
            YearMonth ym = entry.getKey();
            List<Double> monthValues = entry.getValue();

            // Define aggregation period bounds
            LocalDateTime periodStart = LocalDateTime.of(ym.year, ym.month, 1, 0, 0);
            LocalDateTime periodEnd = periodStart.plusMonths(1).minusDays(1);

            // Check if period extends beyond series temporal bounds
            boolean isComplete = !periodStart.isBefore(seriesStart) && !periodEnd.isAfter(seriesEnd);

            // If period is incomplete or contains missing data, result is NaN
            double aggregatedValue = (bucketsWithMissingData.contains(ym) || !isComplete)
                ? Double.NaN
                : method.aggregate(monthValues);

            periodValues.put(periodStart, aggregatedValue);
        }

        return buildFillingGaps(periodValues, p -> p.plusMonths(1));
    }

    /**
     * Aggregates data to annual resolution with specified start month.
     *
     * @param startMonth Starting month (1-12) for the water year
     */
    private static TimeSeriesData aggregateAnnual(
        TimeSeriesData original,
        AggregationMethod method,
        int startMonth
    ) {
        long[] timestamps = original.getTimestamps();
        double[] values = original.getValues();
        boolean[] validPoints = original.getValidPoints();

        if (timestamps.length == 0) {
            return original;
        }

        // Get series temporal bounds
        LocalDateTime seriesStart = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(timestamps[0]), ZoneOffset.UTC);
        LocalDateTime seriesEnd = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(timestamps[timestamps.length - 1]), ZoneOffset.UTC);

        // Group data points by water year
        Map<Integer, List<Double>> annualBuckets = new TreeMap<>();
        Set<Integer> bucketsWithMissingData = new HashSet<>();

        for (int i = 0; i < timestamps.length; i++) {
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamps[i]),
                ZoneOffset.UTC
            );

            // Calculate water year based on start month
            int waterYear = calculateWaterYear(dateTime, startMonth);

            if (!validPoints[i]) {
                // Mark this period as containing missing data
                bucketsWithMissingData.add(waterYear);
                continue;
            }

            annualBuckets.computeIfAbsent(waterYear, k -> new ArrayList<>()).add(values[i]);
        }

        // Aggregate each year with valid data
        TreeMap<LocalDateTime, Double> periodValues = new TreeMap<>();

        for (Map.Entry<Integer, List<Double>> entry : annualBuckets.entrySet()) {
            int waterYear = entry.getKey();
            List<Double> yearValues = entry.getValue();

            // Define aggregation period bounds
            // E.g., for Jul-Jun water year 2020: 2020-07-01 to 2021-06-30
            LocalDateTime periodStart = LocalDateTime.of(waterYear, startMonth, 1, 0, 0);
            LocalDateTime periodEnd = periodStart.plusYears(1).minusDays(1);

            // Check if period extends beyond series temporal bounds
            boolean isComplete = !periodStart.isBefore(seriesStart) && !periodEnd.isAfter(seriesEnd);

            // If period is incomplete or contains missing data, result is NaN
            double aggregatedValue = (bucketsWithMissingData.contains(waterYear) || !isComplete)
                ? Double.NaN
                : method.aggregate(yearValues);

            periodValues.put(periodStart, aggregatedValue);
        }

        return buildFillingGaps(periodValues, p -> p.plusYears(1));
    }

    /**
     * Calculates the water year for a given date and start month.
     * For example, if startMonth=10 (October), then Oct 2020 - Sep 2021 = water year 2020.
     */
    private static int calculateWaterYear(LocalDateTime dateTime, int startMonth) {
        int year = dateTime.getYear();
        int month = dateTime.getMonthValue();

        // If we're before the start month, we're in the previous water year
        if (month < startMonth) {
            return year - 1;
        }

        return year;
    }

    /**
     * Simple year-month key for grouping.
     */
    private static class YearMonth implements Comparable<YearMonth> {
        final int year;
        final int month;

        YearMonth(int year, int month) {
            this.year = year;
            this.month = month;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof YearMonth that)) return false;
            return year == that.year && month == that.month;
        }

        @Override
        public int hashCode() {
            return Objects.hash(year, month);
        }

        @Override
        public int compareTo(YearMonth other) {
            int yearComp = Integer.compare(this.year, other.year);
            if (yearComp != 0) return yearComp;
            return Integer.compare(this.month, other.month);
        }
    }

    /**
     * Simple year-month-day key for grouping.
     */
    private static class YearMonthDay implements Comparable<YearMonthDay> {
        final int year;
        final int month;
        final int day;

        YearMonthDay(int year, int month, int day) {
            this.year = year;
            this.month = month;
            this.day = day;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof YearMonthDay that)) return false;
            return year == that.year && month == that.month && day == that.day;
        }

        @Override
        public int hashCode() {
            return Objects.hash(year, month, day);
        }

        @Override
        public int compareTo(YearMonthDay other) {
            int yearComp = Integer.compare(this.year, other.year);
            if (yearComp != 0) return yearComp;
            int monthComp = Integer.compare(this.month, other.month);
            if (monthComp != 0) return monthComp;
            return Integer.compare(this.day, other.day);
        }
    }
}
