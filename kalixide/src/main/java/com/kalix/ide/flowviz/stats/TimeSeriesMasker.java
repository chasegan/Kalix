package com.kalix.ide.flowviz.stats;

import com.kalix.ide.flowviz.data.TimeSeriesData;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utilities for creating and applying masks to time series data.
 * A mask is a time series with values of 1.0 (included) or NaN (excluded).
 * Masking allows statistics to be computed only on timestamps where certain conditions are met.
 */
public class TimeSeriesMasker {

    /**
     * Creates a mask that is 1.0 only at timestamps where ALL series have valid data.
     * The mask will have all unique timestamps from all series, with 1.0 where all have data, NaN otherwise.
     *
     * @param allSeries List of all series to consider
     * @return A mask series with 1.0 at valid timestamps, NaN at excluded timestamps
     */
    public static TimeSeriesData createAllMask(List<TimeSeriesData> allSeries) {
        if (allSeries == null || allSeries.isEmpty()) {
            // Empty mask
            return new TimeSeriesData("mask", new LocalDateTime[0], new double[0]);
        }

        // Collect all unique timestamps and their validity across all series
        Map<Long, Boolean> timestampValidity = new LinkedHashMap<>();

        // Initialize with first series
        TimeSeriesData firstSeries = allSeries.get(0);
        long[] firstTimestamps = firstSeries.getTimestamps();
        boolean[] firstValidPoints = firstSeries.getValidPoints();

        for (int i = 0; i < firstSeries.getPointCount(); i++) {
            timestampValidity.put(firstTimestamps[i], firstValidPoints[i]);
        }

        // Intersect with remaining series
        for (int seriesIdx = 1; seriesIdx < allSeries.size(); seriesIdx++) {
            TimeSeriesData series = allSeries.get(seriesIdx);
            long[] timestamps = series.getTimestamps();
            boolean[] validPoints = series.getValidPoints();

            // Build map of timestamps to validity for this series
            Map<Long, Boolean> seriesValidity = new HashMap<>();
            for (int i = 0; i < series.getPointCount(); i++) {
                seriesValidity.put(timestamps[i], validPoints[i]);
            }

            // Union: add any timestamps from this series not yet in the map
            for (long timestamp : timestamps) {
                timestampValidity.putIfAbsent(timestamp, false);
            }

            // Intersect validity: timestamp is valid only if valid in ALL series so far
            for (Long timestamp : timestampValidity.keySet()) {
                boolean currentValidity = timestampValidity.get(timestamp);
                boolean seriesHasValid = seriesValidity.getOrDefault(timestamp, false);
                timestampValidity.put(timestamp, currentValidity && seriesHasValid);
            }
        }

        // Convert to arrays
        return buildMaskFromValidityMap(timestampValidity, "mask_all");
    }

    /**
     * Creates a mask that is 1.0 only at timestamps where BOTH the reference and series have valid data.
     * The mask will have all unique timestamps from both series, with 1.0 where both have data, NaN otherwise.
     *
     * @param reference The reference series
     * @param series The series to mask with reference
     * @return A mask series with 1.0 at valid timestamps, NaN at excluded timestamps
     */
    public static TimeSeriesData createEachMask(TimeSeriesData reference, TimeSeriesData series) {
        if (reference == null || series == null) {
            // Empty mask
            return new TimeSeriesData("mask", new LocalDateTime[0], new double[0]);
        }

        // Collect union of timestamps and validity for both series
        Map<Long, Boolean> timestampValidity = new LinkedHashMap<>();

        // Add reference timestamps
        long[] refTimestamps = reference.getTimestamps();
        boolean[] refValidPoints = reference.getValidPoints();
        for (int i = 0; i < reference.getPointCount(); i++) {
            timestampValidity.put(refTimestamps[i], refValidPoints[i]);
        }

        // Build map for series
        long[] seriesTimestamps = series.getTimestamps();
        boolean[] seriesValidPoints = series.getValidPoints();
        Map<Long, Boolean> seriesValidity = new HashMap<>();
        for (int i = 0; i < series.getPointCount(); i++) {
            seriesValidity.put(seriesTimestamps[i], seriesValidPoints[i]);
        }

        // Union timestamps
        for (long timestamp : seriesTimestamps) {
            timestampValidity.putIfAbsent(timestamp, false);
        }

        // Intersect validity: valid only if valid in BOTH
        for (Long timestamp : timestampValidity.keySet()) {
            boolean refValid = timestampValidity.get(timestamp);
            boolean serValid = seriesValidity.getOrDefault(timestamp, false);
            timestampValidity.put(timestamp, refValid && serValid);
        }

        // Convert to arrays
        return buildMaskFromValidityMap(timestampValidity, "mask_each");
    }

    /**
     * Applies a mask to a time series, returning a new series with only masked-in points.
     * Points where the mask is NaN are excluded from the result.
     *
     * @param series The series to mask
     * @param mask The mask to apply (1.0 = include, NaN = exclude)
     * @return A new series with only masked-in points
     */
    public static TimeSeriesData applyMask(TimeSeriesData series, TimeSeriesData mask) {
        if (series == null) {
            return new TimeSeriesData("masked", new LocalDateTime[0], new double[0]);
        }

        if (mask == null) {
            // No mask = return original series
            return series;
        }

        // Build map of mask timestamps to validity
        Map<Long, Boolean> maskValidity = new HashMap<>();
        long[] maskTimestamps = mask.getTimestamps();
        boolean[] maskValidPoints = mask.getValidPoints();

        for (int i = 0; i < mask.getPointCount(); i++) {
            maskValidity.put(maskTimestamps[i], maskValidPoints[i]);
        }

        // Filter series points
        List<LocalDateTime> filteredTimes = new ArrayList<>();
        List<Double> filteredValues = new ArrayList<>();

        long[] seriesTimestamps = series.getTimestamps();
        double[] seriesValues = series.getValues();

        for (int i = 0; i < series.getPointCount(); i++) {
            long timestamp = seriesTimestamps[i];
            // Include point if mask says it's valid (true in maskValidity map)
            if (maskValidity.getOrDefault(timestamp, false)) {
                filteredTimes.add(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(timestamp), ZoneOffset.UTC));
                filteredValues.add(seriesValues[i]);
            }
        }

        // Convert to arrays
        LocalDateTime[] timesArray = filteredTimes.toArray(new LocalDateTime[0]);
        double[] valuesArray = filteredValues.stream().mapToDouble(Double::doubleValue).toArray();

        return new TimeSeriesData(series.getName(), timesArray, valuesArray);
    }

    /**
     * Helper method to build a TimeSeriesData mask from a validity map.
     */
    private static TimeSeriesData buildMaskFromValidityMap(Map<Long, Boolean> timestampValidity, String maskName) {
        List<Long> sortedTimestamps = new ArrayList<>(timestampValidity.keySet());
        Collections.sort(sortedTimestamps);

        LocalDateTime[] maskTimes = new LocalDateTime[sortedTimestamps.size()];
        double[] maskValues = new double[sortedTimestamps.size()];

        for (int i = 0; i < sortedTimestamps.size(); i++) {
            long timestamp = sortedTimestamps.get(i);
            maskTimes[i] = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp), ZoneOffset.UTC);
            // 1.0 if valid, NaN if not
            maskValues[i] = timestampValidity.get(timestamp) ? 1.0 : Double.NaN;
        }

        return new TimeSeriesData(maskName, maskTimes, maskValues);
    }
}
