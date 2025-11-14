package com.kalix.ide.flowviz.stats;

import com.kalix.ide.flowviz.data.TimeSeriesData;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Computes the SDEB (Sorted Distributional Error with Bias) objective function.
 * SDEB combines temporal error, distributional error, and bias penalty to evaluate
 * goodness of fit between simulated and observed flows.
 *
 * Formula: SDEB = (0.1 × SD + 0.9 × SE) × B
 * Where:
 *   SD = Temporal error (sum of squared differences in time-series order)
 *   SE = Distributional error (sum of squared differences after sorting)
 *   B = Bias penalty (1 + |sum(observed) - sum(simulated)| / sum(observed))
 *
 * Lower values indicate better fit (this is a minimization objective).
 */
public class SdebStatistic implements Statistic {

    @Override
    public String getName() {
        return "SDEB";
    }

    @Override
    public boolean isBivariate() {
        return true;
    }

    @Override
    public String calculate(TimeSeriesData series, TimeSeriesData reference) {
        if (series == null || reference == null) {
            return "N/A";
        }

        if (series.getPointCount() == 0 || reference.getPointCount() == 0) {
            return "N/A";
        }

        try {
            // Extract matched data from the two time series
            MatchedData matched = extractMatchedData(reference, series);

            if (matched.count == 0) {
                return "N/A";
            }

            // Calculate SDEB using the matched data
            double sdeb = calculateSdeb(matched.observed, matched.simulated);

            return String.format("%.3f", sdeb);

        } catch (IllegalArgumentException e) {
            // Handle edge cases like sum(observed) = 0
            return "N/A";
        }
    }

    @Override
    public String getTooltip() {
        return "SDEB objective: (0.1×SD + 0.9×SE) × B (lower is better)";
    }

    /**
     * Container for matched time series data.
     */
    private static class MatchedData {
        final double[] observed;
        final double[] simulated;
        final int count;

        MatchedData(double[] observed, double[] simulated, int count) {
            this.observed = observed;
            this.simulated = simulated;
            this.count = count;
        }
    }

    /**
     * Extracts data at matching timestamps from reference (observed) and series (simulated).
     */
    private MatchedData extractMatchedData(TimeSeriesData reference, TimeSeriesData series) {
        // Build map of reference timestamps to values
        Map<Long, Double> referenceMap = new HashMap<>();
        long[] refTimestamps = reference.getTimestamps();
        double[] refValues = reference.getValues();
        boolean[] refValid = reference.getValidPoints();

        for (int i = 0; i < reference.getPointCount(); i++) {
            if (refValid[i]) {
                referenceMap.put(refTimestamps[i], refValues[i]);
            }
        }

        // Count matching timestamps
        long[] seriesTimestamps = series.getTimestamps();
        double[] seriesValues = series.getValues();
        boolean[] seriesValid = series.getValidPoints();

        int matchCount = 0;
        for (int i = 0; i < series.getPointCount(); i++) {
            if (seriesValid[i] && referenceMap.containsKey(seriesTimestamps[i])) {
                matchCount++;
            }
        }

        // Extract matched values
        double[] observed = new double[matchCount];
        double[] simulated = new double[matchCount];
        int idx = 0;

        for (int i = 0; i < series.getPointCount(); i++) {
            if (seriesValid[i]) {
                long timestamp = seriesTimestamps[i];
                Double refValue = referenceMap.get(timestamp);
                if (refValue != null) {
                    observed[idx] = refValue;
                    simulated[idx] = seriesValues[i];
                    idx++;
                }
            }
        }

        return new MatchedData(observed, simulated, matchCount);
    }

    /**
     * Calculates SDEB objective function given matched observed and simulated data.
     *
     * @param observed Array of observed (reference) values
     * @param simulated Array of simulated (series) values
     * @return SDEB value (lower is better)
     * @throws IllegalArgumentException if sum(observed) = 0
     */
    private double calculateSdeb(double[] observed, double[] simulated) {
        // Step 1: Create mask and extract valid data
        boolean[] mask = new boolean[observed.length];
        int validCount = 0;
        for (int i = 0; i < observed.length; i++) {
            mask[i] = Double.isFinite(observed[i]) && Double.isFinite(simulated[i]);
            if (mask[i]) validCount++;
        }

        if (validCount == 0) {
            throw new IllegalArgumentException("No valid data points");
        }

        // Extract masked values
        double[] QO = new double[validCount];
        double[] QM = new double[validCount];
        int idx = 0;
        for (int i = 0; i < observed.length; i++) {
            if (mask[i]) {
                QO[idx] = observed[i];
                QM[idx] = simulated[i];
                idx++;
            }
        }

        // Step 2: Create sorted versions
        double[] RO = QO.clone();
        double[] RM = QM.clone();
        Arrays.sort(RO);
        Arrays.sort(RM);

        // Step 3: Square root transform
        double[] sqrtQO = new double[QO.length];
        double[] sqrtQM = new double[QM.length];
        double[] sqrtRO = new double[RO.length];
        double[] sqrtRM = new double[RM.length];

        for (int i = 0; i < QO.length; i++) {
            sqrtQO[i] = Math.sqrt(QO[i]);
            sqrtQM[i] = Math.sqrt(QM[i]);
            sqrtRO[i] = Math.sqrt(RO[i]);
            sqrtRM[i] = Math.sqrt(RM[i]);
        }

        // Step 4: Calculate SD (temporal error)
        double SD = 0.0;
        for (int i = 0; i < sqrtQO.length; i++) {
            double diff = sqrtQO[i] - sqrtQM[i];
            SD += diff * diff;
        }

        // Step 5: Calculate SE (distributional error)
        double SE = 0.0;
        for (int i = 0; i < sqrtRO.length; i++) {
            double diff = sqrtRO[i] - sqrtRM[i];
            SE += diff * diff;
        }

        // Step 6: Calculate B (bias penalty)
        double sumObserved = 0.0;
        double sumSimulated = 0.0;
        for (int i = 0; i < QO.length; i++) {
            sumObserved += QO[i];
            sumSimulated += QM[i];
        }

        if (sumObserved == 0.0) {
            throw new IllegalArgumentException("Sum of observed flows is zero, cannot calculate SDEB");
        }

        double B = 1.0 + Math.abs(sumObserved - sumSimulated) / sumObserved;

        // Step 7: Calculate final SDEB
        double SDEB = (0.1 * SD + 0.9 * SE) * B;

        return SDEB;
    }
}
