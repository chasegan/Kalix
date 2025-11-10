package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.TimeSeriesData;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

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

        if (period == AggregationPeriod.MONTHLY) {
            return aggregateMonthly(original, method);
        } else if (period.isAnnual()) {
            return aggregateAnnual(original, method, period.getStartMonth());
        }

        return original;
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
        Set<YearMonth> bucketsWithMissingData = new java.util.HashSet<>();

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

        // Aggregate each month
        List<LocalDateTime> aggregatedDates = new ArrayList<>();
        List<Double> aggregatedValues = new ArrayList<>();

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

            aggregatedDates.add(periodStart);
            aggregatedValues.add(aggregatedValue);
        }

        // Convert to arrays
        LocalDateTime[] dateArray = aggregatedDates.toArray(new LocalDateTime[0]);
        double[] valueArray = aggregatedValues.stream().mapToDouble(Double::doubleValue).toArray();

        return new TimeSeriesData(original.getName(), dateArray, valueArray);
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
        Set<Integer> bucketsWithMissingData = new java.util.HashSet<>();

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

        // Aggregate each year
        List<LocalDateTime> aggregatedDates = new ArrayList<>();
        List<Double> aggregatedValues = new ArrayList<>();

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

            aggregatedDates.add(periodStart);
            aggregatedValues.add(aggregatedValue);
        }

        // Convert to arrays
        LocalDateTime[] dateArray = aggregatedDates.toArray(new LocalDateTime[0]);
        double[] valueArray = aggregatedValues.stream().mapToDouble(Double::doubleValue).toArray();

        return new TimeSeriesData(original.getName(), dateArray, valueArray);
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
}
