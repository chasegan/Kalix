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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that a scale switch with auto-Y off leaves the stored bounds equal to the bounds
 * the axis draws: everything that reads them (copy-axis, set-limits, undo, saved state)
 * must agree with the plot.
 */
class FlowVizPanelScaleSwitchTest {

    private static final long DAY = 86_400_000L;

    @Test
    void switchingToLogWithAutoYOffStoresThePositiveMinimumTheAxisDraws() {
        FlowVizPanel panel = new FlowVizPanel();
        DataSet dataSet = new DataSet();
        DatasetSeries ref = new DatasetSeries("/tmp/x.csv", "flow");
        dataSet.addSeries(ref, new TimeSeriesData(
            new long[] {0, DAY, 2 * DAY}, new double[] {-5, 50, 100}));
        panel.setDataSet(dataSet);
        panel.setVisibleSeries(List.of(ref));
        panel.refreshData(true);
        assertFalse(panel.isAutoYMode(), "auto-Y off is the mode under test");
        assertTrue(panel.viewportForTests().getMinValue() < 0, "the linear fit reaches below zero");

        panel.setYAxisScale(YAxisScale.LOG);

        ViewPort v = panel.viewportForTests();
        assertEquals(1e-6, v.getMinValue(), 1e-15, "the stored minimum is the drawn fallback, not -5");
        assertEquals(v.getTransformedMin(), YAxisScale.LOG.transform(v.getMinValue()), 1e-12, "stored and drawn agree");
    }
}
