package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins that the shared AggregationPipeline actually feeds the plot's display
 * dataset (output-level, not just compile-level): visible series land in the
 * rendered data, and an aggregation change reshapes it.
 */
class PlotPanelDisplayPipelineTest {

    private static final long DAY_MS = 86_400_000L;
    private static final SeriesRef REF = new DatasetSeries("/t.csv", "flow");

    private static TimeSeriesData daily(int days) {
        long[] timestamps = new long[days];
        double[] values = new double[days];
        for (int i = 0; i < days; i++) {
            timestamps[i] = 1_577_836_800_000L + i * DAY_MS; // all within Jan 2020
            values[i] = i + 1;
        }
        return new TimeSeriesData(timestamps, values);
    }

    @Test
    void aggregationChangesReshapeTheDisplayedData() {
        DataSet pool = new DataSet();
        pool.addSeries(REF, daily(6));

        PlotPanel panel = new PlotPanel();
        panel.setDataSet(pool);
        panel.setVisibleSeries(List.of(REF));

        DataSet displayed = panel.displayDataSetForTests();
        assertNotNull(displayed, "visible series must reach the display pipeline");
        assertEquals(6, displayed.getSeries(REF).getPointCount(), "ORIGINAL keeps every point");

        panel.setAggregation(AggregationPeriod.MONTHLY, AggregationMethod.MEAN);
        assertEquals(1, panel.displayDataSetForTests().getSeries(REF).getPointCount(),
            "six January days aggregate to one monthly point");
    }

    @Test
    void eachMaskFiltersNonReferenceSeriesToPairwiseOverlap() {
        long[] timestamps = {1_577_836_800_000L, 1_577_836_800_000L + DAY_MS, 1_577_836_800_000L + 2 * DAY_MS};
        SeriesRef a = new DatasetSeries("/t.csv", "reference");
        SeriesRef b = new DatasetSeries("/t.csv", "other");
        DataSet pool = new DataSet();
        pool.addSeries(a, new TimeSeriesData(timestamps, new double[] {1, Double.NaN, 3}));
        pool.addSeries(b, new TimeSeriesData(timestamps, new double[] {10, 20, 30}));

        PlotPanel panel = new PlotPanel();
        panel.setDataSet(pool);
        panel.setVisibleSeries(List.of(a, b));
        panel.setMaskMode(com.kalix.ide.flowviz.stats.MaskMode.EACH);

        DataSet displayed = panel.displayDataSetForTests();
        // The reference draws on its own valid points, untouched (3 points, gap at 1)...
        assertEquals(3, displayed.getSeries(a).getPointCount());
        assertEquals(1.0, displayed.getSeries(a).getValues()[0]);
        // ...and the non-reference series is FILTERED to the pairwise overlap
        // (the mask drops points, it does not NaN them): exactly the data its
        // bivariate statistic uses.
        TimeSeriesData other = displayed.getSeries(b);
        assertEquals(2, other.getPointCount(), "the point under the reference's gap is dropped");
        assertEquals(10.0, other.getValues()[0], 0.0);
        assertEquals(30.0, other.getValues()[1], 0.0);
        assertEquals(timestamps[2], other.getTimestamps()[1], "overlap keeps original timestamps");
    }
}
