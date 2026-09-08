package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.style.LineStyle;
import com.kalix.ide.flowviz.style.StrokeStyle;

import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the view toggle's contract: pure presentation — no history entry (Ctrl+Z
 * must never flip the page), no tree reprojection — with the newly shown page
 * catching up (a dirty stats projection recomputes on toggle), and duplication
 * carrying the active view so a duplicate looks identical.
 */
class ViewToggleTest {

    private static final long DAY_MS = 86_400_000L;
    private static final SeriesRef REF = new DatasetSeries("/t.csv", "one");

    private static TimeSeriesData daily(double... values) {
        long[] timestamps = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            timestamps[i] = 1_577_836_800_000L + i * DAY_MS;
        }
        return new TimeSeriesData(timestamps, values);
    }

    private static VisualizationTabManager manager(DataSet pool) {
        return new VisualizationTabManager(pool,
            ref -> new LineStyle(Color.BLACK, StrokeStyle.values()[0]));
    }

    private static VisualizationTabManager.TabSettings settingsWith(SeriesRef... series) {
        VisualizationTabManager.TabSettings settings = VisualizationTabManager.TabSettings.getDefaults();
        settings.selectedSeries = new LinkedHashSet<>(List.of(series));
        settings.checkedSources = new LinkedHashSet<>();
        return settings;
    }

    private static AbstractButton button(Container root, String tooltip) {
        for (Component component : root.getComponents()) {
            if (component instanceof AbstractButton b && tooltip.equals(b.getToolTipText())) {
                return b;
            }
            if (component instanceof Container container) {
                AbstractButton found = button(container, tooltip);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void toggleIsPresentationOnlyNoHistoryNoTreeNoise() {
        VisualizationTabManager mgr = manager(new DataSet());
        mgr.addPlotTabFromSettings(settingsWith());
        AtomicInteger treeNotifications = new AtomicInteger();
        mgr.setHost(countingHost(treeNotifications));

        AbstractButton statsToggle = button(
            (Container) mgr.getTabbedPane().getSelectedComponent(), "Stats view");
        assertNotNull(statsToggle);
        statsToggle.doClick();

        assertEquals(FlowVizView.STATS, mgr.tabAt(0).viewMode);
        assertFalse(mgr.getTargetVizPanel().canUndo(), "a toggle must never be an undo entry");
        assertEquals(0, treeNotifications.get(), "the selection is unchanged: no tree reprojection");
    }

    @Test
    void toggleToStatsRecomputesTheDirtyProjection() {
        DataSet pool = new DataSet();
        pool.addSeries(REF, daily(1, 2, 3));
        VisualizationTabManager mgr = manager(pool);
        mgr.addPlotTabFromSettings(settingsWith(REF));

        VisualizationTabManager.TabInfo tab = mgr.tabAt(0);
        assertTrue(tab.statsDirty, "a plot-view tab's stats page starts lazy");
        assertEquals(0, tab.statsModel.getRowCount());

        button((Container) mgr.getTabbedPane().getSelectedComponent(), "Stats view").doClick();

        assertFalse(tab.statsDirty, "the toggle honoured the dirty mark");
        assertEquals(1, tab.statsModel.getRowCount(), "the shown stats page is populated");
    }

    @Test
    void toggleRoundTripReturnsToThePlotView() {
        VisualizationTabManager mgr = manager(new DataSet());
        mgr.addPlotTabFromSettings(settingsWith());
        Container tabRoot = (Container) mgr.getTabbedPane().getSelectedComponent();

        button(tabRoot, "Stats view").doClick();
        button(tabRoot, "Plot view").doClick();

        assertEquals(FlowVizView.PLOT, mgr.tabAt(0).viewMode);
        assertFalse(mgr.getTargetVizPanel().canUndo(), "round-trip left no history behind");
    }

    @Test
    void duplicationCarriesTheActiveView() {
        VisualizationTabManager mgr = manager(new DataSet());
        mgr.addPlotTabFromSettings(settingsWith());
        button((Container) mgr.getTabbedPane().getSelectedComponent(), "Stats view").doClick();

        VisualizationTabManager.TabSettings duplicated =
            VisualizationTabManager.TabSettings.fromTab(mgr.tabAt(0));
        assertEquals(FlowVizView.STATS, duplicated.activeView);

        mgr.addTabFromSettings(duplicated);
        assertEquals(FlowVizView.STATS, mgr.tabAt(1).viewMode,
            "a duplicate opens on the same page as its source");
    }

    /** A host that only counts active-tab-change notifications. */
    private static VizHost countingHost(java.util.concurrent.atomic.AtomicInteger counter) {
        return new VizHost() {
            @Override
            public void onActiveTabChanged() {
                counter.incrementAndGet();
            }
        };
    }
}
