package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.stats.SeasonalMaskMode;

import java.time.LocalDate;
import java.time.Month;
import java.util.Arrays;
import java.util.Set;

/**
 * Aggregates time series data to coarser temporal resolutions.
 *
 * <p>The pipeline is fully primitive (per manifestos/performance.md): timestamps stay as
 * UTC epoch millis end to end, points are bucketed in a single pass over primitive arrays,
 * and calendar math happens only at bucket <em>boundaries</em> (daily buckets need none at
 * all — a UTC day is just {@code floorDiv(timestampMs, 86_400_000)}).</p>
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>Each output point sits at the period start (midnight UTC / first of month /
 *       first of water year).</li>
 *   <li>The output spans the first through last period containing at least one valid
 *       point; fully-missing interior periods are emitted as {@link Double#NaN} so the
 *       aggregated grid stays complete and a missing period renders as a gap (via the
 *       renderer's NaN break) rather than a straight bridge.</li>
 *   <li>A period aggregates to {@link Double#NaN} when it contains any invalid point, or
 *       when it is incomplete — i.e. it extends beyond the series' temporal bounds. For
 *       daily aggregation the end-side test is cadence-aware: a day is complete only if
 *       the series reaches the day's last expected sample (one nominal interval before
 *       the next midnight), so a final day of hourly data that stops at 03:00 reads as
 *       NaN rather than an under-reported sum.</li>
 * </ul>
 */
public class TimeSeriesAggregator {

    /** Milliseconds per UTC day (timestamps are UTC epoch millis, so days are fixed-width). */
    private static final long DAY_MS = 86_400_000L;

    /**
     * Aggregates time series data according to the specified period and method.
     *
     * @param original Original time series data
     * @param period Aggregation period
     * @param method Aggregation method
     * @return Aggregated time series, or original if period is ORIGINAL
     */
    public static TimeSeriesData aggregate(
        TimeSeriesData original,
        AggregationPeriod period,
        AggregationMethod method,
        SeasonalMaskMode seasonalMaskMode
    ) {
        if (original == null || period == AggregationPeriod.ORIGINAL
                || original.getPointCount() == 0) {
            return original;
        }

        if (period == AggregationPeriod.DAILY) {
            return aggregateBuckets(original, method, new DailyBuckets(original), seasonalMaskMode);
        } else if (period == AggregationPeriod.MONTHLY) {
            return aggregateBuckets(original, method, new MonthlyBuckets(original), seasonalMaskMode);
        } else if (period.isAnnual()) {
            return aggregateBuckets(original, method,
                new AnnualBuckets(original, period.getStartMonth()), seasonalMaskMode);
        }

        return original;
    }

    /**
     * A consecutive run of aggregation periods covering the series' span: bucket {@code 0}
     * contains the first timestamp and bucket {@code count() - 1} the last. Implementations
     * are consulted only at bucket boundaries — the per-point scan is pure comparisons.
     */
    private interface Buckets {
        /** Number of buckets spanning the series. */
        int count();

        /**
         * Epoch-millis start of bucket {@code b}. Must accept {@code b == count()} (the end
         * boundary of the final bucket).
         */
        long startMs(int b);

        /**
         * Epoch-millis of the last sample a <em>complete</em> bucket {@code b} is expected to
         * contain. A bucket whose expected last sample lies beyond the series' final timestamp
         * is incomplete and aggregates to NaN.
         */
        long lastExpectedSampleMs(int b);
    }

    /**
     * Single-pass primitive aggregation engine shared by all periods. Accumulates sum
     * (Kahan-compensated, matching the numerical quality of the previous
     * {@code DoubleStream.sum()} path), count, min and max per bucket, then emits one point
     * per bucket from the first to the last bucket holding valid data.
     */
    private static TimeSeriesData aggregateBuckets(
        TimeSeriesData original,
        AggregationMethod method,
        Buckets buckets,
        SeasonalMaskMode seasonalMaskMode
    ) {
        Set<Month> selectedMonths = seasonalMaskMode instanceof SeasonalMaskMode.Enabled(Set<Month> months)
            ? months : null;
        long[] timestamps = original.getTimestamps();
        double[] values = original.getValues();
        boolean[] validPoints = original.getValidPoints();
        int n = timestamps.length;
        int bucketCount = buckets.count();

        double[] sum = new double[bucketCount];
        double[] compensation = new double[bucketCount];   // Kahan carry for sum
        double[] min = new double[bucketCount];
        double[] max = new double[bucketCount];
        int[] count = new int[bucketCount];
        boolean[] hasMissing = new boolean[bucketCount];
        Arrays.fill(min, Double.POSITIVE_INFINITY);
        Arrays.fill(max, Double.NEGATIVE_INFINITY);

        // Timestamps are sorted ascending, so bucketing is a forward walk: calendar math
        // (inside startMs) runs once per boundary crossed, never per point.
        int b = 0;
        long bucketEndMs = bucketCount > 1 ? buckets.startMs(1) : Long.MAX_VALUE;
        // Month runs are walked the same way as buckets: one calendar computation per
        // month crossed, never per point.
        long monthEndMs = Long.MIN_VALUE;
        boolean monthSelected = true;
        for (int i = 0; i < n; i++) {
            long t = timestamps[i];
            while (t >= bucketEndMs) {
                b++;
                bucketEndMs = b + 1 < bucketCount ? buckets.startMs(b + 1) : Long.MAX_VALUE;
            }

            if (selectedMonths != null && t >= monthEndMs) {
                LocalDate day = LocalDate.ofEpochDay(Math.floorDiv(t, DAY_MS));
                monthEndMs = day.withDayOfMonth(1).plusMonths(1).toEpochDay() * DAY_MS;
                monthSelected = selectedMonths.contains(day.getMonth());
            }
            // A masked-out month is absent on purpose: it is neither counted nor
            // treated as missing data (which would NaN the whole period).
            if (!monthSelected) {
                continue;
            }

            if (!validPoints[i]) {
                hasMissing[b] = true;
                continue;
            }

            double v = values[i];
            double y = v - compensation[b];
            double s = sum[b] + y;
            compensation[b] = (s - sum[b]) - y;
            sum[b] = s;
            if (v < min[b]) min[b] = v;
            if (v > max[b]) max[b] = v;
            count[b]++;
        }

        // Output spans the first through last bucket with at least one valid point.
        int firstOut = 0;
        while (firstOut < bucketCount && count[firstOut] == 0) {
            firstOut++;
        }
        if (firstOut == bucketCount) {
            return new TimeSeriesData(new long[0], new double[0]);  // no valid data at all
        }
        int lastOut = bucketCount - 1;
        while (count[lastOut] == 0) {
            lastOut--;
        }

        long seriesStartMs = timestamps[0];
        long seriesEndMs = timestamps[n - 1];
        int outCount = lastOut - firstOut + 1;
        long[] outTimestamps = new long[outCount];
        double[] outValues = new double[outCount];

        for (int k = 0; k < outCount; k++) {
            int bb = firstOut + k;
            long periodStartMs = buckets.startMs(bb);
            outTimestamps[k] = periodStartMs;

            // NaN when: no valid data (fully-missing interior period), any invalid point,
            // or the period extends beyond the series' temporal bounds (incomplete).
            // Under a seasonal mask only the SELECTED months are expected, so the window
            // narrows to them - a Jan-only selection wants January present, not December.
            // Genuine truncation still NaNs: a series ending mid-January fails the test.
            long expectedFirstMs = periodStartMs;
            long expectedLastMs = buckets.lastExpectedSampleMs(bb);
            if (selectedMonths != null) {
                long periodEndMs = buckets.startMs(bb + 1);
                // Whatever the bucket subtracts for its final sample (a day, or the
                // sample interval for sub-daily data) applies to the narrowed window too.
                long tailGapMs = periodEndMs - expectedLastMs;
                long firstSelectedMs = firstSelectedMonthStartMs(periodStartMs, periodEndMs, selectedMonths);
                if (firstSelectedMs >= 0) {
                    long lastSelectedEndMs = lastSelectedMonthEndMs(periodStartMs, periodEndMs, selectedMonths);
                    // Clamped to the bucket, so daily/monthly periods (never wider than one
                    // month) reduce to exactly the unmasked test.
                    expectedFirstMs = Math.max(firstSelectedMs, periodStartMs);
                    expectedLastMs = Math.min(lastSelectedEndMs, periodEndMs) - tailGapMs;
                }
            }
            boolean incomplete = expectedFirstMs < seriesStartMs
                || expectedLastMs > seriesEndMs;
            if (count[bb] == 0 || hasMissing[bb] || incomplete) {
                outValues[k] = Double.NaN;
            } else {
                outValues[k] = switch (method) {
                    case SUM -> sum[bb];
                    case MEAN -> sum[bb] / count[bb];
                    case MIN -> min[bb];
                    case MAX -> max[bb];
                };
            }
        }

        return new TimeSeriesData(outTimestamps, outValues);
    }

    /**
     * Start of the earliest selected month intersecting {@code [fromMs, toExclusiveMs)},
     * or {@code -1} when the span contains no selected month at all.
     */
    private static long firstSelectedMonthStartMs(long fromMs, long toExclusiveMs, Set<Month> months) {
        LocalDate cursor = LocalDate.ofEpochDay(Math.floorDiv(fromMs, DAY_MS)).withDayOfMonth(1);
        while (cursor.toEpochDay() * DAY_MS < toExclusiveMs) {
            if (months.contains(cursor.getMonth())) {
                return cursor.toEpochDay() * DAY_MS;
            }
            cursor = cursor.plusMonths(1);
        }
        return -1;
    }

    /**
     * Exclusive end of the latest selected month intersecting {@code [fromMs, toExclusiveMs)},
     * or {@code -1} when the span contains no selected month at all.
     */
    private static long lastSelectedMonthEndMs(long fromMs, long toExclusiveMs, Set<Month> months) {
        LocalDate cursor = LocalDate.ofEpochDay(Math.floorDiv(fromMs, DAY_MS)).withDayOfMonth(1);
        long endMs = -1;
        while (cursor.toEpochDay() * DAY_MS < toExclusiveMs) {
            if (months.contains(cursor.getMonth())) {
                endMs = cursor.plusMonths(1).toEpochDay() * DAY_MS;
            }
            cursor = cursor.plusMonths(1);
        }
        return endMs;
    }

    /**
     * Calendar-day buckets. Pure integer arithmetic: a UTC day is
     * {@code floorDiv(timestampMs, DAY_MS)}. Intended for sub-daily series (e.g. hourly);
     * on daily-or-coarser series each bucket holds one value, so the aggregation methods
     * become no-ops.
     */
    private static final class DailyBuckets implements Buckets {
        private final long firstDay;
        private final int count;
        private final long sampleIntervalMs;

        DailyBuckets(TimeSeriesData series) {
            long[] timestamps = series.getTimestamps();
            firstDay = Math.floorDiv(timestamps[0], DAY_MS);
            long lastDay = Math.floorDiv(timestamps[timestamps.length - 1], DAY_MS);
            count = Math.toIntExact(lastDay - firstDay + 1);

            // A complete day's last expected sample sits one sample interval before the next
            // midnight (e.g. 23:00 for hourly data). When the series has no detectable cadence,
            // or is daily-or-coarser, fall back to a whole day — the test then reduces to
            // "the day's own midnight has been reached", the pre-existing behaviour for
            // those inputs.
            long nominal = series.getNominalIntervalMillis();
            sampleIntervalMs = (nominal > 0 && nominal < DAY_MS) ? nominal : DAY_MS;
        }

        @Override public int count() { return count; }
        @Override public long startMs(int b) { return (firstDay + b) * DAY_MS; }
        @Override public long lastExpectedSampleMs(int b) { return startMs(b + 1) - sampleIntervalMs; }
    }

    /** Calendar-month buckets; boundaries via {@link LocalDate}, indexed as year*12+month. */
    private static final class MonthlyBuckets implements Buckets {
        private final int firstMonthIndex;
        private final int count;

        MonthlyBuckets(TimeSeriesData series) {
            long[] timestamps = series.getTimestamps();
            firstMonthIndex = monthIndexOf(timestamps[0]);
            count = monthIndexOf(timestamps[timestamps.length - 1]) - firstMonthIndex + 1;
        }

        private static int monthIndexOf(long timestampMs) {
            LocalDate date = LocalDate.ofEpochDay(Math.floorDiv(timestampMs, DAY_MS));
            return date.getYear() * 12 + (date.getMonthValue() - 1);
        }

        @Override public int count() { return count; }

        @Override public long startMs(int b) {
            int monthIndex = firstMonthIndex + b;
            return LocalDate.of(Math.floorDiv(monthIndex, 12), Math.floorMod(monthIndex, 12) + 1, 1)
                .toEpochDay() * DAY_MS;
        }

        /** Midnight of the month's last day — the month-end approximation for daily input. */
        @Override public long lastExpectedSampleMs(int b) { return startMs(b + 1) - DAY_MS; }
    }

    /**
     * Water-year buckets with a configurable start month. E.g. for a Jul–Jun water year,
     * water year 2020 runs 2020-07-01 to 2021-06-30; dates before the start month belong
     * to the previous water year.
     */
    private static final class AnnualBuckets implements Buckets {
        private final int startMonth;
        private final int firstWaterYear;
        private final int count;

        AnnualBuckets(TimeSeriesData series, int startMonth) {
            this.startMonth = startMonth;
            long[] timestamps = series.getTimestamps();
            firstWaterYear = waterYearOf(timestamps[0], startMonth);
            count = waterYearOf(timestamps[timestamps.length - 1], startMonth) - firstWaterYear + 1;
        }

        private static int waterYearOf(long timestampMs, int startMonth) {
            LocalDate date = LocalDate.ofEpochDay(Math.floorDiv(timestampMs, DAY_MS));
            return date.getMonthValue() < startMonth ? date.getYear() - 1 : date.getYear();
        }

        @Override public int count() { return count; }

        @Override public long startMs(int b) {
            return LocalDate.of(firstWaterYear + b, startMonth, 1).toEpochDay() * DAY_MS;
        }

        /** Midnight of the water year's last day — the year-end approximation for daily input. */
        @Override public long lastExpectedSampleMs(int b) { return startMs(b + 1) - DAY_MS; }
    }
}
