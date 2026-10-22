package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.flowviz.transform.YAxisScale;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins two of the panel's fit rules that the other viewport tests don't reach: a new
 * reference series on a difference plot re-fits Y under auto-Y, and a scale switch with
 * auto-Y off keeps the bounds.
 */
class FlowVizPanelFitRulesTest {

    private static final long DAY = 86_400_000L;
    private static final DatasetSeries A = new DatasetSeries("/tmp/x.csv", "a");
    private static final DatasetSeries B = new DatasetSeries("/tmp/x.csv", "b");

    private static FlowVizPanel panel(double[] a, double[] b) {
        FlowVizPanel panel = new FlowVizPanel();
        panel.setSize(800, 600);
        DataSet dataSet = new DataSet();
        dataSet.addSeries(A, new TimeSeriesData(new long[] {0, DAY, 2 * DAY}, a));
        dataSet.addSeries(B, new TimeSeriesData(new long[] {0, DAY, 2 * DAY}, b));
        panel.setDataSet(dataSet);
        return panel;
    }

    @Test
    void newReferenceOnADifferencePlotRefitsYAndKeepsXUnderAutoY() {
        FlowVizPanel panel = panel(new double[] {0, 10, 20}, new double[] {5, 5, 5});
        panel.setVisibleSeries(List.of(A, B));
        panel.setPlotType(PlotType.DIFFERENCE);
        panel.setAutoYMode(true);
        panel.refreshData(true);
        ViewPort before = panel.viewportForTests();
        assertTrue(before.getMaxValue() < 10, "against a, b's differences top out at 5");

        panel.setVisibleSeries(List.of(B, A)); // b becomes the reference

        ViewPort after = panel.viewportForTests();
        assertEquals(before.getStartTimeMs(), after.getStartTimeMs(), "auto-Y keeps the X range");
        assertEquals(before.getEndTimeMs(), after.getEndTimeMs(), "auto-Y keeps the X range");
        assertTrue(after.getMaxValue() >= 15, "Y re-fitted to a's differences against b, up to 15");
    }

    @Test
    void scaleSwitchWithAutoYOffKeepsTheBoundsAndRecordsAnEntry() {
        FlowVizPanel panel = panel(new double[] {100, 150, 200}, new double[] {120, 140, 160});
        panel.setVisibleSeries(List.of(A, B));
        panel.refreshData(true);
        ViewPort before = panel.viewportForTests();
        assertTrue(before.getMinValue() > 0, "fixture: a positive fit, showable on log");

        panel.setYAxisScale(YAxisScale.LOG);

        ViewPort after = panel.viewportForTests();
        assertEquals(before.getMinValue(), after.getMinValue(), 1e-12, "no re-fit with auto-Y off");
        assertEquals(before.getMaxValue(), after.getMaxValue(), 1e-12, "no re-fit with auto-Y off");
        assertEquals(YAxisScale.LOG, after.getYAxisScale());
        assertTrue(panel.canUndo(), "the switch is one undoable entry");
    }
}
