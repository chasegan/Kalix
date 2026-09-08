package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.transform.YAxisScale;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link FlowVizPanel#centerViewportOn}: the time axis re-centres on the
 * target keeping its span, the value axis re-centres on an off-screen value
 * keeping its span in the scale's transformed space, and the move counts as a
 * user pan (touched flag), so later refreshes don't refit over it.
 */
class FlowVizPanelCenterViewportTest {

    private static final long DAY = 86_400_000L;

    private static FlowVizPanel panelWithThreeDays() {
        FlowVizPanel panel = new FlowVizPanel();
        DataSet dataSet = new DataSet();
        DatasetSeries ref = new DatasetSeries("/tmp/x.csv", "flow");
        dataSet.addSeries(ref, new TimeSeriesData(
            new long[] {0, DAY, 2 * DAY}, new double[] {1, 2, 3}));
        panel.setDataSet(dataSet);
        panel.setVisibleSeries(List.of(ref));
        panel.refreshData(true);
        return panel;
    }

    @Test
    void centresTimeAxisKeepingTheSpan() {
        FlowVizPanel panel = panelWithThreeDays();

        ViewPort before = panel.viewportForTests();
        assertNotNull(before);
        long span = before.getTimeRangeMs();
        assertFalse(panel.isUserViewportTouched(), "a programmatic fit is not a user pan");

        long target = 10 * DAY;
        panel.centerViewportOn(target, Double.NaN);

        ViewPort after = panel.viewportForTests();
        assertEquals(target - span / 2, after.getStartTimeMs());
        assertEquals(span, after.getTimeRangeMs(), "the zoom level survives the centring");
        assertTrue(panel.isUserViewportTouched(), "centring counts as a user pan");
    }

    @Test
    void centresTheValueAxisOnAnOffScreenValueKeepingTheSpan() {
        FlowVizPanel panel = panelWithThreeDays();
        ViewPort before = panel.viewportForTests();
        double span = before.getValueRange();

        panel.centerViewportOn(10 * DAY, 5000);

        ViewPort after = panel.viewportForTests();
        assertEquals(5000, (after.getMinValue() + after.getMaxValue()) / 2, 1e-9);
        assertEquals(span, after.getValueRange(), 1e-9, "the zoom level survives the centring");
    }

    /** On a log axis the span that survives is the decade count, not the data-space width. */
    @Test
    void centresTheValueAxisOnALogScaleKeepingTheDecadeSpan() {
        FlowVizPanel panel = panelWithThreeDays();
        panel.setYAxisScale(YAxisScale.LOG);
        ViewPort before = panel.viewportForTests();
        double decades = Math.log10(before.getMaxValue() / before.getMinValue());

        panel.centerViewportOn(10 * DAY, 5000);

        ViewPort after = panel.viewportForTests();
        assertEquals(5000, Math.sqrt(after.getMinValue() * after.getMaxValue()), 5000 * 1e-9, "centred geometrically");
        assertEquals(decades, Math.log10(after.getMaxValue() / after.getMinValue()), 1e-9, "the decade span survives");
    }
}
