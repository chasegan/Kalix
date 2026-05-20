package com.kalix.ide.flowviz.stats;

import java.util.Arrays;

/**
 * A series of values prepared for statistic computation.
 *
 * <p>Holds the values a statistic should run over, and lazily derives the quantities
 * statistics commonly share — valid count, sum, mean, min, max, and a sorted copy. A
 * recompute builds a sample <em>once</em> per series and hands the same instance to
 * every {@link Statistic}, so shared work (notably the reference series' sorted
 * distribution, reused by every bivariate comparison) is computed at most once.</p>
 *
 * <p>For a masked sample ({@link MaskMode#ALL}/{@link MaskMode#EACH}) the values are
 * finite by construction — the mask only admits valid points. The unmasked
 * {@link MaskMode#NONE} sample may contain {@code NaN}/infinite entries; every accessor
 * here ignores non-finite values.</p>
 *
 * <p>Two samples passed to a bivariate statistic are index-aligned whenever the caller
 * built them by applying the <em>same</em> mask to both series — which is exactly what
 * {@code StatsTableModel} does. Bivariate statistics rely on that.</p>
 *
 * <p>Not thread-safe: a sample is built and consumed on the EDT within a single
 * recompute. The lazy fields use plain (unsynchronised) caching.</p>
 */
public final class StatSample {

    private final double[] values;

    private boolean statsReady;
    private int validCount;
    private double sum;
    private double min;
    private double max;

    private double[] sortedValid;

    /**
     * Wraps {@code values} without copying — the array is only ever read, so the caller
     * may pass a series' backing array directly. Callers must not mutate it afterwards.
     */
    public StatSample(double[] values) {
        this.values = values;
    }

    /**
     * The sample's values, in their original order, including any non-finite entries.
     * Bivariate statistics use this and rely on two samples being index-aligned (see
     * the class docs).
     */
    public double[] values() {
        return values;
    }

    /** Total number of values, non-finite entries included — the {@code n} that Count reports. */
    public int rawCount() {
        return values.length;
    }

    /** Number of finite values. */
    public int validCount() {
        ensureStats();
        return validCount;
    }

    /** Sum of the finite values ({@code 0.0} when there are none). */
    public double sum() {
        ensureStats();
        return sum;
    }

    /** Mean of the finite values, or {@code NaN} when there are none. */
    public double mean() {
        ensureStats();
        return validCount > 0 ? sum / validCount : Double.NaN;
    }

    /** Smallest finite value, or {@code NaN} when there are none. */
    public double min() {
        ensureStats();
        return validCount > 0 ? min : Double.NaN;
    }

    /** Largest finite value, or {@code NaN} when there are none. */
    public double max() {
        ensureStats();
        return validCount > 0 ? max : Double.NaN;
    }

    /**
     * The finite values, sorted ascending. Computed once on first call and cached — so a
     * shared reference sample sorts its distribution a single time no matter how many
     * bivariate comparisons consume it.
     */
    public double[] sortedValidValues() {
        if (sortedValid == null) {
            ensureStats();
            double[] sorted = new double[validCount];
            int k = 0;
            for (double v : values) {
                if (Double.isFinite(v)) {
                    sorted[k++] = v;
                }
            }
            Arrays.sort(sorted);
            sortedValid = sorted;
        }
        return sortedValid;
    }

    private void ensureStats() {
        if (statsReady) {
            return;
        }
        int vc = 0;
        double s = 0.0;
        double mn = Double.POSITIVE_INFINITY;
        double mx = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            if (Double.isFinite(v)) {
                vc++;
                s += v;
                if (v < mn) mn = v;
                if (v > mx) mx = v;
            }
        }
        this.validCount = vc;
        this.sum = s;
        this.min = mn;
        this.max = mx;
        this.statsReady = true;
    }
}
