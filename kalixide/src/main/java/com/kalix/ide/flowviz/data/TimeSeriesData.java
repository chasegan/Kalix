package com.kalix.ide.flowviz.data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

public class TimeSeriesData {
    private final long[] timestamps;      // Unix timestamps in milliseconds for fast math
    private final double[] values;        // Raw values (NaN for missing data)
    private final boolean[] validPoints;  // Quick missing data lookup
    private final int pointCount;
    
    // Cached statistics
    private Double minValue;
    private Double maxValue;
    private Integer validPointCount;
    
    // Cadence model. The nominal grid interval (the cadence the series is *defined* on) is kept
    // separate from whether the backing array is actually a gap-free grid (contiguity). They
    // diverge for a regular series with missing values dropped: it still has a cadence, but its
    // array is no longer contiguous.
    //   - nominalIntervalMillis: the regular grid step; 0 if the series is genuinely irregular
    //     (ad-hoc timestamps). Retained even when the array has gaps — this is what a gap-aware
    //     renderer uses to decide whether a time jump represents missing data.
    //   - contiguous: true only when every interval equals the nominal step (a full grid, no
    //     gaps). This alone gates the O(1) index fast path in getIndexRange().
    private final long nominalIntervalMillis;
    private final boolean contiguous;
    private final long firstTimestamp;
    
    /**
     * Constructs a time series from {@link LocalDateTime}s. Series identity is supplied
     * externally via {@link SeriesRef} when the data is added to a {@link DataSet} — the
     * data itself carries no name.
     */
    public TimeSeriesData(LocalDateTime[] dateTimes, double[] values) {
        this(toEpochMillis(dateTimes), values);
    }

    /**
     * Constructs a time series directly from millisecond Unix timestamps. Avoids the
     * {@link LocalDateTime} round-trip — intended for hot paths that already hold
     * primitive timestamps (e.g. masking, aggregation). The arrays are defensively copied;
     * the copy is sorted by timestamp if not already ascending.
     */
    public TimeSeriesData(long[] timestamps, double[] values) {
        if (timestamps.length != values.length) {
            throw new IllegalArgumentException("Timestamp and value arrays must have same length");
        }

        this.pointCount = timestamps.length;
        this.timestamps = timestamps.clone();
        this.values = values.clone();
        this.validPoints = new boolean[pointCount];

        for (int i = 0; i < pointCount; i++) {
            this.validPoints[i] = !Double.isNaN(values[i]) && Double.isFinite(values[i]);
        }

        // Sort by timestamp if needed
        sortIfNeeded();

        // Detect cadence (nominal grid interval) and contiguity (gap-free grid)
        CadenceInfo cadence = detectCadence();
        this.nominalIntervalMillis = cadence.nominalIntervalMillis;
        this.contiguous = cadence.contiguous;
        this.firstTimestamp = timestamps.length > 0 ? this.timestamps[0] : 0;

        // Pre-compute statistics
        computeStatistics();
    }

    private static long[] toEpochMillis(LocalDateTime[] dateTimes) {
        long[] millis = new long[dateTimes.length];
        for (int i = 0; i < dateTimes.length; i++) {
            millis[i] = dateTimes[i].toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        return millis;
    }
    
    private void sortIfNeeded() {
        boolean needsSort = false;
        for (int i = 1; i < pointCount; i++) {
            if (timestamps[i] < timestamps[i-1]) {
                needsSort = true;
                break;
            }
        }
        
        if (needsSort) {
            // Create indices array for sorting
            Integer[] indices = new Integer[pointCount];
            for (int i = 0; i < pointCount; i++) {
                indices[i] = i;
            }
            
            // Sort indices by timestamp
            Arrays.sort(indices, (a, b) -> Long.compare(timestamps[a], timestamps[b]));
            
            // Reorder all arrays
            long[] newTimestamps = new long[pointCount];
            double[] newValues = new double[pointCount];
            boolean[] newValidPoints = new boolean[pointCount];
            
            for (int i = 0; i < pointCount; i++) {
                int originalIndex = indices[i];
                newTimestamps[i] = timestamps[originalIndex];
                newValues[i] = values[originalIndex];
                newValidPoints[i] = validPoints[originalIndex];
            }
            
            System.arraycopy(newTimestamps, 0, timestamps, 0, pointCount);
            System.arraycopy(newValues, 0, values, 0, pointCount);
            System.arraycopy(newValidPoints, 0, validPoints, 0, pointCount);
        }
    }
    
    /** Result of cadence detection: the nominal grid step and whether the array is gap-free. */
    private static final class CadenceInfo {
        final long nominalIntervalMillis;  // 0 if genuinely irregular
        final boolean contiguous;          // true iff every interval == nominal (full grid)

        CadenceInfo(long nominalIntervalMillis, boolean contiguous) {
            this.nominalIntervalMillis = nominalIntervalMillis;
            this.contiguous = contiguous;
        }
    }

    // Intervals are accepted as "equal" / "integer multiples" within this relative tolerance,
    // absorbing minor timestamp jitter in imported data. Model output is exact, so this only
    // matters at the margins.
    private static final double INTERVAL_TOLERANCE = 0.01;

    // A non-contiguous series is treated as a regular grid with gaps only if the base interval
    // accounts for at least this fraction of all intervals. This is the discriminator against
    // genuinely ad-hoc data that merely happens to share a common divisor: in real gappy regular
    // data the base spacing dominates, whereas ad-hoc spacings rarely repeat.
    private static final double BASE_DOMINANCE_FRACTION = 0.5;

    /**
     * Classifies the series' cadence, deriving two distinct properties (see field docs): the
     * <b>nominal interval</b> (the grid step the series is defined on, retained even when values
     * are missing) and <b>contiguity</b> (whether the backing array is a gap-free grid, which
     * alone governs the O(1) index fast path).
     *
     * <ul>
     *   <li>All intervals equal → contiguous regular grid (nominal = that interval).</li>
     *   <li>Every interval a near-integer multiple of a dominant base interval → regular grid
     *       with gaps (nominal = base, not contiguous). This is a regular series with
     *       dropped/missing points.</li>
     *   <li>Otherwise → genuinely irregular/ad-hoc (nominal = 0, not contiguous). Calendar
     *       aggregations (monthly/annual) fall here by design — their spacing varies, so they
     *       should not be treated as a fixed cadence.</li>
     * </ul>
     */
    private CadenceInfo detectCadence() {
        if (pointCount < 3) {
            return new CadenceInfo(0, false);
        }

        long firstInterval = timestamps[1] - timestamps[0];
        if (firstInterval <= 0) {
            return new CadenceInfo(0, false);  // non-ascending / duplicate timestamps: no clean grid
        }

        // Contiguity: every interval equal within tolerance. This is the precondition for
        // getIndexRange()'s arithmetic fast path — index = (t - first) / interval only holds when
        // the array is a full grid with no gaps. We must scan all intervals, not a leading
        // sample: a series regular for its first N points but with a later gap would otherwise be
        // misclassified, and the fast path would drift by the number of dropped points, silently
        // skipping the leading visible points after the gap. The extra O(n) scan is negligible
        // beside the array clones and stats pass already done in the constructor.
        double tolerance = firstInterval * INTERVAL_TOLERANCE;
        boolean allEqual = true;
        for (int i = 2; i < pointCount; i++) {
            if (Math.abs((timestamps[i] - timestamps[i - 1]) - firstInterval) > tolerance) {
                allEqual = false;
                break;
            }
        }
        if (allEqual) {
            return new CadenceInfo(firstInterval, true);
        }

        // Not contiguous: is this a regular grid with gaps, or genuinely irregular?
        return detectGappyCadence();
    }

    /**
     * Determines whether a non-contiguous series is nonetheless a regular grid with gaps: every
     * interval must be a near-integer multiple (≥1×) of a dominant base interval. Returns the base
     * as the nominal interval if so, else {@code 0} (irregular). Always non-contiguous — the
     * caller has already ruled contiguity out.
     */
    private CadenceInfo detectGappyCadence() {
        int m = pointCount - 1;

        long[] diffs = new long[m];
        for (int i = 0; i < m; i++) {
            long d = timestamps[i + 1] - timestamps[i];
            if (d <= 0) {
                return new CadenceInfo(0, false);
            }
            diffs[i] = d;
        }

        // Base candidate = the most common interval (mode). In a gappy regular series the 1× grid
        // spacing recurs most; ad-hoc spacings rarely repeat. Found via a sorted copy so the scan
        // stays primitive (no boxing) even for large series; ties resolve to the smallest value.
        long[] sorted = diffs.clone();
        Arrays.sort(sorted);
        long base = sorted[0];
        int bestRun = 1, run = 1;
        for (int i = 1; i < m; i++) {
            run = (sorted[i] == sorted[i - 1]) ? run + 1 : 1;
            if (run > bestRun) {
                bestRun = run;
                base = sorted[i];
            }
        }
        if (base <= 0) {
            return new CadenceInfo(0, false);
        }

        // Every interval must be a near-integer multiple (≥1×) of the base, and the base itself
        // must dominate — otherwise this is ad-hoc data that merely shares a divisor.
        double tol = base * INTERVAL_TOLERANCE;
        int baseCount = 0;
        for (long d : diffs) {
            long k = Math.round((double) d / base);
            if (k < 1 || Math.abs(d - k * base) > tol) {
                return new CadenceInfo(0, false);
            }
            if (k == 1) {
                baseCount++;
            }
        }
        if (baseCount < m * BASE_DOMINANCE_FRACTION) {
            return new CadenceInfo(0, false);
        }
        return new CadenceInfo(base, false);
    }
    
    private void computeStatistics() {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int validCount = 0;

        for (int i = 0; i < pointCount; i++) {
            if (validPoints[i]) {
                double value = values[i];
                min = Math.min(min, value);
                max = Math.max(max, value);
                validCount++;
            }
        }

        this.validPointCount = validCount;
        if (validCount > 0) {
            this.minValue = min;
            this.maxValue = max;
        } else {
            this.minValue = null;
            this.maxValue = null;
        }
    }
    
    // Optimized range queries using regular intervals
    public IndexRange getIndexRange(long startTimeMs, long endTimeMs) {
        if (pointCount == 0) {
            return new IndexRange(0, 0);
        }
        
        int startIndex, endIndex;
        
        if (contiguous) {
            // Fast calculation: on a gap-free grid the array index is exactly (t - first) / step
            startIndex = (int) Math.max(0, (startTimeMs - firstTimestamp) / nominalIntervalMillis);
            endIndex = (int) Math.min(pointCount, (endTimeMs - firstTimestamp) / nominalIntervalMillis + 1);
            
            // Clamp to actual bounds
            startIndex = Math.max(0, Math.min(startIndex, pointCount - 1));
            endIndex = Math.max(startIndex, Math.min(endIndex, pointCount));
        } else {
            // Binary search for irregular intervals
            startIndex = binarySearchTimestamp(startTimeMs, true);
            endIndex = binarySearchTimestamp(endTimeMs, false);
        }
        
        return new IndexRange(startIndex, endIndex);
    }
    
    private int binarySearchTimestamp(long targetTime, boolean findFirst) {
        int left = 0;
        int right = pointCount - 1;
        int result = findFirst ? pointCount : 0;
        
        while (left <= right) {
            int mid = left + (right - left) / 2;
            long midTime = timestamps[mid];
            
            if (midTime == targetTime) {
                result = mid;
                if (findFirst) {
                    right = mid - 1; // Continue searching left for first occurrence
                } else {
                    left = mid + 1;  // Continue searching right for last occurrence
                }
            } else if (midTime < targetTime) {
                if (!findFirst) result = mid + 1;
                left = mid + 1;
            } else {
                if (findFirst) result = mid;
                right = mid - 1;
            }
        }
        
        return Math.max(0, Math.min(result, pointCount));
    }

    // Getters
    public int getPointCount() { return pointCount; }
    public long[] getTimestamps() { return timestamps; }
    public double[] getValues() { return values; }
    public boolean[] getValidPoints() { return validPoints; }
    
    public long getFirstTimestamp() { return firstTimestamp; }
    public long getLastTimestamp() { return pointCount > 0 ? timestamps[pointCount - 1] : 0; }
    
    /** The regular grid step the series is defined on, in ms; {@code 0} if genuinely irregular.
     *  Retained even when the array has gaps — a gap-aware renderer uses it to decide whether a
     *  time jump represents missing data. */
    public long getNominalIntervalMillis() { return nominalIntervalMillis; }

    /** Whether the backing array is a gap-free regular grid. This alone gates the O(1) index
     *  fast path; a regular series with dropped points has a nominal interval but is not
     *  contiguous. */
    public boolean isContiguous() { return contiguous; }

    /** Legacy alias for {@link #isContiguous()}: historically the only "regular" notion this
     *  class exposed was a gap-free grid. Retained for existing callers. */
    public boolean hasRegularInterval() { return contiguous; }

    /** Legacy alias for {@link #getNominalIntervalMillis()}: returns the grid step when
     *  contiguous and {@code 0} otherwise, matching the historical contract where callers guard
     *  with {@link #hasRegularInterval()}. Retained for existing callers. */
    public long getIntervalMillis() { return contiguous ? nominalIntervalMillis : 0; }
    
    // Statistics getters
    public Double getMinValue() { return minValue; }
    public Double getMaxValue() { return maxValue; }
    public Integer getValidPointCount() { return validPointCount; }
    public int getMissingPointCount() { return pointCount - (validPointCount != null ? validPointCount : 0); }
    
    public static class IndexRange {
        public final int startIndex;
        public final int endIndex;
        
        public IndexRange(int startIndex, int endIndex) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }
        
        public int size() {
            return Math.max(0, endIndex - startIndex);
        }
        
        public boolean isEmpty() {
            return endIndex <= startIndex;
        }
    }
}