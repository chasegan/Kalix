package com.kalix.ide.windows;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.models.StatsTableModel;
import com.kalix.ide.flowviz.stats.MaskMode;
import com.kalix.ide.flowviz.style.LineStyle;
import com.kalix.ide.flowviz.style.StrokeStyle;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JTable;
import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The tripwire for the aggregation consolidation: every path that fills a stats
 * table (batch rebuild on tab creation, per-series async update, and the stats
 * toolbar's recompute) must produce identical statistics for identical inputs —
 * plus the duplication mask-carry contract.
 */
class StatsAggregationEquivalenceTest {

    private static final long DAY_MS = 86_400_000L;
    private static final SeriesRef R1 = new DatasetSeries("/t.csv", "one");
    private static final SeriesRef R2 = new DatasetSeries("/t.csv", "two");

    private static TimeSeriesData daily(double... values) {
        long[] timestamps = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            timestamps[i] = 1_577_836_800_000L + i * DAY_MS;
        }
        return new TimeSeriesData(timestamps, values);
    }

    private static DataSet pool() {
        DataSet pool = new DataSet();
        pool.addSeries(R1, daily(1, 2, 3, 4, 5, 6));
        pool.addSeries(R2, daily(2, 4, 6, 8, 10, 12));
        return pool;
    }

    private static VisualizationTabManager manager(DataSet pool) {
        return new VisualizationTabManager(pool,
            ref -> new LineStyle(Color.BLACK, StrokeStyle.values()[0]));
    }

    private static List<List<String>> snapshot(StatsTableModel model) {
        List<List<String>> rows = new ArrayList<>();
        for (int r = 0; r < model.getRowCount(); r++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < model.getColumnCount(); c++) {
                Object value = model.getValueAt(r, c);
                row.add(value != null ? value.toString() : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private static VisualizationTabManager.TabSettings statsSettings() {
        VisualizationTabManager.TabSettings settings = VisualizationTabManager.TabSettings.getDefaults();
        settings.selectedSeries = new LinkedHashSet<>(List.of(R1, R2));
        settings.checkedSources = new LinkedHashSet<>();
        settings.aggregationPeriod = AggregationPeriod.DAILY;
        settings.aggregationMethod = AggregationMethod.MEAN;
        return settings;
    }

    @Test
    void allThreeStatsFillPathsProduceIdenticalTables() {
        // Path A: batch rebuild at tab creation (pool already populated).
        StatsTableModel batch = manager(pool()).addStatsTabFromSettings(statsSettings());
        List<List<String>> expected = snapshot(batch);
        assertFalse(expected.isEmpty(), "fixture sanity: statistics were computed");

        // Path B: per-series async updates into an initially empty pool.
        VisualizationTabManager async = manager(new DataSet());
        async.addStatsTabFromSettings(statsSettings());
        async.updateSeriesInStatsTabsWithAggregation(R1, daily(1, 2, 3, 4, 5, 6));
        async.updateSeriesInStatsTabsWithAggregation(R2, daily(2, 4, 6, 8, 10, 12));
        assertEquals(expected, snapshot(async.getAllStatsModels().get(0)),
            "async per-series path must match the batch path");

        // Path C: the stats toolbar's recompute, driven through the real combos.
        StatsTableModel toolbarModel = new StatsTableModel();
        VisualizationTabManager.TabInfo tabInfo = new VisualizationTabManager.TabInfo(
            VisualizationTabManager.TabInfo.TabType.STATS, "", new JPanel(), null, toolbarModel);
        tabInfo.selectedSeries.addAll(List.of(R1, R2));
        tabInfo.statsPeriod = AggregationPeriod.ORIGINAL; // driven to DAILY via the combo below
        tabInfo.statsMethod = AggregationMethod.SUM;
        StatsToolbarBuilder builder = new StatsToolbarBuilder(tabInfo, new JTable(toolbarModel), pool());
        builder.addAggregationControls();
        builder.build();
        // Real listener firings, exactly as a user changing the dropdowns.
        driveCombo(builder, AggregationPeriod.DAILY.getDisplayName(),
            AggregationMethod.MEAN.getDisplayName());
        assertEquals(AggregationPeriod.DAILY, tabInfo.statsPeriod);
        assertEquals(expected, snapshot(toolbarModel),
            "toolbar recompute must match the batch path");
    }

    private static void driveCombo(StatsToolbarBuilder builder, String period, String method) {
        javax.swing.JToolBar toolbar = builder.build();
        for (java.awt.Component component : toolbar.getComponents()) {
            if (component instanceof javax.swing.JComboBox<?> combo) {
                if ("Aggregation".equals(combo.getToolTipText())) {
                    combo.setSelectedItem(period);
                } else if ("Aggregation method".equals(combo.getToolTipText())) {
                    combo.setSelectedItem(method);
                }
            }
        }
    }

    @Test
    void statsTabDuplicationCarriesTheMaskMode() {
        StatsTableModel sourceModel = new StatsTableModel();
        sourceModel.setMaskMode(MaskMode.NONE); // away from the ALL default
        VisualizationTabManager.TabInfo source = new VisualizationTabManager.TabInfo(
            VisualizationTabManager.TabInfo.TabType.STATS, "", new JPanel(), null, sourceModel);

        VisualizationTabManager.TabSettings settings =
            VisualizationTabManager.TabSettings.fromStatsTab(source);
        assertEquals(MaskMode.NONE, settings.maskMode, "settings capture the live mask");

        settings.selectedSeries = new LinkedHashSet<>();
        settings.checkedSources = new LinkedHashSet<>();
        VisualizationTabManager mgr = manager(new DataSet());
        StatsTableModel duplicate = mgr.addStatsTabFromSettings(settings);
        assertEquals(MaskMode.NONE, duplicate.getMaskMode(),
            "duplication no longer silently resets the mask to ALL");
    }
}
