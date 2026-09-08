package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;

import com.kalix.ide.flowviz.stats.SeasonalMaskMode;
import java.util.Set;
import java.time.Month;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregationPipelineTest {

    private static final long DAY_MS = 86_400_000L;

    private static TimeSeriesData daily(double... values) {
        long[] timestamps = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            timestamps[i] = 1_577_836_800_000L + i * DAY_MS; // 2020-01-01 UTC onward
        }
        return new TimeSeriesData(timestamps, values);
    }

    @Test
    void preservesSelectionOrderAndSkipsUnresolvableRefs() {
        SeriesRef a = new DatasetSeries("/x.csv", "a");
        SeriesRef b = new DatasetSeries("/x.csv", "b");
        SeriesRef missing = new DatasetSeries("/x.csv", "missing");
        DataSet pool = new DataSet();
        pool.addSeries(b, daily(1, 2, 3)); // pool insertion order deliberately b-first
        pool.addSeries(a, daily(4, 5, 6));

        LinkedHashMap<SeriesRef, TimeSeriesData> out = AggregationPipeline.aggregate(
            pool, List.of(a, missing, b), AggregationPeriod.ORIGINAL, AggregationMethod.SUM,
            SeasonalMaskMode.DISABLED);

        // Selection order wins (the first series is the bivariate reference and
        // legend anchor), and the unresolvable ref is skipped, not an error.
        assertEquals(List.of(a, b), List.copyOf(out.keySet()));
        assertEquals(3, out.get(a).getPointCount());
    }

    @Test
    void emptySelectionAndNullPoolAreEmptyResults() {
        assertTrue(AggregationPipeline.aggregate(new DataSet(), List.of(),
                                                 AggregationPeriod.ORIGINAL, AggregationMethod.SUM,
                                                 SeasonalMaskMode.DISABLED).isEmpty());
        assertTrue(AggregationPipeline.aggregate(null,
                                                 List.of(new DatasetSeries("/x.csv", "a")),
                                                 AggregationPeriod.ORIGINAL, AggregationMethod.SUM,
                                                 SeasonalMaskMode.DISABLED).isEmpty());
    }
    /**
     * Each series is masked against its OWN timestamps. The two series here share no
     * timestamp at all (midnight vs midday), so a single mask built from one grid and
     * reused across both would drop every point of the other.
     */
    @Test
    void seasonalMaskingIsPerSeriesWhenGridsDisagree() {
        long dayMs = 86_400_000L;
        long jan1Midnight = 1_577_836_800_000L;
        int days = 60; // 1 Jan - 29 Feb 2020

        long[] midnights = new long[days];
        long[] middays = new long[days];
        double[] values = new double[days];
        for (int i = 0; i < days; i++) {
            midnights[i] = jan1Midnight + i * dayMs;
            middays[i] = jan1Midnight + i * dayMs + dayMs / 2;
            values[i] = 1;
        }

        SeriesRef midnightRef = new DatasetSeries("/t.csv", "midnight");
        SeriesRef middayRef = new DatasetSeries("/t.csv", "midday");
        DataSet pool = new DataSet();
        pool.addSeries(midnightRef, new TimeSeriesData(midnights, values.clone()));
        pool.addSeries(middayRef, new TimeSeriesData(middays, values.clone()));

        var aggregated = AggregationPipeline.aggregate(
            pool, List.of(midnightRef, middayRef),
            AggregationPeriod.ORIGINAL, AggregationMethod.SUM,
            SeasonalMaskMode.of(Set.of(Month.FEBRUARY)));

        assertEquals(29, aggregated.get(midnightRef).getPointCount(), "February 2020 has 29 days");
        assertEquals(29, aggregated.get(middayRef).getPointCount(),
            "the off-grid series keeps its own February points, not the other series' timestamps");
    }
}
