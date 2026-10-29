package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.transform.YAxisScale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how series move between the left and right (y2) axes: the viewport has a y2 range
 * exactly while some visible series is on the right, a new right axis fits its series, later
 * moves reset neither axis, auto-Y fits each axis to its own series, and the assignment rides
 * undo and tab duplication. Stale refs are dropped, never an error.
 */
class FlowVizPanelRightAxisTest {

    private static final long DAY = 86_400_000L;

    private final DatasetSeries small = new DatasetSeries("/tmp/x.csv", "small");   // 0..10
    private final DatasetSeries large = new DatasetSeries("/tmp/x.csv", "large");   // 1000..2000
    private final DatasetSeries larger = new DatasetSeries("/tmp/x.csv", "larger"); // 5000..6000
    private FlowVizPanel panel;

    @BeforeEach
    void setUp() {
        DataSet dataSet = new DataSet();
        long[] times = {0, DAY, 2 * DAY};
        dataSet.addSeries(small, new TimeSeriesData(times, new double[] {0, 5, 10}));
        dataSet.addSeries(large, new TimeSeriesData(times, new double[] {1000, 1500, 2000}));
        dataSet.addSeries(larger, new TimeSeriesData(times, new double[] {5000, 5500, 6000}));
        panel = new FlowVizPanel();
        panel.setDataSet(dataSet);
        panel.setVisibleSeries(List.of(small, large, larger));
        panel.zoomToFit();
    }

    private ViewPort view() {
        return panel.viewportForTests();
    }

    @Test
    void aNewRightAxisFitsItsSeriesAndLeavesTheLeftAlone() {
        double y1Min = view().getMinValue();
        double y1Max = view().getMaxValue();

        panel.setSeriesOnRightAxis(large, true);

        assertTrue(panel.isRightAxisInUse());
        assertTrue(view().hasY2());
        assertTrue(view().getY2MinValue() < 1000 && view().getY2MinValue() > 900, "y2 fits large: " + view());
        assertTrue(view().getY2MaxValue() > 2000 && view().getY2MaxValue() < 2100, "y2 fits large: " + view());
        assertEquals(y1Min, view().getMinValue(), "manual mode: y1 is not reset");
        assertEquals(y1Max, view().getMaxValue());
    }

    @Test
    void onLogAnAutoYLeftAxisWithNoSeriesKeepsItsRange() {
        panel.setYAxisScale(YAxisScale.LOG);
        panel.setAutoYMode(true);
        panel.setSeriesOnRightAxis(small, true);
        panel.setSeriesOnRightAxis(large, true);
        double y1Min = view().getMinValue(); // fitted to larger alone
        double y1Max = view().getMaxValue();

        panel.setSeriesOnRightAxis(larger, true); // the left axis' last series

        assertEquals(y1Min, view().getMinValue(), "not the -1..1 default, which LOG cannot show");
        assertEquals(y1Max, view().getMaxValue());
        assertTrue(view().getMinValue() > 0, "showable on LOG: " + view());
    }

    @Test
    void zoomToFitGivesALeftAxisWithNoSeriesTheRightAxisRange() {
        panel.setSeriesOnRightAxis(small, true);
        panel.setSeriesOnRightAxis(large, true);
        panel.setSeriesOnRightAxis(larger, true);

        panel.zoomToFit();

        assertEquals(view().getY2MinValue(), view().getMinValue(), "an empty axis takes the other's range");
        assertEquals(view().getY2MaxValue(), view().getMaxValue());
    }

    @Test
    void laterMovesDoNotResetTheRightAxis() {
        panel.setSeriesOnRightAxis(large, true);
        double y2Min = view().getY2MinValue();
        double y2Max = view().getY2MaxValue();

        panel.setSeriesOnRightAxis(larger, true);

        assertEquals(y2Min, view().getY2MinValue(), "y2 is sized only when it first appears");
        assertEquals(y2Max, view().getY2MaxValue());
    }

    @Test
    void movingTheLastSeriesBackDropsTheRightAxis() {
        panel.setSeriesOnRightAxis(large, true);
        panel.setSeriesOnRightAxis(large, false);

        assertFalse(panel.isRightAxisInUse());
        assertFalse(view().hasY2());
    }

    @Test
    void autoYFitsEachAxisToItsOwnSeries() {
        panel.setAutoYMode(true);
        panel.setSeriesOnRightAxis(large, true);
        panel.setSeriesOnRightAxis(larger, true);

        assertTrue(view().getMaxValue() < 20, "y1 holds only small: " + view());
        assertTrue(view().getY2MinValue() < 1000 && view().getY2MaxValue() > 6000, "y2 holds both: " + view());
    }

    @Test
    void zoomToFitFitsEachAxisToItsOwnSeries() {
        panel.setSeriesOnRightAxis(larger, true);
        panel.zoomToFit();

        assertTrue(view().getMaxValue() > 2000 && view().getMaxValue() < 2200, "y1 holds small and large: " + view());
        assertTrue(view().getY2MinValue() > 4900 && view().getY2MaxValue() < 6100, "y2 holds larger: " + view());
    }

    @Test
    void aSeriesThatLeavesThePlotLeavesTheRightAxisAndReturnsOnTheLeft() {
        panel.setSeriesOnRightAxis(large, true);

        panel.setVisibleSeries(List.of(small));
        assertFalse(panel.isRightAxisInUse());
        assertFalse(view().hasY2());

        panel.setVisibleSeries(List.of(small, large));
        assertFalse(panel.isOnRightAxis(large));
    }

    @Test
    void movingASeriesThatIsNotVisibleIsIgnored() {
        panel.setVisibleSeries(List.of(small));
        panel.setSeriesOnRightAxis(large, true);

        assertFalse(panel.isRightAxisInUse());
        assertFalse(view().hasY2());
    }

    @Test
    void undoRestoresTheAxisAssignmentAndRange() {
        panel.setSeriesOnRightAxis(large, true);
        double y2Min = view().getY2MinValue();
        panel.setSeriesOnRightAxis(larger, true);

        panel.undo();
        assertTrue(panel.isOnRightAxis(large));
        assertFalse(panel.isOnRightAxis(larger));
        assertEquals(y2Min, view().getY2MinValue());

        panel.undo();
        assertFalse(panel.isRightAxisInUse());
        assertFalse(view().hasY2());

        panel.redo();
        assertTrue(panel.isOnRightAxis(large));
        assertEquals(y2Min, view().getY2MinValue());
    }

    @Test
    void aDuplicatedTabKeepsTheAxisAssignment() {
        panel.setSeriesOnRightAxis(large, true);

        FlowVizPanel copy = new FlowVizPanel();
        copy.setDataSet(new DataSet());
        copy.copyHistoryFrom(panel);

        assertTrue(copy.isOnRightAxis(large));
        assertEquals(view().getY2MinValue(), copy.viewportForTests().getY2MinValue());
    }
}
