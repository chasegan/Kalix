package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.transform.YAxisScale;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that zoom-to-fit honours the scale's domain the way auto-Y does: on LOG a series
 * that touches zero fits to its smallest positive value rather than padding from NaN.
 */
class FlowVizPanelZoomToFitTest {

    private static final long DAY = 86_400_000L;

    @Test
    void logZoomToFitSkipsValuesTheScaleCannotShow() {
        FlowVizPanel panel = new FlowVizPanel();
        DataSet dataSet = new DataSet();
        DatasetSeries ref = new DatasetSeries("/tmp/x.csv", "flow");
        // All below the default small-value clamp, so only the domain rule can save the fit
        dataSet.addSeries(ref, new TimeSeriesData(
            new long[] {0, DAY, 2 * DAY}, new double[] {0.0, 0.5, 0.9}));
        panel.setDataSet(dataSet);
        panel.setVisibleSeries(List.of(ref));
        panel.setYAxisScale(YAxisScale.LOG);
        panel.refreshData(true);

        panel.zoomToFit();

        ViewPort v = panel.viewportForTests();
        assertTrue(Double.isFinite(v.getMinValue()) && Double.isFinite(v.getMaxValue()), "finite bounds: " + v);
        assertTrue(v.getMinValue() > 0 && v.getMinValue() <= 0.5, "fits to the smallest positive value: " + v);
        assertTrue(v.getMaxValue() >= 0.9, "reaches the maximum: " + v);
    }
}
