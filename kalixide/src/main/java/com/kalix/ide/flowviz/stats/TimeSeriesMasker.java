package com.kalix.ide.flowviz.stats;

import com.kalix.ide.flowviz.data.TimeSeriesData;

import java.util.List;

/**
 * Utilities for creating and applying masks to time series data.
 *
 * <p>A {@link Mask} is the sorted set of timestamps at which statistics should be
 * computed — typically the timestamps where every selected series has valid data.
 * Applying a mask to a series filters it to just those timestamps.</p>
 *
 * <p>All operations work in primitive {@code long[]}/{@code double[]} space. Because
 * {@link TimeSeriesData} already stores its timestamps sorted ascending, mask
 * construction and application are linear sorted-array merges — no per-timestamp
 * hashing or boxing. When every series shares an identical regular grid (the common
 * case for model output), construction takes an even cheaper index-aligned fast path.</p>
 */
public class TimeSeriesMasker {

    private TimeSeriesMasker() {
        // Utility class
    }

    /**
     * An immutable, sorted set of "valid" timestamps. Applying it to a series keeps only
     * the points whose timestamp is in the set.
     */
    public static final class Mask {
        /** Sorted ascending, de-duplicated. */
        private final long[] validTimestamps;

        private Mask(long[] validTimestamps) {
            this.validTimestamps = validTimestamps;
        }

        /** Number of timestamps in the mask. */
        public int size() {
            return validTimestamps.length;
        }

        /**
         * Filters {@code series} to the points whose timestamp is in this mask, returning a
         * new series. Both the series timestamps and the mask are sorted, so this is a
         * single linear two-pointer merge. A {@code null} series yields an empty result.
         */
        public TimeSeriesData apply(TimeSeriesData series) {
            if (series == null) {
                return new TimeSeriesData(new long[0], new double[0]);
            }
            long[] ts = series.getTimestamps();
            double[] vals = series.getValues();
            int n = ts.length;
            int m = validTimestamps.length;

            int cap = Math.min(n, m);
            long[] outTs = new long[cap];
            double[] outVals = new double[cap];
            int k = 0;

            int i = 0, j = 0;
            while (i < n && j < m) {
                long a = ts[i];
                long b = validTimestamps[j];
                if (a < b) {
                    i++;
                } else if (a > b) {
                    j++;
                } else {
                    outTs[k] = a;
                    outVals[k] = vals[i];
                    k++;
                    i++;
                    j++;
                }
            }

            if (k == outTs.length) {
                return new TimeSeriesData(outTs, outVals);
            }
            return new TimeSeriesData(java.util.Arrays.copyOf(outTs, k),
                                      java.util.Arrays.copyOf(outVals, k));
        }

        /**
         * Filters {@code series} to the values whose timestamp is in this mask, returning
         * just the masked {@code double[]} — no {@link TimeSeriesData} construction. The
         * result is in mask order, so applying the same mask to two series yields two
         * index-aligned arrays. A {@code null} series yields an empty array.
         *
         * <p>This is the form statistics consume; {@link #apply(TimeSeriesData)} is for
         * callers (e.g. plotting) that need a full series back.</p>
         */
        public double[] applyToValues(TimeSeriesData series) {
            if (series == null) {
                return new double[0];
            }
            long[] ts = series.getTimestamps();
            double[] vals = series.getValues();
            int n = ts.length;
            int m = validTimestamps.length;

            double[] out = new double[Math.min(n, m)];
            int k = 0;

            int i = 0, j = 0;
            while (i < n && j < m) {
                long a = ts[i];
                long b = validTimestamps[j];
                if (a < b) {
                    i++;
                } else if (a > b) {
                    j++;
                } else {
                    out[k++] = vals[i];
                    i++;
                    j++;
                }
            }

            return k == out.length ? out : java.util.Arrays.copyOf(out, k);
        }
    }

    /**
     * Creates a mask of the timestamps at which <em>all</em> the given series have valid
     * data — i.e. the intersection of each series' valid-timestamp set.
     *
     * @param allSeries the series to intersect; null/empty yields an empty mask
     */
    public static Mask createAllMask(List<TimeSeriesData> allSeries) {
        if (allSeries == null || allSeries.isEmpty()) {
            return new Mask(new long[0]);
        }
        if (allSeries.size() == 1) {
            return new Mask(validTimestampsOf(allSeries.get(0)));
        }

        long[] aligned = tryAlignedAllMask(allSeries);
        if (aligned != null) {
            return new Mask(aligned);
        }

        // General case: intersect each series' sorted valid-timestamp set pairwise.
        long[] result = validTimestampsOf(allSeries.get(0));
        for (int s = 1; s < allSeries.size() && result.length > 0; s++) {
            result = intersectSorted(result, validTimestampsOf(allSeries.get(s)));
        }
        return new Mask(result);
    }

    /**
     * Creates a mask of the timestamps at which <em>both</em> the reference and the series
     * have valid data. Equivalent to {@link #createAllMask} over the two series.
     */
    public static Mask createEachMask(TimeSeriesData reference, TimeSeriesData series) {
        if (reference == null || series == null) {
            return new Mask(new long[0]);
        }
        return new Mask(intersectSorted(validTimestampsOf(reference), validTimestampsOf(series)));
    }

    /**
     * Fast path for the common case where every series shares an identical timestamp grid
     * (e.g. model outputs from one simulation): the mask is simply the timestamps at
     * indices where every series' point is valid — a per-index validity AND, no merge.
     *
     * <p>Alignment is verified <em>exactly</em> via {@link java.util.Arrays#equals} on the
     * timestamp arrays — not via the heuristic {@code hasRegularInterval} — so a wrong
     * mask cannot result. Returns {@code null} when the grids differ, signalling the
     * caller to fall back to the general intersection.</p>
     */
    private static long[] tryAlignedAllMask(List<TimeSeriesData> allSeries) {
        TimeSeriesData first = allSeries.get(0);
        int n = first.getPointCount();
        if (n == 0) {
            return null;
        }
        long[] firstTimestamps = first.getTimestamps();

        for (int s = 1; s < allSeries.size(); s++) {
            TimeSeriesData series = allSeries.get(s);
            if (series.getPointCount() != n
                    || !java.util.Arrays.equals(firstTimestamps, series.getTimestamps())) {
                return null;
            }
        }

        // All series share the grid — AND their validity index-by-index.
        boolean[][] valid = new boolean[allSeries.size()][];
        for (int s = 0; s < allSeries.size(); s++) {
            valid[s] = allSeries.get(s).getValidPoints();
        }

        long[] out = new long[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            boolean allValid = true;
            for (int s = 0; s < valid.length; s++) {
                if (!valid[s][i]) {
                    allValid = false;
                    break;
                }
            }
            if (allValid) {
                out[k++] = firstTimestamps[i];
            }
        }
        return k == out.length ? out : java.util.Arrays.copyOf(out, k);
    }

    /**
     * Returns the sorted, de-duplicated timestamps at which {@code series} has valid data.
     * The series' own timestamps are already sorted, so this is a single linear scan.
     */
    private static long[] validTimestampsOf(TimeSeriesData series) {
        long[] ts = series.getTimestamps();
        boolean[] valid = series.getValidPoints();
        int n = ts.length;

        long[] out = new long[n];
        int k = 0;
        long prev = 0;
        for (int i = 0; i < n; i++) {
            if (valid[i]) {
                long t = ts[i];
                // Skip duplicate timestamps (a single valid entry per timestamp is enough).
                if (k == 0 || t != prev) {
                    out[k++] = t;
                    prev = t;
                }
            }
        }
        return k == out.length ? out : java.util.Arrays.copyOf(out, k);
    }

    /**
     * Intersects two sorted, de-duplicated {@code long[]}s — a single linear merge.
     */
    private static long[] intersectSorted(long[] a, long[] b) {
        int cap = Math.min(a.length, b.length);
        long[] out = new long[cap];
        int k = 0;
        int i = 0, j = 0;
        while (i < a.length && j < b.length) {
            long x = a[i];
            long y = b[j];
            if (x < y) {
                i++;
            } else if (x > y) {
                j++;
            } else {
                out[k++] = x;
                i++;
                j++;
            }
        }
        return k == out.length ? out : java.util.Arrays.copyOf(out, k);
    }
}
