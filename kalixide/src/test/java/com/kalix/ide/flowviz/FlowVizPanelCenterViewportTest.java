package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.rendering.ViewPort;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link FlowVizPanel#centerViewportOn}: the time axis re-centres on the
 * target keeping its span, and the move counts as a user pan (touched flag),
 * so later refreshes don't refit over it.
 */
class FlowVizPanelCenterViewportTest {

    private static final long DAY = 86_400_000L;

    @Test
    void centresTimeAxisKeepingTheSpan() {
        FlowVizPanel panel = new FlowVizPanel();
        DataSet dataSet = new DataSet();
        DatasetSeries ref = new DatasetSeries("/tmp/x.csv", "flow");
        dataSet.addSeries(ref, new TimeSeriesData(
            new long[] {0, DAY, 2 * DAY}, new double[] {1, 2, 3}));
        panel.setDataSet(dataSet);
        panel.setVisibleSeries(List.of(ref));
        panel.refreshData(true);

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
}
