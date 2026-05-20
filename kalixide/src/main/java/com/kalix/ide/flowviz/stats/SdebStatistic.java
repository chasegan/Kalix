package com.kalix.ide.flowviz.stats;

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
 *
 * <p>The two samples are index-aligned by construction — {@code StatsTableModel} masks
 * series and reference with the same mask, so {@code series.values()} and
 * {@code reference.values()} line up timestamp-for-timestamp. SDEB therefore needs no
 * timestamp matching of its own; it asserts the alignment via a length check and uses
 * the values, sums, and sorted distributions the samples already provide (the
 * reference's sorted distribution is cached on the shared reference sample, so it is
 * sorted once per recompute regardless of how many series are compared against it).</p>
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
    public String calculate(StatSample series, StatSample reference) {
        if (reference == null) {
            return "N/A";
        }

        double[] observed = reference.values();
        double[] simulated = series.values();

        // Aligned and equal-length by construction (same mask applied to both). Bail
        // defensively rather than produce a wrong number if that contract is violated.
        if (observed.length == 0 || observed.length != simulated.length) {
            return "N/A";
        }

        double sumObserved = reference.sum();
        if (sumObserved == 0.0) {
            return "N/A";  // Bias penalty undefined when observed flows sum to zero.
        }

        double sdeb = calculateSdeb(
            observed, simulated,
            reference.sortedValidValues(), series.sortedValidValues(),
            sumObserved, series.sum());

        return String.format("%.3f", sdeb);
    }

    @Override
    public String getTooltip() {
        return "SDEB objective: (0.1×SD + 0.9×SE) × B (lower is better)";
    }

    /**
     * Calculates the SDEB objective from already-masked, index-aligned data.
     *
     * @param observed        observed (reference) values, in temporal order
     * @param simulated       simulated (series) values, temporally aligned with {@code observed}
     * @param sortedObserved  {@code observed} sorted ascending
     * @param sortedSimulated {@code simulated} sorted ascending
     * @param sumObserved     sum of {@code observed} (must be non-zero)
     * @param sumSimulated    sum of {@code simulated}
     */
    private double calculateSdeb(double[] observed, double[] simulated,
                                 double[] sortedObserved, double[] sortedSimulated,
                                 double sumObserved, double sumSimulated) {
        // SD — temporal error: squared differences of the sqrt-transformed series in
        // time order.
        double sd = 0.0;
        for (int i = 0; i < observed.length; i++) {
            double diff = Math.sqrt(observed[i]) - Math.sqrt(simulated[i]);
            sd += diff * diff;
        }

        // SE — distributional error: same, but over the sorted distributions.
        double se = 0.0;
        for (int i = 0; i < sortedObserved.length; i++) {
            double diff = Math.sqrt(sortedObserved[i]) - Math.sqrt(sortedSimulated[i]);
            se += diff * diff;
        }

        // B — bias penalty.
        double b = 1.0 + Math.abs(sumObserved - sumSimulated) / sumObserved;

        return (0.1 * sd + 0.9 * se) * b;
    }
}
