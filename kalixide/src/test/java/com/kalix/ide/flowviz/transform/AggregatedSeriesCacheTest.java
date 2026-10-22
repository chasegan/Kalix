package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.stats.SeasonalMaskMode;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The cache behind a tab's aggregates: reuse while the pool holds the same data
 * object, recompute when it doesn't, and never hold more than the selection.
 */
class AggregatedSeriesCacheTest {

    private static final long DAY_MS = 86_400_000L;

    private static final SeriesRef A = new DatasetSeries("/x.csv", "a");
    private static final SeriesRef B = new DatasetSeries("/x.csv", "b");

    private static TimeSeriesData daily(double... values) {
        long[] timestamps = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            timestamps[i] = 1_577_836_800_000L + i * DAY_MS; // 2020-01-01 UTC onward
        }
        return new TimeSeriesData(timestamps, values);
    }

    /** All of January 2020, every day {@code value}: a complete month, so monthly aggregates are defined. */
    private static TimeSeriesData january(double value) {
        double[] values = new double[31];
        Arrays.fill(values, value);
        return daily(values);
    }

    private static LinkedHashMap<SeriesRef, TimeSeriesData> monthly(
            AggregatedSeriesCache cache, DataSet pool, List<SeriesRef> refs) {
        return AggregationPipeline.aggregate(cache, pool, refs,
            AggregationPeriod.MONTHLY, AggregationMethod.SUM, SeasonalMaskMode.DISABLED);
    }

    @Test
    void sameSourceObjectReusesTheAggregate() {
        DataSet pool = new DataSet();
        pool.addSeries(A, daily(1, 2, 3));
        AggregatedSeriesCache cache = new AggregatedSeriesCache();
        TimeSeriesData first = monthly(cache, pool, List.of(A)).get(A);
        assertSame(first, monthly(cache, pool, List.of(A)).get(A));
    }

    @Test
    void newSourceObjectIsRecomputed() {
        DataSet pool = new DataSet();
        pool.addSeries(A, daily(1, 2, 3));
        AggregatedSeriesCache cache = new AggregatedSeriesCache();
        TimeSeriesData first = monthly(cache, pool, List.of(A)).get(A);

        pool.addSeries(A, january(2)); // new data arrives as a new object
        TimeSeriesData second = monthly(cache, pool, List.of(A)).get(A);
        assertNotSame(first, second);
        assertEquals(62.0, second.getValues()[0], 1e-9);
    }

    @Test
    void settingsChangeClearsEveryEntry() {
        DataSet pool = new DataSet();
        pool.addSeries(A, january(2));
        AggregatedSeriesCache cache = new AggregatedSeriesCache();
        TimeSeriesData monthlySum = monthly(cache, pool, List.of(A)).get(A);

        TimeSeriesData monthlyMean = AggregationPipeline.aggregate(cache, pool, List.of(A),
            AggregationPeriod.MONTHLY, AggregationMethod.MEAN, SeasonalMaskMode.DISABLED).get(A);
        assertEquals(2.0, monthlyMean.getValues()[0], 1e-9);
        assertNotSame(monthlySum, monthly(cache, pool, List.of(A)).get(A), "back to SUM recomputes");
    }

    @Test
    void selectionOrderIsKeptAndMissingRefsSkipped() {
        DataSet pool = new DataSet();
        pool.addSeries(B, daily(1));
        pool.addSeries(A, daily(2));
        SeriesRef missing = new DatasetSeries("/x.csv", "missing");
        AggregatedSeriesCache cache = new AggregatedSeriesCache();
        assertEquals(List.of(A, B), List.copyOf(monthly(cache, pool, List.of(A, missing, B)).keySet()));
        assertEquals(2, cache.size());
    }

    @Test
    void unrequestedSeriesAreDropped() {
        DataSet pool = new DataSet();
        pool.addSeries(A, daily(1));
        pool.addSeries(B, daily(2));
        AggregatedSeriesCache cache = new AggregatedSeriesCache();
        monthly(cache, pool, List.of(A, B));
        monthly(cache, pool, List.of(B));
        assertEquals(1, cache.size());
    }

    @Test
    void nativeResolutionWithoutSeasonalMaskHoldsTheSourceItself() {
        DataSet pool = new DataSet();
        TimeSeriesData source = daily(1, 2, 3);
        pool.addSeries(A, source);
        TimeSeriesData out = AggregationPipeline.aggregate(new AggregatedSeriesCache(), pool, List.of(A),
            AggregationPeriod.ORIGINAL, AggregationMethod.SUM, SeasonalMaskMode.DISABLED).get(A);
        assertSame(source, out, "no copy at native resolution");
    }
}
