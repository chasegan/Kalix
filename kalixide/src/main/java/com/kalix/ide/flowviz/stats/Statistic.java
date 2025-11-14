package com.kalix.ide.flowviz.stats;

import com.kalix.ide.flowviz.data.TimeSeriesData;

/**
 * Interface for a statistic that can be computed on time series data.
 * Statistics can be univariate (computed on a single series) or bivariate (comparing to a reference).
 */
public interface Statistic {

    /**
     * Gets the display name of this statistic for table headers.
     *
     * @return The statistic name (e.g., "Min", "Max", "Bias")
     */
    String getName();

    /**
     * Returns true if this statistic requires a reference series for comparison.
     * Bivariate statistics cannot be computed when mask mode is NONE.
     *
     * @return true for bivariate statistics (bias, RMSE, correlation), false for univariate (min, max, mean)
     */
    boolean isBivariate();

    /**
     * Calculates the statistic value for the given series.
     *
     * @param series The series to compute the statistic on (already masked if applicable)
     * @param reference The reference series for bivariate statistics (already masked if applicable), null for univariate
     * @return The computed statistic as a formatted string, or "N/A" if not applicable/computable
     */
    String calculate(TimeSeriesData series, TimeSeriesData reference);

    /**
     * Gets a tooltip describing this statistic.
     * Can be used in UI to explain what the statistic means.
     *
     * @return A description of the statistic, or null for no tooltip
     */
    default String getTooltip() {
        return null;
    }
}
