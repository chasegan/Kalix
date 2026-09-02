package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Transforms time series data according to plot type.
 * Transformations are applied after aggregation but before Y-axis scaling.
 *
 * <p>All transforms work in primitive {@code long[]}/{@code double[]} space
 * (per manifestos/performance.md): output series are built via the primitive
 * {@link TimeSeriesData#TimeSeriesData(long[], double[])} constructor, and
 * reference-series alignment uses a linear two-pointer merge over the sorted
 * timestamp arrays (the same pattern as
 * {@link com.kalix.ide.flowviz.stats.TimeSeriesMasker}) — no per-point boxing
 * or hashing.</p>
 */
public class PlotTypeTransformer {
    private static final Logger logger = LoggerFactory.getLogger(PlotTypeTransformer.class);

    /**
     * Scale factor for encoding numeric values as fake timestamps.
     * Used by DOUBLE_MASS (and potentially other NUMERIC x-axis types).
     * Provides 6 decimal places of precision; max representable value ~9.2 × 10^12.
     */
    public static final long NUMERIC_SCALE = 1_000_000L;

    /**
     * Scale factor for encoding exceedance percentiles (0-100) as fake timestamps.
     * Six decimal places, like {@link #NUMERIC_SCALE}; the two are separate constants
     * because they encode different quantities and need not stay equal.
     */
    public static final long PERCENTILE_SCALE = 1_000_000L;

    /**
     * Transforms a dataset according to the specified plot type.
     *
     * @param input The aggregated dataset
     * @param type The plot type transformation
     * @param selectedSeriesKeys Ordered list of selected series (first is reference for DIFFERENCE types)
     * @return Transformed dataset
     */
    public static DataSet transform(DataSet input, PlotType type, List<SeriesRef> selectedSeriesKeys) {
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
                case DOUBLE_MASS -> transformDoubleMass(input, selectedSeriesKeys);
                case RESIDUAL_MASS -> transformResidualMass(input, selectedSeriesKeys);
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
    private static DataSet transformCumulative(DataSet input, List<SeriesRef> selectedSeriesKeys) {
        DataSet result = new DataSet();

        for (SeriesRef seriesKey : selectedSeriesKeys) {
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

            result.addSeries(seriesKey, new TimeSeriesData(timestamps, cumulativeValues));
        }

        return result;
    }

    /**
     * Transforms each series to show difference from reference series.
     * Reference series is the first in selectedSeriesKeys and is shown as a zero line.
     * Calculation: answer[i] = value[i] - reference[i]
     * Only exact timestamp matches are used. Missing values in either series result in NaN.
     */
    private static DataSet transformDifference(DataSet input, List<SeriesRef> selectedSeriesKeys) {
        if (selectedSeriesKeys.isEmpty()) {
            return new DataSet();
        }

        DataSet result = new DataSet();

        // Get reference series (first selected series)
        SeriesRef referenceKey = selectedSeriesKeys.get(0);
        TimeSeriesData referenceSeries = input.getSeries(referenceKey);
        if (referenceSeries == null) {
            logger.warn("Reference series not found: {}", referenceKey);
            return new DataSet();
        }

        // Process each series
        for (SeriesRef seriesKey : selectedSeriesKeys) {
            TimeSeriesData series = input.getSeries(seriesKey);
            if (series == null) {
                continue;
            }

            long[] timestamps = series.getTimestamps();
            double[] values = series.getValues();
            boolean[] validPoints = series.getValidPoints();

            // Reference values aligned onto this series' timestamps (NaN where absent/invalid)
            double[] alignedRef = alignReference(referenceSeries, timestamps);

            // Calculate differences
            double[] differences = new double[timestamps.length];

            for (int i = 0; i < timestamps.length; i++) {
                // If either value is missing or invalid, result is NaN
                if (!validPoints[i] || Double.isNaN(alignedRef[i])) {
                    differences[i] = Double.NaN;
                } else {
                    differences[i] = values[i] - alignedRef[i];
                }
            }

            result.addSeries(seriesKey, new TimeSeriesData(timestamps, differences));
        }

        return result;
    }

    /**
     * Transforms each series to cumulative difference from reference series.
     * Reference series is the first in selectedSeriesKeys and is shown as a zero line.
     * Calculation: cumsum(series[i] - reference[i])
     * Only exact timestamp matches are used. Missing values in either series result in NaN.
     */
    private static DataSet transformCumulativeDifference(DataSet input, List<SeriesRef> selectedSeriesKeys) {
        if (selectedSeriesKeys.isEmpty()) {
            return new DataSet();
        }

        DataSet result = new DataSet();

        // Get reference series (first selected series)
        SeriesRef referenceKey = selectedSeriesKeys.get(0);
        TimeSeriesData referenceSeries = input.getSeries(referenceKey);
        if (referenceSeries == null) {
            logger.warn("Reference series not found: {}", referenceKey);
            return new DataSet();
        }

        // Process each series
        for (SeriesRef seriesKey : selectedSeriesKeys) {
            TimeSeriesData series = input.getSeries(seriesKey);
            if (series == null) {
                continue;
            }

            long[] timestamps = series.getTimestamps();
            double[] values = series.getValues();
            boolean[] validPoints = series.getValidPoints();

            // Reference values aligned onto this series' timestamps (NaN where absent/invalid)
            double[] alignedRef = alignReference(referenceSeries, timestamps);

            // Calculate cumulative differences
            double[] cumulativeDifferences = new double[timestamps.length];
            double runningTotal = 0.0;

            for (int i = 0; i < timestamps.length; i++) {
                // If either value is missing or invalid, result is NaN (but don't update running total)
                if (!validPoints[i] || Double.isNaN(alignedRef[i])) {
                    cumulativeDifferences[i] = Double.NaN;
                } else {
                    runningTotal += values[i] - alignedRef[i];
                    cumulativeDifferences[i] = runningTotal;
                }
            }

            result.addSeries(seriesKey, new TimeSeriesData(timestamps, cumulativeDifferences));
        }

        return result;
    }

    /**
     * Transforms each series to exceedance curve (sorted values with Cunnane plotting positions).
     *
     * Exceedance probability is calculated using the Cunnane formula with alpha=0.4:
     *   p = (rank - 0.4) / (n + 0.2) * 100%
     *
     * Values are sorted from largest to smallest. Invalid (missing) points are dropped
     * entirely — they carry no information on this plot type, and keeping them would
     * push the plot's X-bounds past 100%.
     * Plotting positions are stored as "fake timestamps" (percentile * 1,000,000) for rendering.
     * Each series is independent.
     */
    private static DataSet transformExceedance(DataSet input, List<SeriesRef> selectedSeriesKeys) {
        DataSet result = new DataSet();

        for (SeriesRef seriesKey : selectedSeriesKeys) {
            TimeSeriesData series = input.getSeries(seriesKey);
            if (series == null) {
                continue;
            }

            double[] values = series.getValues();
            boolean[] validPoints = series.getValidPoints();

            // Gather the valid values (invalid points are dropped from the output)
            int validCount = 0;
            for (boolean valid : validPoints) {
                if (valid) validCount++;
            }

            double[] sortedValues = new double[validCount];
            int k = 0;
            for (int i = 0; i < values.length; i++) {
                if (validPoints[i]) {
                    sortedValues[k++] = values[i];
                }
            }

            // Primitive ascending sort, then read out descending (largest first)
            Arrays.sort(sortedValues);

            double[] exceedanceValues = new double[validCount];
            long[] percentileTimestamps = new long[validCount];

            for (int rank = 1; rank <= validCount; rank++) {
                // Cunnane formula: p = (rank - 0.4) / (n + 0.2)
                double percentile = ((rank - 0.4) / (validCount + 0.2)) * 100.0;

                // Store as fake timestamp
                percentileTimestamps[rank - 1] = (long) (percentile * PERCENTILE_SCALE);
                exceedanceValues[rank - 1] = sortedValues[validCount - rank];
            }

            result.addSeries(seriesKey, new TimeSeriesData(percentileTimestamps, exceedanceValues));
        }

        return result;
    }

    /**
     * Transforms each series to residual mass: cumulative deviation from mean over time.
     * Equivalent to cumsum(value - mean) for valid points. NaN values produce NaN output
     * without affecting the running total.
     */
    private static DataSet transformResidualMass(DataSet input, List<SeriesRef> selectedSeriesKeys) {
        DataSet result = new DataSet();

        for (SeriesRef seriesKey : selectedSeriesKeys) {
            TimeSeriesData series = input.getSeries(seriesKey);
            if (series == null) {
                continue;
            }

            double[] values = series.getValues();
            boolean[] validPoints = series.getValidPoints();
            long[] timestamps = series.getTimestamps();

            // Calculate mean of valid points
            double sum = 0.0;
            int validCount = 0;
            for (int i = 0; i < values.length; i++) {
                if (validPoints[i]) {
                    sum += values[i];
                    validCount++;
                }
            }

            if (validCount == 0) {
                continue;
            }

            double mean = sum / validCount;

            // Calculate cumulative deviation from mean
            double[] residualMass = new double[values.length];
            double runningTotal = 0.0;

            for (int i = 0; i < values.length; i++) {
                if (validPoints[i]) {
                    runningTotal += values[i] - mean;
                    residualMass[i] = runningTotal;
                } else {
                    residualMass[i] = Double.NaN;
                }
            }

            result.addSeries(seriesKey, new TimeSeriesData(timestamps, residualMass));
        }

        return result;
    }

    /**
     * Transforms to double mass curve: cumulative reference values on X-axis,
     * cumulative series values on Y-axis.
     *
     * For each series, only timestamps where both the reference and the series have valid
     * data are included. Cumulative sums are computed over these common valid points only.
     * Reference cumulative values are encoded as fake timestamps (value * NUMERIC_SCALE).
     *
     * The reference series itself is included as a 1:1 line (cumRef vs cumRef).
     */
    private static DataSet transformDoubleMass(DataSet input, List<SeriesRef> selectedSeriesKeys) {
        if (selectedSeriesKeys.isEmpty()) {
            return new DataSet();
        }

        DataSet result = new DataSet();

        // Get reference series (first selected series)
        SeriesRef referenceKey = selectedSeriesKeys.get(0);
        TimeSeriesData referenceSeries = input.getSeries(referenceKey);
        if (referenceSeries == null) {
            logger.warn("Reference series not found: {}", referenceKey);
            return new DataSet();
        }

        // Process each series
        for (SeriesRef seriesKey : selectedSeriesKeys) {
            TimeSeriesData series = input.getSeries(seriesKey);
            if (series == null) {
                continue;
            }

            long[] timestamps = series.getTimestamps();
            double[] values = series.getValues();
            boolean[] validPoints = series.getValidPoints();

            // Reference values aligned onto this series' timestamps (NaN where absent/invalid)
            double[] alignedRef = alignReference(referenceSeries, timestamps);

            // Build cumulative sums over the points where both series are valid,
            // writing into full-size buffers and trimming once at the end.
            double[] cumulativeY = new double[timestamps.length];
            long[] fakeTimestamps = new long[timestamps.length];
            double cumRef = 0.0;
            double cumSeries = 0.0;
            int n = 0;

            for (int i = 0; i < timestamps.length; i++) {
                if (!validPoints[i] || Double.isNaN(alignedRef[i])) {
                    continue;
                }
                cumRef += alignedRef[i];
                cumSeries += values[i];

                fakeTimestamps[n] = (long) (cumRef * NUMERIC_SCALE);
                cumulativeY[n] = cumSeries;
                n++;
            }

            if (n == 0) {
                continue;
            }

            // Create new series with fake timestamps encoding cumulative reference on X
            TimeSeriesData doubleMassSeries = new TimeSeriesData(
                n == timestamps.length ? fakeTimestamps : Arrays.copyOf(fakeTimestamps, n),
                n == timestamps.length ? cumulativeY : Arrays.copyOf(cumulativeY, n)
            );

            result.addSeries(seriesKey, doubleMassSeries);
        }

        return result;
    }

    /**
     * Aligns the reference series onto the given (sorted, ascending) timestamp grid using a
     * linear two-pointer merge — the same house pattern as
     * {@link com.kalix.ide.flowviz.stats.TimeSeriesMasker}. Entry {@code i} holds the
     * reference value at {@code timestamps[i]}, or {@link Double#NaN} when the reference has
     * no point (or no valid point) at that timestamp. Duplicate reference timestamps resolve
     * to the last occurrence, matching the previous hash-map behaviour.
     */
    private static double[] alignReference(TimeSeriesData reference, long[] timestamps) {
        long[] refTimestamps = reference.getTimestamps();
        double[] refValues = reference.getValues();
        boolean[] refValidPoints = reference.getValidPoints();
        int m = refTimestamps.length;

        double[] aligned = new double[timestamps.length];
        int j = 0;
        for (int i = 0; i < timestamps.length; i++) {
            long t = timestamps[i];
            while (j < m && refTimestamps[j] < t) {
                j++;
            }
            if (j < m && refTimestamps[j] == t) {
                // Duplicate reference timestamps: last one wins
                int last = j;
                while (last + 1 < m && refTimestamps[last + 1] == t) {
                    last++;
                }
                aligned[i] = refValidPoints[last] ? refValues[last] : Double.NaN;
            } else {
                aligned[i] = Double.NaN;
            }
        }
        return aligned;
    }
}
