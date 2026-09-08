package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.style.LineStyle;
import com.kalix.ide.flowviz.style.StrokeStyle;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the default tab-naming contract: session-monotonic "View n" defaults
 * (numbers never reused, tabs never renumbered), duplicates of default-named
 * tabs getting fresh numbers, and user names surviving duplication.
 */
class TabNamingTest {

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
    void defaultNamesIncrementMonotonically() {
        VisualizationTabManager mgr = manager();
        mgr.addPlotTabFromSettings(emptySettings());
        mgr.addStatsTabFromSettings(emptySettings());
        assertEquals("View 1", mgr.tabAt(0).name);
        assertEquals("View 2", mgr.tabAt(1).name);
    }

    @Test
    void duplicatingADefaultNamedTabMintsAFreshNumber() {
        VisualizationTabManager mgr = manager();
        mgr.addPlotTabFromSettings(emptySettings()); // View 1
        mgr.addTabFromSettings(VisualizationTabManager.TabSettings.fromTab(mgr.tabAt(0)));
        assertEquals("View 2", mgr.tabAt(1).name, "two 'View 1's help nobody");
    }

    @Test
    void duplicatingAUserNamedTabKeepsTheName() {
        VisualizationTabManager mgr = manager();
        mgr.addPlotTabFromSettings(emptySettings());
        mgr.tabAt(0).rename("Calibration");
        mgr.addTabFromSettings(VisualizationTabManager.TabSettings.fromTab(mgr.tabAt(0)));
        assertEquals("Calibration", mgr.tabAt(1).name, "a chosen name is the user's to keep");
    }
}
