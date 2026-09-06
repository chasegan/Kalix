package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.DatasetSource;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.SourceRef;
import com.kalix.ide.flowviz.stats.MaskMode;
import com.kalix.ide.flowviz.style.LineStyle;
import com.kalix.ide.flowviz.style.StrokeStyle;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;

import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the unified-tab semantics stage 2 introduces: every tab owns the full
 * state bundle; stats-view actions (aggregation, source ticks, reset) are
 * undoable through the same history; the hidden stats projection stays lazy.
 */
class UnifiedTabBehaviourTest {

    private static final long DAY_MS = 86_400_000L;
    private static final SeriesRef REF = new DatasetSeries("/t.csv", "one");
    private static final SourceRef SRC = new DatasetSource("/t.csv");

    private static VisualizationTabManager manager(DataSet pool) {
        return new VisualizationTabManager(pool,
            ref -> new LineStyle(Color.BLACK, StrokeStyle.values()[0]));
    }

    private static VisualizationTabManager.TabSettings emptySettings() {
        VisualizationTabManager.TabSettings settings = VisualizationTabManager.TabSettings.getDefaults();
        settings.selectedSeries = new LinkedHashSet<>();
        settings.checkedSources = new LinkedHashSet<>();
        return settings;
    }

    private static JComboBox<?> combo(Container root, String tooltip) {
        for (Component component : root.getComponents()) {
            if (component instanceof JComboBox<?> box && tooltip.equals(box.getToolTipText())) {
                return box;
            }
            if (component instanceof Container container) {
                JComboBox<?> found = combo(container, tooltip);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
    void statsViewUndoButtonWalksSharedHistoryAndResyncsControls() {
        VisualizationTabManager mgr = manager(new DataSet());
        mgr.addStatsTabFromSettings(emptySettings());
        Container tabRoot = (Container) mgr.getTabbedPane().getSelectedComponent();
        JComboBox<?> periodCombo = combo(tabRoot, "Aggregation");
        assertNotNull(periodCombo);

        periodCombo.setSelectedItem(AggregationPeriod.DAILY.getDisplayName());
        PlotPanel panel = mgr.getTargetPlotPanel();
        assertEquals(AggregationPeriod.DAILY, panel.getAggregationPeriod());
        assertTrue(panel.canUndo());

        AbstractButton undo = button(tabRoot, "Undo");
        assertNotNull(undo, "stats toolbars carry undo/redo now that history exists");
        undo.doClick();
        assertEquals(AggregationPeriod.ORIGINAL, panel.getAggregationPeriod());
        assertEquals(AggregationPeriod.ORIGINAL.getDisplayName(), periodCombo.getSelectedItem(),
            "the undo callback resyncs the combos");
    }

    @Test
    void sourceTicksOnStatsViewTabsPushHistory() {
        VisualizationTabManager mgr = manager(new DataSet());
        mgr.addStatsTabFromSettings(emptySettings());
        PlotPanel panel = mgr.getTargetPlotPanel();
        assertFalse(panel.canUndo(), "fresh tab: one-entry history");

        mgr.setTargetTabCheckedSources(Set.of(SRC));
        mgr.pushTargetTabHistory();

        assertTrue(panel.canUndo(), "a source tick on a stats-view tab is undoable");
    }

    @Test
    void statsViewResetIsOneUndoableEntry() {
        VisualizationTabManager mgr = manager(new DataSet());
        mgr.addStatsTabFromSettings(emptySettings());
        Container tabRoot = (Container) mgr.getTabbedPane().getSelectedComponent();
        combo(tabRoot, "Aggregation").setSelectedItem(AggregationPeriod.DAILY.getDisplayName());
        PlotPanel panel = mgr.getTargetPlotPanel();

        mgr.resetTabAt(mgr.getTabbedPane().getSelectedIndex());
        assertEquals(AggregationPeriod.ORIGINAL, panel.getAggregationPeriod());
        assertEquals(MaskMode.ALL, panel.getMaskMode(), "stats-view reset restores the stats default mask");

        button(tabRoot, "Undo").doClick();
        assertEquals(AggregationPeriod.DAILY, panel.getAggregationPeriod(),
            "undo-of-reset restores the pre-reset state");
    }

    @Test
    void hiddenStatsProjectionStaysLazyOnPlotViewTabs() {
        VisualizationTabManager mgr = manager(new DataSet());
        VisualizationTabManager.TabSettings settings = emptySettings();
        settings.selectedSeries = new LinkedHashSet<>(List.of(REF));
        mgr.addPlotTabFromSettings(settings);

        long[] t = {1_577_836_800_000L, 1_577_836_800_000L + DAY_MS};
        mgr.updateSeriesInStatsTabsWithAggregation(REF,
            new com.kalix.ide.flowviz.data.TimeSeriesData(t, new double[] {1, 2}));

        VisualizationTabManager.TabInfo tab = mgr.tabAt(0);
        assertTrue(tab.statsDirty, "the hidden projection is marked, not computed");
        assertEquals(0, tab.statsModel.getRowCount(), "no eager stats work for a hidden page");
    }

    @Test
    void duplicationCarriesHistoryForStatsViewTabs() {
        VisualizationTabManager mgr = manager(new DataSet());
        mgr.addStatsTabFromSettings(emptySettings());
        Container tabRoot = (Container) mgr.getTabbedPane().getSelectedComponent();
        combo(tabRoot, "Aggregation").setSelectedItem(AggregationPeriod.DAILY.getDisplayName());

        VisualizationTabManager.TabSettings duplicated =
            VisualizationTabManager.TabSettings.fromTab(mgr.tabAt(0));
        mgr.addStatsTabFromSettings(duplicated);

        PlotPanel copy = mgr.getTargetPlotPanel(); // the new tab is selected
        assertEquals(AggregationPeriod.DAILY, copy.getAggregationPeriod());
        assertEquals(AggregationMethod.SUM, copy.getAggregationMethod());
        assertTrue(copy.canUndo(), "stats-view duplication is Chrome-style now: history copied");
    }
}
