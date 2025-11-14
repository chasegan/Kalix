package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Transforms time series data according to plot type.
 * Transformations are applied after aggregation but before Y-axis scaling.
 */
public class PlotTypeTransformer {
    private static final Logger logger = LoggerFactory.getLogger(PlotTypeTransformer.class);

    /**
     * Transforms a dataset according to the specified plot type.
     *
     * @param input The aggregated dataset
     * @param type The plot type transformation
     * @param selectedSeriesKeys Ordered list of selected series (first is reference for DIFFERENCE types)
     * @return Transformed dataset
     */
    public static DataSet transform(DataSet input, PlotType type, List<String> selectedSeriesKeys) {
        // Validate inputs
        if (input == null || type == null) {
            logger.warn("Invalid input to transform: input={}, type={}", input, type);
            return input != null ? input : new DataSet();
        }

        // If no series selected or type is VALUES, return unchanged
        if (selectedSeriesKeys == null || selectedSeriesKeys.isEmpty() || type == PlotType.VALUES) {
            return input;
        }

        try {
            return switch (type) {
                case VALUES -> input;
                case CUMULATIVE -> transformCumulative(input, selectedSeriesKeys);
                case DIFFERENCE -> transformDifference(input, selectedSeriesKeys);
                case CUMULATIVE_DIFFERENCE -> transformCumulativeDifference(input, selectedSeriesKeys);
                case EXCEEDANCE -> transformExceedance(input, selectedSeriesKeys);
            };
        } catch (Exception e) {
            logger.error("Error transforming dataset with plot type " + type, e);
            return input;
        }
    }

    /**
     * Transforms each series to cumulative sum over time.
     * Cumulative starts at the first value, so output has same length as input.
     * NaN values do not contribute to the running total but result in NaN at that position.
     */
    private static DataSet transformCumulative(DataSet input, List<String> selectedSeriesKeys) {
        DataSet result = new DataSet();

        for (String seriesKey : selectedSeriesKeys) {
            TimeSeriesData series = input.getSeries(seriesKey);
            if (series == null) {
                continue;
            }

            long[] timestamps = series.getTimestamps();
            double[] values = series.getValues();
            boolean[] validPoints = series.getValidPoints();

            // Calculate cumulative sum
            double[] cumulativeValues = new double[values.length];
            double runningTotal = 0.0;

            for (int i = 0; i < values.length; i++) {
                if (validPoints[i]) {
                    runningTotal += values[i];
                    cumulativeValues[i] = runningTotal;
                } else {
                    // Missing value: output NaN but don't affect running total
                    cumulativeValues[i] = Double.NaN;
                }
            }

            // Create new series with cumulative values
            LocalDateTime[] dateTimes = timestampsToDateTimes(timestamps);
            TimeSeriesData cumulativeSeries = new TimeSeriesData(
                series.getName(),
                dateTimes,
                cumulativeValues
            );

            result.addSeries(cumulativeSeries);
        }

        return result;
    }

    /**
     * Transforms each series to show difference from reference series.
     * Reference series is the first in selectedSeriesKeys and is shown as a zero line.
     * Calculation: answer[i] = value[i] - reference[i]
     * Only exact timestamp matches are used. Missing values in either series result in NaN.
     */
    private static DataSet transformDifference(DataSet input, List<String> selectedSeriesKeys) {
        if (selectedSeriesKeys.isEmpty()) {
            return new DataSet();
        }

        DataSet result = new DataSet();

        // Get reference series (first selected series)
        String referenceKey = selectedSeriesKeys.get(0);
        TimeSeriesData referenceSeries = input.getSeries(referenceKey);
        if (referenceSeries == null) {
            logger.warn("Reference series not found: {}", referenceKey);
            return new DataSet();
        }

        // Build timestamp -> value map for reference series
        long[] refTimestamps = referenceSeries.getTimestamps();
        double[] refValues = referenceSeries.getValues();
        boolean[] refValidPoints = referenceSeries.getValidPoints();

        java.util.Map<Long, Double> referenceMap = new java.util.HashMap<>();
        for (int i = 0; i < refTimestamps.length; i++) {
            // Only add valid points; invalid points effectively don't exist in the map
            if (refValidPoints[i]) {
                referenceMap.put(refTimestamps[i], refValues[i]);
            } else {
                // Mark invalid with NaN
                referenceMap.put(refTimestamps[i], Double.NaN);
            }
        }

        // Process each series
        for (String seriesKey : selectedSeriesKeys) {
            TimeSeriesData series = input.getSeries(seriesKey);
            if (series == null) {
                continue;
            }

            long[] timestamps = series.getTimestamps();
            double[] values = series.getValues();
            boolean[] validPoints = series.getValidPoints();

            // Calculate differences
            double[] differences = new double[timestamps.length];

            for (int i = 0; i < timestamps.length; i++) {
                long timestamp = timestamps[i];

                // Check if reference has this timestamp
                if (!referenceMap.containsKey(timestamp)) {
                    differences[i] = Double.NaN;
                    continue;
                }

                double refValue = referenceMap.get(timestamp);

                // If either value is invalid, result is NaN
                if (!validPoints[i] || Double.isNaN(refValue)) {
                    differences[i] = Double.NaN;
                } else {
                    differences[i] = values[i] - refValue;
                }
            }

            // Create new series with difference values
            LocalDateTime[] dateTimes = timestampsToDateTimes(timestamps);
            TimeSeriesData differenceSeries = new TimeSeriesData(
                series.getName(),
                dateTimes,
                differences
            );

            result.addSeries(differenceSeries);
        }

        return result;
    }

    /**
     * Transforms each series to cumulative difference from reference series.
     * Reference series is the first in selectedSeriesKeys and is shown as a zero line.
     * Calculation: cumsum(series[i] - reference[i])
     * Only exact timestamp matches are used. Missing values in either series result in NaN.
     */
    private static DataSet transformCumulativeDifference(DataSet input, List<String> selectedSeriesKeys) {
        if (selectedSeriesKeys.isEmpty()) {
            return new DataSet();
        }

        DataSet result = new DataSet();

        // Get reference series (first selected series)
        String referenceKey = selectedSeriesKeys.get(0);
        TimeSeriesData referenceSeries = input.getSeries(referenceKey);
        if (referenceSeries == null) {
            logger.warn("Reference series not found: {}", referenceKey);
            return new DataSet();
        }

        // Build timestamp -> value map for reference series
        long[] refTimestamps = referenceSeries.getTimestamps();
        double[] refValues = referenceSeries.getValues();
        boolean[] refValidPoints = referenceSeries.getValidPoints();

        java.util.Map<Long, Double> referenceMap = new java.util.HashMap<>();
        for (int i = 0; i < refTimestamps.length; i++) {
            if (refValidPoints[i]) {
                referenceMap.put(refTimestamps[i], refValues[i]);
            } else {
                referenceMap.put(refTimestamps[i], Double.NaN);
            }
        }

        // Process each series
        for (String seriesKey : selectedSeriesKeys) {
            TimeSeriesData series = input.getSeries(seriesKey);
            if (series == null) {
                continue;
            }

            long[] timestamps = series.getTimestamps();
            double[] values = series.getValues();
            boolean[] validPoints = series.getValidPoints();

            // Calculate cumulative differences
            double[] cumulativeDifferences = new double[timestamps.length];
            double runningTotal = 0.0;

            for (int i = 0; i < timestamps.length; i++) {
                long timestamp = timestamps[i];

                // Check if reference has this timestamp
                if (!referenceMap.containsKey(timestamp)) {
                    cumulativeDifferences[i] = Double.NaN;
                    continue;
                }

                double refValue = referenceMap.get(timestamp);

                // If either value is invalid, result is NaN (but don't update running total)
                if (!validPoints[i] || Double.isNaN(refValue)) {
                    cumulativeDifferences[i] = Double.NaN;
                } else {
                    double difference = values[i] - refValue;
                    runningTotal += difference;
                    cumulativeDifferences[i] = runningTotal;
                }
            }

            // Create new series with cumulative difference values
            LocalDateTime[] dateTimes = timestampsToDateTimes(timestamps);
            TimeSeriesData cumulativeDifferenceSeries = new TimeSeriesData(
                series.getName(),
                dateTimes,
                cumulativeDifferences
            );

            result.addSeries(cumulativeDifferenceSeries);
        }

        return result;
    }

    /**
     * Transforms each series to exceedance curve (sorted values with Cunnane plotting positions).
     *
     * Exceedance probability is calculated using the Cunnane formula with alpha=0.4:
     *   p = (rank - 0.4) / (n + 0.2) * 100%
     *
     * Values are sorted from largest to smallest (NaN values at bottom).
     * Plotting positions are stored as "fake timestamps" (percentile * 1,000,000) for rendering.
     * Each series is independent.
     */
    private static DataSet transformExceedance(DataSet input, List<String> selectedSeriesKeys) {
        DataSet result = new DataSet();

        for (String seriesKey : selectedSeriesKeys) {
            TimeSeriesData series = input.getSeries(seriesKey);
            if (series == null) {
                continue;
            }

            double[] values = series.getValues();
            boolean[] validPoints = series.getValidPoints();

            // Count valid (non-NaN) points
            int validCount = 0;
            for (boolean valid : validPoints) {
                if (valid) validCount++;
            }

            // Create array of indices for sorting
            Integer[] indices = new Integer[values.length];
            for (int i = 0; i < values.length; i++) {
                indices[i] = i;
            }

            // Sort indices by values (largest first), NaN at bottom
            java.util.Arrays.sort(indices, (a, b) -> {
                double valueA = values[a];
                double valueB = values[b];

                // NaN sorting: NaN values go to the end
                boolean nanA = Double.isNaN(valueA);
                boolean nanB = Double.isNaN(valueB);

                if (nanA && nanB) return 0;
                if (nanA) return 1;  // A is NaN, goes after B
                if (nanB) return -1; // B is NaN, A goes before B

                // Both valid: sort descending (largest first)
                return Double.compare(valueB, valueA);
            });

            // Build sorted values and calculate Cunnane plotting positions
            double[] sortedValues = new double[values.length];
            long[] percentileTimestamps = new long[values.length];

            int rank = 1; // 1-based ranking for Cunnane formula
            for (int i = 0; i < values.length; i++) {
                sortedValues[i] = values[indices[i]];

                if (!Double.isNaN(sortedValues[i])) {
                    // Cunnane formula: p = (rank - 0.4) / (n + 0.2)
                    double percentile = ((rank - 0.4) / (validCount + 0.2)) * 100.0;
                    rank++;

                    // Store as fake timestamp (multiply by 1,000,000)
                    percentileTimestamps[i] = (long)(percentile * 1_000_000);
                } else {
                    // NaN values: assign percentile beyond 100%
                    percentileTimestamps[i] = 101_000_000 + i;
                }
            }

            // Create new series with sorted values and percentile timestamps
            LocalDateTime[] dateTimes = timestampsToDateTimes(percentileTimestamps);
            TimeSeriesData exceedanceSeries = new TimeSeriesData(
                series.getName(),
                dateTimes,
                sortedValues
            );

            result.addSeries(exceedanceSeries);
        }

        return result;
    }

    /**
     * Utility: Converts timestamps array to LocalDateTime array.
     */
    private static LocalDateTime[] timestampsToDateTimes(long[] timestamps) {
        LocalDateTime[] dateTimes = new LocalDateTime[timestamps.length];
        for (int i = 0; i < timestamps.length; i++) {
            dateTimes[i] = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamps[i]),
                ZoneOffset.UTC
            );
        }
        return dateTimes;
    }
}
