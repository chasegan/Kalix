package com.kalix.ide.windows;

import com.kalix.ide.flowviz.PlotState;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.DatasetSource;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.SourceRef;
import com.kalix.ide.flowviz.style.LineStyle;
import com.kalix.ide.flowviz.style.StrokeStyle;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the in-place-mutation contract: any change to a tab\u2019s canonical record
 * (selectedSeries / checkedSources) must reproject the window trees via the
 * tab-changed callback IFF that tab is active \u2014 and must stay silent (and leave
 * focus alone) for background tabs, whose state is projected on activation.
 */
class VisualizationTabManagerResetTest {

    private static final SeriesRef REF = new DatasetSeries("/test/a.csv", "a");
    private static final SourceRef SRC = new DatasetSource("/test/a.csv");

    private static VisualizationTabManager newManager() {
        return new VisualizationTabManager(new DataSet(),
            ref -> new LineStyle(Color.BLACK, StrokeStyle.values()[0]));
    }

    private static VisualizationTabManager.TabSettings emptySettings() {
        VisualizationTabManager.TabSettings s = VisualizationTabManager.TabSettings.getDefaults();
        s.selectedSeries = new LinkedHashSet<>();
        s.checkedSources = new LinkedHashSet<>();
        return s;
    }

    @Test
    void activeTabResetNotifiesOnceAndClearsCanonicalRecord() {
        VisualizationTabManager mgr = newManager();
        mgr.addPlotTabFromSettings(emptySettings());
        mgr.setTargetTabSelectedSeries(Set.of(REF));
        mgr.setTargetTabCheckedSources(Set.of(SRC));

        AtomicInteger notifications = new AtomicInteger();
        mgr.setOnTabChangedCallback(notifications::incrementAndGet);

        mgr.resetTabAt(mgr.getTabbedPane().getSelectedIndex());

        assertEquals(1, notifications.get(),
            "an active-tab reset must reproject the trees exactly once");
        assertTrue(mgr.getTargetTabSelectedSeries().isEmpty(), "series record cleared");
        assertTrue(mgr.getTargetTabCheckedSources().isEmpty(), "sources record cleared");
    }

    @Test
    void backgroundTabResetIsSilentAndFocusPreserving() {
        VisualizationTabManager mgr = newManager();
        mgr.addPlotTabFromSettings(emptySettings());          // tab 0
        mgr.addPlotTabFromSettings(emptySettings());          // tab 1, becomes active
        mgr.setTargetTabSelectedSeries(Set.of(REF));          // lands on tab 1
        mgr.getTabbedPane().setSelectedIndex(0);              // tab 0 active

        AtomicInteger notifications = new AtomicInteger();
        mgr.setOnTabChangedCallback(notifications::incrementAndGet);

        mgr.resetTabAt(1);                                    // background reset

        assertEquals(0, notifications.get(),
            "a background reset must not touch the trees (they mirror the active tab)");
        assertEquals(0, mgr.getTabbedPane().getSelectedIndex(),
            "a background reset must not steal focus (the old remove-recreate reset did)");

        mgr.getTabbedPane().setSelectedIndex(1);              // activate the reset tab
        assertEquals(1, notifications.get(), "activation projects the reset tab");
        assertTrue(mgr.getTargetTabSelectedSeries().isEmpty(),
            "the reset tab restores its empty context on activation");
    }

    @Test
    void undoSyncRoutesThroughTheSameSeam() {
        VisualizationTabManager mgr = newManager();
        mgr.addPlotTabFromSettings(emptySettings());
        mgr.setTargetTabSelectedSeries(Set.of(REF)); // pushes an undoable selection state

        AtomicInteger notifications = new AtomicInteger();
        mgr.setOnTabChangedCallback(notifications::incrementAndGet);

        PlotState undone = mgr.getTargetPlotPanel().undo();
        mgr.syncTabSelectionFromPlotState(mgr.getTargetPlotPanel(), undone);

        assertEquals(1, notifications.get(), "undo sync must reproject the trees once");
        assertEquals(undone.getVisibleSeries(), new java.util.ArrayList<>(mgr.getTargetTabSelectedSeries()),
            "the canonical record matches the restored state");
    }

    @Test
    void constructionSnapshotCarriesTrueSources() {
        VisualizationTabManager mgr = newManager();
        VisualizationTabManager.TabSettings settings = emptySettings();
        settings.checkedSources = new LinkedHashSet<>(Set.of(SRC));
        mgr.addPlotTabFromSettings(settings);

        assertEquals(Set.of(SRC), mgr.getTargetPlotPanel().currentState().getCheckedSources(),
            "history entry #1 must carry the sources the tab was born with");
    }

    @Test
    void sourceChangesAreUndoableAndRestoreThroughTheSeam() {
        VisualizationTabManager mgr = newManager();
        VisualizationTabManager.TabSettings settings = emptySettings();
        settings.checkedSources = new LinkedHashSet<>(Set.of(SRC));
        mgr.addPlotTabFromSettings(settings);           // entry 1: {SRC}

        mgr.setTargetTabCheckedSources(Set.of());       // source-only change...
        mgr.pushTargetTabHistory();                      // ...is its own undo entry
        assertTrue(mgr.getTargetPlotPanel().canUndo(), "source-only change pushed an entry");

        mgr.pushTargetTabHistory();                      // unchanged state dedupes
        PlotState undone = mgr.getTargetPlotPanel().undo();
        mgr.syncTabSelectionFromPlotState(mgr.getTargetPlotPanel(), undone);

        assertEquals(Set.of(SRC), mgr.getTargetTabCheckedSources(),
            "undo restores the source context into the canonical record");
        assertTrue(!mgr.getTargetPlotPanel().canUndo(),
            "the duplicate push deduped: exactly one source-change entry existed");
    }
}
