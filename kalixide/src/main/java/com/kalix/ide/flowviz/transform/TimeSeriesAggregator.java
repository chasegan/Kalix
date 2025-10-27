package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.TimeSeriesData;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

        // Group data points by year-month
        Map<YearMonth, List<Double>> monthlyBuckets = new TreeMap<>();

        for (int i = 0; i < timestamps.length; i++) {
            if (!validPoints[i]) {
                continue; // Skip invalid points
            }

            LocalDateTime dateTime = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamps[i]),
                ZoneOffset.UTC
            );

            YearMonth yearMonth = new YearMonth(dateTime.getYear(), dateTime.getMonthValue());
            monthlyBuckets.computeIfAbsent(yearMonth, k -> new ArrayList<>()).add(values[i]);
        }

        // Aggregate each month
        List<LocalDateTime> aggregatedDates = new ArrayList<>();
        List<Double> aggregatedValues = new ArrayList<>();

        for (Map.Entry<YearMonth, List<Double>> entry : monthlyBuckets.entrySet()) {
            YearMonth ym = entry.getKey();
            List<Double> monthValues = entry.getValue();

            // Use start of month (1st at 00:00:00) as timestamp
            // Follows convention: timestamp represents start of aggregation period
            LocalDateTime startOfMonth = LocalDateTime.of(ym.year, ym.month, 1, 0, 0);
            double aggregatedValue = method.aggregate(monthValues);

            aggregatedDates.add(startOfMonth);
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

        // Group data points by water year
        Map<Integer, List<Double>> annualBuckets = new TreeMap<>();

        for (int i = 0; i < timestamps.length; i++) {
            if (!validPoints[i]) {
                continue; // Skip invalid points
            }

            LocalDateTime dateTime = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamps[i]),
                ZoneOffset.UTC
            );

            // Calculate water year based on start month
            int waterYear = calculateWaterYear(dateTime, startMonth);
            annualBuckets.computeIfAbsent(waterYear, k -> new ArrayList<>()).add(values[i]);
        }

        // Aggregate each year
        List<LocalDateTime> aggregatedDates = new ArrayList<>();
        List<Double> aggregatedValues = new ArrayList<>();

        for (Map.Entry<Integer, List<Double>> entry : annualBuckets.entrySet()) {
            int waterYear = entry.getKey();
            List<Double> yearValues = entry.getValue();

            // Use start of water year (1st of start month at 00:00:00) as timestamp
            // Follows convention: timestamp represents start of aggregation period
            // E.g., for Jul-Jun water year 2020, timestamp is 2020-07-01 00:00:00
            LocalDateTime startOfWaterYear = LocalDateTime.of(waterYear, startMonth, 1, 0, 0);

            double aggregatedValue = method.aggregate(yearValues);

            aggregatedDates.add(startOfWaterYear);
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
            if (!(o instanceof YearMonth)) return false;
            YearMonth that = (YearMonth) o;
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
