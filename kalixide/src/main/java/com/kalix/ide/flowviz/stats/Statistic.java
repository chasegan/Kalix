package com.kalix.ide.flowviz.stats;

/**
 * Interface for a statistic that can be computed on time series data.
 * Statistics can be univariate (computed on a single series) or bivariate (comparing to a reference).
 *
 * <p>Statistics operate on a {@link StatSample} — a prepared, masked set of values with
 * shared derived quantities — rather than on raw {@code TimeSeriesData}. Masking and the
 * sharing of derived work are the caller's responsibility; an implementation is a pure
 * function of its sample(s).</p>
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
     * Calculates the statistic value for the given sample.
     *
     * @param series The sample to compute the statistic on (already masked if applicable)
     * @param reference The reference sample for bivariate statistics — index-aligned with
     *                  {@code series} (both masked with the same mask); {@code null} for
     *                  univariate statistics or when no reference is available
     * @return The computed statistic as a formatted string, or "N/A" if not applicable/computable
     */
    String calculate(StatSample series, StatSample reference);

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
