package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;

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
            pool, List.of(a, missing, b), AggregationPeriod.ORIGINAL, AggregationMethod.SUM);

        // Selection order wins (the first series is the bivariate reference and
        // legend anchor), and the unresolvable ref is skipped, not an error.
        assertEquals(List.of(a, b), List.copyOf(out.keySet()));
        assertEquals(3, out.get(a).getPointCount());
    }

    @Test
    void emptySelectionAndNullPoolAreEmptyResults() {
        assertTrue(AggregationPipeline.aggregate(new DataSet(), List.of(),
            AggregationPeriod.ORIGINAL, AggregationMethod.SUM).isEmpty());
        assertTrue(AggregationPipeline.aggregate(null,
            List.of(new DatasetSeries("/x.csv", "a")),
            AggregationPeriod.ORIGINAL, AggregationMethod.SUM).isEmpty());
    }
}
