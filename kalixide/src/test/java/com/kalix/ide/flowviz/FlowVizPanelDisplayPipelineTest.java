package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.stats.SeasonalMaskMode;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

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

        FlowVizPanel panel = new FlowVizPanel();
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

        FlowVizPanel panel = new FlowVizPanel();
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
    /** Daily points from 1 Jan 2020 up to (not including) {@code endExclusive}, all valued 1. */
    private static TimeSeriesData dailyFrom2020(LocalDate endExclusive) {
        LocalDate start = LocalDate.of(2020, 1, 1);
        int days = (int) (endExclusive.toEpochDay() - start.toEpochDay());
        long[] timestamps = new long[days];
        double[] values = new double[days];
        for (int i = 0; i < days; i++) {
            timestamps[i] = start.plusDays(i).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            values[i] = 1;
        }
        return new TimeSeriesData(timestamps, values);
    }

    /**
     * The seasonal mask must be applied BEFORE aggregation (#235). Aggregating first
     * stamps every annual point at the period start, which left the mask able only to
     * keep or drop the whole year.
     */
    @Test
    void seasonalMaskSelectsMonthsWithinAnAnnualAggregate() {
        DataSet pool = new DataSet();
        pool.addSeries(REF, dailyFrom2020(LocalDate.of(2021, 1, 1))); // all of 2020, 1/day

        FlowVizPanel panel = new FlowVizPanel();
        panel.setDataSet(pool);
        panel.setVisibleSeries(List.of(REF));
        panel.setAggregation(AggregationPeriod.ANNUAL_JAN_DEC, AggregationMethod.SUM);
        assertEquals(366.0, panel.displayDataSetForTests().getSeries(REF).getValues()[0], 0.0,
            "unmasked: all 366 days of 2020 sum into one annual point");

        panel.setSeasonalMaskMode(SeasonalMaskMode.of(Set.of(Month.JANUARY)));
        TimeSeriesData masked = panel.displayDataSetForTests().getSeries(REF);
        assertEquals(1, masked.getPointCount(), "still one annual point");
        assertEquals(31.0, masked.getValues()[0], 0.0,
            "the annual sum covers January only - not the unmasked 366");
    }

    /**
     * The reported symptom: deselecting the month an annual period starts in used to
     * empty the view, because every aggregated point carried that start month.
     */
    @Test
    void deselectingTheStartMonthDoesNotEmptyAnAnnualAggregate() {
        DataSet pool = new DataSet();
        pool.addSeries(REF, dailyFrom2020(LocalDate.of(2021, 1, 1)));

        FlowVizPanel panel = new FlowVizPanel();
        panel.setDataSet(pool);
        panel.setVisibleSeries(List.of(REF));
        panel.setAggregation(AggregationPeriod.ANNUAL_JAN_DEC, AggregationMethod.SUM);
        panel.setSeasonalMaskMode(SeasonalMaskMode.of(Set.of(Month.FEBRUARY)));

        TimeSeriesData masked = panel.displayDataSetForTests().getSeries(REF);
        assertNotNull(masked, "excluding the period's start month must not wipe the series");
        assertEquals(1, masked.getPointCount());
        assertEquals(29.0, masked.getValues()[0], 0.0, "February 2020 had 29 days");
    }
}
