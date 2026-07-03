package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.data.TimeSeriesData;

/**
 * Precomputed block min/max acceleration structure over one immutable series.
 *
 * <p>LOD rendering needs the min and max of the valid values in an index range, once
 * per pixel column per frame. Scanning the points directly makes a pan/zoom frame cost
 * O(visible points); with this structure a range query touches at most
 * {@code 2*BLOCK + range/BLOCK} array slots, so a frame costs roughly O(plot width)
 * regardless of how many million points are on screen.</p>
 *
 * <p>Build cost is one O(n) pass and ~1/32nd of the series' memory; instances are
 * cached per {@link TimeSeriesData} (which is immutable, so a pyramid can never go
 * stale - see {@link LODManager}).</p>
 */
final class MinMaxPyramid {

    /** Points per block. 64 balances query cost (2*64 edge reads) against memory (n/64). */
    private static final int BLOCK = 64;

    private final double[] values;
    private final boolean[] valid;
    /** Per-block min/max over valid points; +Inf/-Inf when the block has none. */
    private final double[] blockMin;
    private final double[] blockMax;

    MinMaxPyramid(TimeSeriesData series) {
        this.values = series.getValues();
        this.valid = series.getValidPoints();

        int blockCount = (values.length + BLOCK - 1) / BLOCK;
        this.blockMin = new double[blockCount];
        this.blockMax = new double[blockCount];

        for (int b = 0; b < blockCount; b++) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            int end = Math.min(values.length, (b + 1) * BLOCK);
            for (int i = b * BLOCK; i < end; i++) {
                if (!valid[i]) continue;
                double v = values[i];
                if (v < min) min = v;
                if (v > max) max = v;
            }
            blockMin[b] = min;
            blockMax[b] = max;
        }
    }

    /**
     * Folds the min/max of the valid values in {@code [from, to)} into
     * {@code acc[0]} (min) and {@code acc[1]} (max). Leaves {@code acc} untouched
     * when the range holds no valid values, so callers can seed with +Inf/-Inf and
     * test afterwards.
     */
    void accumulate(int from, int to, double[] acc) {
        from = Math.max(0, from);
        to = Math.min(values.length, to);
        if (from >= to) {
            return;
        }

        int firstFullBlock = (from + BLOCK - 1) / BLOCK;
        int lastFullBlockEnd = (to / BLOCK);

        if (firstFullBlock >= lastFullBlockEnd) {
            // Range lies within one or two blocks - scan points directly.
            accumulatePoints(from, to, acc);
            return;
        }

        // Partial head, whole blocks, partial tail.
        accumulatePoints(from, firstFullBlock * BLOCK, acc);
        for (int b = firstFullBlock; b < lastFullBlockEnd; b++) {
            if (blockMin[b] < acc[0]) acc[0] = blockMin[b];
            if (blockMax[b] > acc[1]) acc[1] = blockMax[b];
        }
        accumulatePoints(lastFullBlockEnd * BLOCK, to, acc);
    }

    private void accumulatePoints(int from, int to, double[] acc) {
        for (int i = from; i < to; i++) {
            if (!valid[i]) continue;
            double v = values[i];
            if (v < acc[0]) acc[0] = v;
            if (v > acc[1]) acc[1] = v;
        }
    }
}
