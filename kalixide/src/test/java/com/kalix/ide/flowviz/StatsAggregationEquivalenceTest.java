package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.models.StatsTableModel;
import com.kalix.ide.flowviz.stats.MaskMode;
import com.kalix.ide.flowviz.stats.SeasonalMaskMode;
import com.kalix.ide.flowviz.style.LineStyle;
import com.kalix.ide.flowviz.style.StrokeStyle;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;

import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.time.ZoneOffset;
import java.time.Month;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tripwire for the aggregation consolidation: every path that fills a stats
 * table (batch rebuild at tab creation, per-series async update, and the stats
 * toolbar's recompute driven through the real combos) must produce identical
 * statistics for identical inputs — plus the duplication mask-carry contract.
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

    private static VisualizationTabManager.TabSettings statsSettings(
            AggregationPeriod period, AggregationMethod method) {
        VisualizationTabManager.TabSettings settings = VisualizationTabManager.TabSettings.getDefaults();
        settings.selectedSeries = new LinkedHashSet<>(List.of(R1, R2));
        settings.checkedSources = new LinkedHashSet<>();
        settings.aggregationPeriod = period;
        settings.aggregationMethod = method;
        return settings;
    }

    /** Finds a combo by tooltip anywhere in the active tab's component tree. */
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

    @Test
    void allThreeStatsFillPathsProduceIdenticalTables() {
        // Path A: batch rebuild at tab creation (pool already populated).
        StatsTableModel batch = manager(pool())
            .addStatsTabFromSettings(statsSettings(AggregationPeriod.DAILY, AggregationMethod.MEAN));
        List<List<String>> expected = snapshot(batch);
        assertFalse(expected.isEmpty(), "fixture sanity: statistics were computed");

        // Path B: per-series async updates into a visible stats view.
        VisualizationTabManager async = manager(new DataSet());
        async.addStatsTabFromSettings(statsSettings(AggregationPeriod.DAILY, AggregationMethod.MEAN));
        async.updateSeriesInStatsTabsWithAggregation(R1, daily(1, 2, 3, 4, 5, 6));
        async.updateSeriesInStatsTabsWithAggregation(R2, daily(2, 4, 6, 8, 10, 12));
        assertEquals(expected, snapshot(async.getAllStatsModels().get(0)),
            "async per-series path must match the batch path");

        // Path C: the stats toolbar's recompute, driven through the real combos
        // (which now drive the state-owning panel — undoably).
        VisualizationTabManager toolbar = manager(pool());
        StatsTableModel toolbarModel = toolbar
            .addStatsTabFromSettings(statsSettings(AggregationPeriod.ORIGINAL, AggregationMethod.SUM));
        Container tabRoot = (Container) toolbar.getTabbedPane().getSelectedComponent();
        JComboBox<?> periodCombo = combo(tabRoot, "Aggregation");
        JComboBox<?> methodCombo = combo(tabRoot, "Aggregation method");
        assertNotNull(periodCombo);
        assertNotNull(methodCombo);
        periodCombo.setSelectedItem(AggregationPeriod.DAILY.getDisplayName());
        methodCombo.setSelectedItem(AggregationMethod.MEAN.getDisplayName());

        assertEquals(AggregationPeriod.DAILY, toolbar.getTargetVizPanel().getAggregationPeriod(),
            "the combo drives the state-owning panel");
        assertTrue(toolbar.getTargetVizPanel().canUndo(),
            "stats aggregation changes are undoable now");
        assertEquals(expected, snapshot(toolbarModel),
            "toolbar recompute must match the batch path");
    }

    @Test
    void statsTabCreationCarriesTheMaskModeAndDefaultsToAll() {
        // Default: a stats-view tab starts at the historical ALL default.
        VisualizationTabManager defaults = manager(new DataSet());
        StatsTableModel model = defaults
            .addStatsTabFromSettings(statsSettings(AggregationPeriod.ORIGINAL, AggregationMethod.SUM));
        assertEquals(MaskMode.ALL, model.getMaskMode());
        assertEquals(MaskMode.ALL, defaults.getTargetVizPanel().getMaskMode(),
            "the panel owns the mask; the model is its projection");

        // Carried: duplication-style settings preserve a non-default mask.
        VisualizationTabManager carried = manager(new DataSet());
        VisualizationTabManager.TabSettings settings =
            statsSettings(AggregationPeriod.ORIGINAL, AggregationMethod.SUM);
        settings.maskMode = MaskMode.NONE;
        StatsTableModel duplicate = carried.addStatsTabFromSettings(settings);
        assertEquals(MaskMode.NONE, duplicate.getMaskMode(),
            "duplication no longer silently resets the mask to ALL");
    }
    /** Daily points valued 1 covering all of 2020 - a full Jan-Dec year. */
    private static TimeSeriesData wholeOf2020() {
        LocalDate start = LocalDate.of(2020, 1, 1);
        int days = (int) (LocalDate.of(2021, 1, 1).toEpochDay() - start.toEpochDay());
        long[] timestamps = new long[days];
        double[] values = new double[days];
        for (int i = 0; i < days; i++) {
            timestamps[i] = start.plusDays(i).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            values[i] = 1;
        }
        return new TimeSeriesData(timestamps, values);
    }

    private static double stat(StatsTableModel model, String name) {
        for (int column = 0; column < model.getColumnCount(); column++) {
            if (name.equalsIgnoreCase(model.getColumnName(column))) {
                return Double.parseDouble(String.valueOf(model.getValueAt(0, column)));
            }
        }
        throw new AssertionError("no such statistic column: " + name);
    }

    private static VisualizationTabManager.TabSettings maskedSettings(SeasonalMaskMode mask) {
        VisualizationTabManager.TabSettings settings =
            statsSettings(AggregationPeriod.ANNUAL_JAN_DEC, AggregationMethod.SUM);
        settings.selectedSeries = new LinkedHashSet<>(List.of(R1));
        settings.seasonalMaskMode = mask;
        return settings;
    }

    private static DataSet yearPool() {
        DataSet pool = new DataSet();
        pool.addSeries(R1, wholeOf2020());
        return pool;
    }

    /**
     * The reported bug (#235): Annual (Jan-Dec) with January deselected produced an
     * EMPTY stats table, because the model masked points that aggregation had already
     * stamped at the period start.
     */
    @Test
    void seasonalMaskReachesTheStatsTableWithoutEmptyingIt() {
        VisualizationTabManager mgr = manager(yearPool());
        StatsTableModel model = mgr.addStatsTabFromSettings(
            maskedSettings(SeasonalMaskMode.of(Set.of(Month.FEBRUARY))));

        assertEquals(1, model.getRowCount(),
            "deselecting the period's start month must not empty the stats table");
        assertEquals(1.0, stat(model, "Points"), 1e-9, "one annual point for 2020");
        assertEquals(29.0, stat(model, "Mean"), 1e-9,
            "that point is February's 29 days - not the whole year, and not nothing");
    }

    @Test
    void statsAndPlotAgreeUnderASeasonalMask() {
        VisualizationTabManager mgr = manager(yearPool());
        StatsTableModel model = mgr.addStatsTabFromSettings(
            maskedSettings(SeasonalMaskMode.of(Set.of(Month.JANUARY))));

        FlowVizPanel panel = mgr.getTargetVizPanel();
        panel.setVisibleSeries(List.of(R1));
        double plotted = panel.displayDataSetForTests().getSeries(R1).getValues()[0];

        assertEquals(31.0, plotted, 1e-9, "the plot aggregates January's 31 days");
        assertEquals(plotted, stat(model, "Mean"), 1e-9,
            "both views mask through the same pipeline, so they cannot disagree");
    }

    @Test
    void seasonalMaskIsHonouredAtNativeResolution() {
        VisualizationTabManager.TabSettings settings =
            maskedSettings(SeasonalMaskMode.of(Set.of(Month.FEBRUARY)));
        settings.aggregationPeriod = AggregationPeriod.ORIGINAL;
        StatsTableModel model = manager(yearPool()).addStatsTabFromSettings(settings);

        assertEquals(29.0, stat(model, "Points"), 1e-9,
            "ORIGINAL aggregates nothing, but the mask must still filter the points");
    }

    @Test
    void statsTabCreationCarriesTheSeasonalMask() {
        VisualizationTabManager defaults = manager(new DataSet());
        defaults.addStatsTabFromSettings(statsSettings(AggregationPeriod.ORIGINAL, AggregationMethod.SUM));
        assertEquals(SeasonalMaskMode.DISABLED, defaults.getTargetVizPanel().getSeasonalMaskMode(),
            "a fresh tab starts unmasked");

        SeasonalMaskMode carried = SeasonalMaskMode.of(Set.of(Month.JUNE, Month.JULY));
        VisualizationTabManager duplicate = manager(new DataSet());
        duplicate.addStatsTabFromSettings(maskedSettings(carried));
        assertEquals(carried, duplicate.getTargetVizPanel().getSeasonalMaskMode(),
            "duplication carries the seasonal selection, as it does the overlap mask");
    }
}
