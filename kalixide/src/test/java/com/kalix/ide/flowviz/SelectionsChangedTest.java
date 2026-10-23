package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.style.LineStyle;
import com.kalix.ide.flowviz.style.StrokeStyle;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link VizHost#onSelectionsChanged}: the host hears every change of any tab's
 * selection, and a closed tab, but not a change of settings alone.
 */
class SelectionsChangedTest {

    private static final SeriesRef A = new DatasetSeries("/t.csv", "a");
    private static final SeriesRef B = new DatasetSeries("/t.csv", "b");

    private final AtomicInteger calls = new AtomicInteger();

    private VisualizationTabManager manager() {
        VisualizationTabManager mgr = new VisualizationTabManager(new DataSet(),
            ref -> new LineStyle(Color.BLACK, StrokeStyle.values()[0]));
        mgr.setHost(new VizHost() {
            @Override
            public void onSelectionsChanged() {
                calls.incrementAndGet();
            }
        });
        mgr.addPlotTab(FlowVizState.defaults().withVisibleSeries(List.of(A, B)));
        calls.set(0);
        return mgr;
    }

    @Test
    void unTickingIsHeard() {
        VisualizationTabManager mgr = manager();
        mgr.setTargetTabSelectedSeries(Set.of(A));
        assertEquals(1, calls.get());
    }

    @Test
    void aSettingsChangeIsNot() {
        VisualizationTabManager mgr = manager();
        mgr.getTargetVizPanel().model().update(st -> st.withAggregation(AggregationPeriod.MONTHLY, AggregationMethod.MEAN));
        assertEquals(0, calls.get());
    }

    @Test
    void resetAndUndoAreHeard() {
        VisualizationTabManager mgr = manager();
        mgr.resetTabAt(mgr.getTabbedPane().getSelectedIndex());
        assertEquals(1, calls.get(), "reset clears the selection");
        mgr.getTargetVizPanel().undo();
        assertEquals(2, calls.get(), "undo brings it back");
    }

    @Test
    void closingATabIsHeard() {
        VisualizationTabManager mgr = manager();
        IntConsumer close = (IntConsumer) mgr.getTabbedPane().getClientProperty("JTabbedPane.tabCloseCallback");
        close.accept(0);
        assertEquals(1, calls.get());
    }
}
