package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the hidden plot's laziness: new data while hidden waits, and showing the page
 * catches up.
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
        panel.setVisibleSeries(List.of(A));
        assertEquals(3, panel.displayDataSetForTests().getSeries(A).getPointCount());

        panel.setShown(false);
        pool.addSeries(A, days(5));
        panel.model().dataChanged();
        assertEquals(3, panel.displayDataSetForTests().getSeries(A).getPointCount(),
            "a hidden plot defers the refresh");

        panel.setShown(true);
        assertEquals(5, panel.displayDataSetForTests().getSeries(A).getPointCount(),
            "and catches up when shown");
    }
}
