package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.TimeSeriesData;

import java.time.Instant;
import java.util.Arrays;

/**
 * Point-by-point sum of time series that share identical timestamps. Inputs are added one
 * at a time, so a caller never needs to hold more than the running sum and one input.
 *
 * <p>A point missing in any input is missing in the sum. Inputs whose timestamps differ in
 * any way are rejected rather than aligned.</p>
 */
public final class SeriesSum {

    private long[] timestamps;
    private double[] sums;
    private int count;
    private boolean done;

    /**
     * Adds {@code series} to the sum.
     *
     * @throws IllegalArgumentException if its timestamps differ from the first input's;
     *                                  the message says how, for the caller to prefix
     *                                  with the series name
     * @throws IllegalStateException    if {@link #result()} has already been taken
     */
    public void add(TimeSeriesData series) {
        if (done) {
            throw new IllegalStateException("Sum already taken");
        }
        long[] t = series.getTimestamps();
        double[] v = series.getValues();
        boolean[] valid = series.getValidPoints();
        int n = series.getPointCount();

        if (timestamps == null) {
            timestamps = Arrays.copyOf(t, n);
            sums = new double[n];
        } else {
            checkTimestamps(t, n);
        }

        for (int i = 0; i < n; i++) {
            sums[i] = valid[i] ? sums[i] + v[i] : Double.NaN;
        }
        count++;
    }

    /** Number of series added so far. */
    public int count() {
        return count;
    }

    /**
     * The sum, as a new series. One-shot: the accumulator hands its arrays to the result
     * rather than copying them.
     *
     * @throws IllegalStateException if nothing was added, or the sum was already taken
     */
    public TimeSeriesData result() {
        if (done) {
            throw new IllegalStateException("Sum already taken");
        }
        if (count == 0) {
            throw new IllegalStateException("Nothing to sum");
        }
        done = true;
        TimeSeriesData sum = TimeSeriesData.adopting(timestamps, sums);
        timestamps = null;
        sums = null;
        return sum;
    }

    private void checkTimestamps(long[] t, int n) {
        if (n != timestamps.length) {
            throw new IllegalArgumentException(String.format(
                "has %,d points where the first series has %,d", n, timestamps.length));
        }
        int i = Arrays.mismatch(timestamps, 0, n, t, 0, n);
        if (i >= 0) {
            throw new IllegalArgumentException(String.format(
                "has a point at %s where the first series has %s (point %,d)",
                Instant.ofEpochMilli(t[i]), Instant.ofEpochMilli(timestamps[i]), i + 1));
        }
    }
}
