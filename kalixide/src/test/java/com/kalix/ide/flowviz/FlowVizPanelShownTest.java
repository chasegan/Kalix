package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.transform.PlotType;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the hidden plot's laziness: new data and changes of state while hidden wait, and
 * showing the page catches up.
 */
class FlowVizPanelShownTest {

    private static final long DAY = 86_400_000L;
    private static final DatasetSeries A = new DatasetSeries("/tmp/x.csv", "a");

    private static TimeSeriesData days(int count) {
        long[] times = new long[count];
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            times[i] = i * DAY;
            values[i] = i;
        }
        return new TimeSeriesData(times, values);
    }

    @Test
    void newDataWhileHiddenIsDrawnOnceShown() {
        FlowVizPanel panel = new FlowVizPanel();
        DataSet pool = new DataSet();
        pool.addSeries(A, days(3));
        panel.setDataSet(pool);
        panel.model().update(st -> st.withVisibleSeries(List.of(A)));
        assertEquals(3, panel.displayDataSetForTests().getSeries(A).getPointCount());

        panel.setShown(false);
        pool.addSeries(A, days(5));
        panel.model().dataChanged();
        assertNull(panel.displayDataSetForTests(),
            "a hidden plot defers the rebuild, and lets go of the replaced data meanwhile");

        panel.setShown(true);
        assertEquals(5, panel.displayDataSetForTests().getSeries(A).getPointCount(),
            "and catches up when shown");
    }

    @Test
    void changeWhileHiddenFitsWhenShownIntoItsHistoryEntry() {
        FlowVizPanel panel = new FlowVizPanel();
        DataSet pool = new DataSet();
        pool.addSeries(A, days(3)); // values 0, 1, 2
        panel.setDataSet(pool);
        panel.model().update(st -> st.withVisibleSeries(List.of(A)).withAutoYMode(false));
        DataSet values = panel.displayDataSetForTests();

        panel.setShown(false);
        panel.model().update(st -> st.withPlotType(PlotType.CUMULATIVE)); // 0, 1, 3
        assertSame(values, panel.displayDataSetForTests(), "a hidden plot defers the rebuild");

        panel.setShown(true);
        FlowVizState shown = panel.model().current();
        assertTrue(shown.getMaxValue() > 2.0, "fitted to the cumulative data once shown");
        panel.undo();
        assertEquals(shown, panel.redo(), "the fit is part of the change's history entry");
    }
}
