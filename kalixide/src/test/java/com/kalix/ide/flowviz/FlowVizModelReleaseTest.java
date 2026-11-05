package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the model's release of derived data before any page asks: on
 * {@link FlowVizModel#dataChanged}, what was built from series the pool has replaced,
 * while a change to series the tab does not show keeps it; and on a change of state,
 * what the new state no longer describes.
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

    @Test
    void untickedSeriesIsReleasedBeforeAnyPageAsks() {
        DataSet pool = new DataSet();
        pool.addSeries(A, series(1, 2, 3));
        pool.addSeries(B, series(9, 9));
        FlowVizModel model = new FlowVizModel();
        model.setPool(pool);
        model.replace(FlowVizState.INITIAL.withVisibleSeries(List.of(A, B)));
        DataSet shown = model.display();
        assertEquals(2, model.cachedAggregates());

        // The tab's pages are hidden: nothing calls display() after the untick, and the
        // pool is about to drop A. The model must not be what keeps A alive.
        model.update(s -> s.withVisibleSeries(List.of(B)));
        assertFalse(model.holdsDisplay(shown), "the display set with A in it is let go");
        assertEquals(1, model.cachedAggregates(), "and so is A's aggregate");

        model.undo();
        assertEquals(2, model.display().getSeriesRefs().size(), "re-selected, A is aggregated afresh");
    }

    @Test
    void aChangeTheDisplaySetWasNotBuiltUnderKeepsIt() {
        DataSet pool = new DataSet();
        pool.addSeries(A, series(1, 2, 3));
        FlowVizModel model = new FlowVizModel();
        model.setPool(pool);
        model.replace(FlowVizState.INITIAL.withVisibleSeries(List.of(A)));
        DataSet shown = model.display();

        model.update(s -> s.withAutoYMode(!s.isAutoYMode()));
        assertTrue(model.holdsDisplay(shown), "auto-Y is not part of what the set was built under");
    }
}
