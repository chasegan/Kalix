package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.stats.SeasonalMaskMode;
import com.kalix.ide.flowviz.style.LineStyle;
import com.kalix.ide.flowviz.style.StrokeStyle;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.time.Month;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seasonal mask is part of the tab's undoable state, not a view filter sitting
 * beside it: it rides in {@link FlowVizState}, so Ctrl+Z walks back through month
 * selections, and re-picking the selection already in force must not manufacture a
 * history entry for undo to stumble over.
 */
class SeasonalMaskStateTest {

    private static final long DAY_MS = 86_400_000L;
    private static final SeriesRef REF = new DatasetSeries("/t.csv", "flow");

    private static final SeasonalMaskMode SUMMER = SeasonalMaskMode.of(Set.of(Month.JANUARY, Month.FEBRUARY));
    private static final SeasonalMaskMode WINTER = SeasonalMaskMode.of(Set.of(Month.JULY));

    private static FlowVizPanel panelWithData() {
        long[] timestamps = new long[400];
        double[] values = new double[400];
        for (int i = 0; i < timestamps.length; i++) {
            timestamps[i] = 1_577_836_800_000L + i * DAY_MS;
            values[i] = 1;
        }
        DataSet pool = new DataSet();
        pool.addSeries(REF, new TimeSeriesData(timestamps, values));

        FlowVizPanel panel = new FlowVizPanel();
        panel.setDataSet(pool);
        panel.setVisibleSeries(List.of(REF));
        return panel;
    }

    private static VisualizationTabManager manager() {
        return new VisualizationTabManager(new DataSet(),
            ref -> new LineStyle(Color.BLACK, StrokeStyle.values()[0]));
    }

    private static VisualizationTabManager.TabSettings emptySettings() {
        VisualizationTabManager.TabSettings settings = VisualizationTabManager.TabSettings.getDefaults();
        settings.selectedSeries = new LinkedHashSet<>();
        settings.checkedSources = new LinkedHashSet<>();
        return settings;
    }

    @Test
    void changingTheSelectionIsUndoableAndRedoable() {
        FlowVizPanel panel = panelWithData();
        panel.setSeasonalMaskMode(SUMMER);
        assertTrue(panel.canUndo(), "a month selection is a state change, so it is undoable");

        FlowVizState undone = panel.undo();
        assertNotNull(undone);
        assertEquals(SeasonalMaskMode.DISABLED, panel.getSeasonalMaskMode(),
            "undo takes the mask back off");

        panel.redo();
        assertEquals(SUMMER, panel.getSeasonalMaskMode(), "redo puts the same months back");
    }

    @Test
    void undoWalksBackThroughSuccessiveSelections() {
        FlowVizPanel panel = panelWithData();
        panel.setSeasonalMaskMode(SUMMER);
        panel.setSeasonalMaskMode(WINTER);

        panel.undo();
        assertEquals(SUMMER, panel.getSeasonalMaskMode(), "one step back is the previous selection");
        panel.undo();
        assertEquals(SeasonalMaskMode.DISABLED, panel.getSeasonalMaskMode());
    }

    @Test
    void reselectingTheSameMonthsPushesNoHistory() {
        FlowVizPanel panel = panelWithData();
        panel.setSeasonalMaskMode(SUMMER);
        panel.undo();
        panel.redo();
        assertFalse(panel.canRedo(), "fixture sanity: history is at its end");

        // An equal-but-distinct instance: the guard must compare by value, which is why
        // the mode is a record over an EnumSet rather than an identity-based holder.
        panel.setSeasonalMaskMode(SeasonalMaskMode.of(Set.of(Month.FEBRUARY, Month.JANUARY)));

        assertFalse(panel.canRedo(), "no entry was pushed");
        panel.undo();
        assertEquals(SeasonalMaskMode.DISABLED, panel.getSeasonalMaskMode(),
            "one undo still reaches the unmasked state - no duplicate entry in between");
    }

    @Test
    void theMaskIsCarriedInTheCapturedState() {
        FlowVizPanel panel = panelWithData();
        panel.setSeasonalMaskMode(WINTER);
        assertEquals(WINTER, panel.currentState().getSeasonalMaskMode(),
            "the snapshot carries the mask, which is what makes duplication and undo work");
    }

    @Test
    void resetClearsTheSeasonalMask() {
        VisualizationTabManager mgr = manager();
        mgr.addPlotTabFromSettings(emptySettings());
        mgr.getTargetVizPanel().setSeasonalMaskMode(SUMMER);

        mgr.resetTabAt(mgr.getTabbedPane().getSelectedIndex());

        assertEquals(SeasonalMaskMode.DISABLED, mgr.getTargetVizPanel().getSeasonalMaskMode(),
            "reset returns the tab to a fresh tab's settings, mask included");
    }

    @Test
    void undoOfResetBringsTheSeasonalMaskBack() {
        VisualizationTabManager mgr = manager();
        mgr.addPlotTabFromSettings(emptySettings());
        FlowVizPanel panel = mgr.getTargetVizPanel();
        panel.setSeasonalMaskMode(SUMMER);

        mgr.resetTabAt(mgr.getTabbedPane().getSelectedIndex());
        FlowVizState undone = panel.undo();
        mgr.syncTabSelectionFromState(panel, undone);

        assertEquals(SUMMER, panel.getSeasonalMaskMode(),
            "a reset is one undoable step, and the mask comes back with the rest of the view");
    }
}
