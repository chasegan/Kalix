package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link FlowVizModel#dataChanged}: derived data built from series the pool has
 * replaced is released at once, while a change to series the tab does not show keeps it.
 */
class FlowVizModelReleaseTest {

    private static final long DAY = 86_400_000L;
    private static final DatasetSeries A = new DatasetSeries("/tmp/x.csv", "a");
    private static final DatasetSeries B = new DatasetSeries("/tmp/x.csv", "b");

    private static TimeSeriesData series(double... values) {
        long[] times = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            times[i] = i * DAY;
        }
        return new TimeSeriesData(times, values);
    }

    @Test
    void replacedDataReleasesTheDisplaySetAndUnrelatedDataKeepsIt() {
        DataSet pool = new DataSet();
        pool.addSeries(A, series(1, 2, 3));
        FlowVizModel model = new FlowVizModel();
        model.setPool(pool);
        model.replace(FlowVizState.INITIAL.withVisibleSeries(List.of(A)));
        DataSet shown = model.display();

        pool.addSeries(B, series(9, 9));
        model.dataChanged();
        assertTrue(model.holdsDisplay(shown), "a series the tab does not show changes nothing");
        assertSame(shown, model.display());

        pool.addSeries(A, series(4, 5, 6));
        model.dataChanged();
        assertFalse(model.holdsDisplay(shown), "the old run's display set is let go before any page asks");
        assertNotSame(shown, model.display());
    }
}
