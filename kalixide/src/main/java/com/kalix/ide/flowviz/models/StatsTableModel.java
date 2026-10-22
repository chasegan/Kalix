package com.kalix.ide.flowviz.models;

import com.kalix.ide.flowviz.SeriesLoadState;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.stats.MaskMode;
import com.kalix.ide.flowviz.stats.StatSample;
import com.kalix.ide.flowviz.stats.Statistic;
import com.kalix.ide.flowviz.stats.StatisticsRegistry;
import com.kalix.ide.flowviz.stats.TimeSeriesMasker;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Table model for displaying statistics of multiple time series.
 * Shows series name and dynamically computed statistics based on StatisticsRegistry.
 * Supports masking modes for bivariate statistics.
 *
 * <p>It holds no settings of its own: {@link #project} replaces the whole table from the
 * tab's aggregates, selection, mask and load states.
 */
public class StatsTableModel extends AbstractTableModel {
    private final List<SeriesStats> seriesData = new ArrayList<>();

    // The mask the current rows were computed under.
    private MaskMode maskMode = MaskMode.ALL;

    // Label resolver — projects SeriesRef → display string for column 0. Set by the
    // owner (typically VisualizationTabManager wiring from RunManager).
    private LabelResolver labelResolver;

    /**
     * Statistics for a single time series with all computed values.
     */
    public static class SeriesStats {
        public final SeriesRef ref;
        public final Map<String, String> statisticValues;  // Statistic name -> computed value

        /**
         * Creates empty/loading stats for a series.
         */
        public SeriesStats(SeriesRef ref) {
            this.ref = ref;
            this.statisticValues = new HashMap<>();
            // Initialize with empty values
            for (Statistic stat : StatisticsRegistry.getAll()) {
                this.statisticValues.put(stat.getName(), "");
            }
        }

        /**
         * Creates stats with computed values.
         */
        public SeriesStats(SeriesRef ref, Map<String, String> statisticValues) {
            this.ref = ref;
            this.statisticValues = new HashMap<>(statisticValues);
        }
    }

    public void setLabelResolver(LabelResolver labelResolver) {
        this.labelResolver = labelResolver;
        fireTableDataChanged();
    }

    private String labelFor(SeriesRef ref) {
        return labelResolver != null ? labelResolver.labelFor(ref) : String.valueOf(ref);
    }

    @Override
    public int getRowCount() {
        return seriesData.size();
    }

    /** Column 0: 1-based row index. Column 1: series label. Columns 2+: statistics. */
    private static final int COLUMN_INDEX = 0;
    private static final int COLUMN_SERIES = 1;
    private static final int FIRST_STAT_COLUMN = 2;

    @Override
    public int getColumnCount() {
        // "#" index + "Series" label + all statistics
        return FIRST_STAT_COLUMN + StatisticsRegistry.getStatisticCount();
    }

    @Override
    public String getColumnName(int column) {
        if (column == COLUMN_INDEX) {
            return "#";
        }
        if (column == COLUMN_SERIES) {
            return "Series";
        }
        int statIndex = column - FIRST_STAT_COLUMN;
        List<Statistic> allStats = StatisticsRegistry.getAll();
        if (statIndex >= 0 && statIndex < allStats.size()) {
            return allStats.get(statIndex).getName();
        }
        return "";
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (rowIndex >= seriesData.size()) return "";

        SeriesStats stats = seriesData.get(rowIndex);

        if (columnIndex == COLUMN_INDEX) {
            // 1-based position — rows are kept in the order the user added them, so this
            // is the "append number". Row 1 is the bivariate reference series.
            return String.valueOf(rowIndex + 1);
        }

        if (columnIndex == COLUMN_SERIES) {
            // Projected display label (recomputed each render so it tracks renames).
            return labelFor(stats.ref);
        }

        // Remaining columns are statistics
        List<Statistic> allStats = StatisticsRegistry.getAll();
        int statIndex = columnIndex - FIRST_STAT_COLUMN;

        if (statIndex >= 0 && statIndex < allStats.size()) {
            Statistic stat = allStats.get(statIndex);
            return stats.statisticValues.getOrDefault(stat.getName(), "");
        }

        return "";
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }

    /**
     * The mask the current rows were computed under.
     */
    public MaskMode getMaskMode() {
        return maskMode;
    }

    /**
     * Replaces the table with one row per selected series, in selection order:
     * <ul>
     *   <li>a series whose fetch failed shows "Error" in every column;</li>
     *   <li>otherwise a series with data shows its statistics, even while a load state
     *       says it is loading: data in the pool is never hidden by a fetch that ended
     *       without clearing its state;</li>
     *   <li>a loading series without data shows a blank placeholder;</li>
     *   <li>a series with neither has no row (say, one restored by undo after its run
     *       was deleted).</li>
     * </ul>
     * The first series with statistics is the reference for the bivariate ones.
     *
     * @param aggregated the selection's aggregated series (already seasonally masked)
     */
    public void project(Map<SeriesRef, TimeSeriesData> aggregated, List<SeriesRef> selection,
                        MaskMode mask, Map<SeriesRef, SeriesLoadState> loadStates) {
        this.maskMode = mask;
        LinkedHashMap<SeriesRef, TimeSeriesData> withData = new LinkedHashMap<>();
        for (SeriesRef ref : selection) {
            TimeSeriesData data = aggregated.get(ref);
            if (data != null && !(loadStates.get(ref) instanceof SeriesLoadState.Failed)) {
                withData.put(ref, data);
            }
        }
        Map<SeriesRef, SeriesStats> computed = computeAll(withData);

        List<SeriesStats> rows = new ArrayList<>();
        for (SeriesRef ref : selection) {
            SeriesLoadState load = loadStates.get(ref);
            if (load instanceof SeriesLoadState.Failed) {
                rows.add(errorRow(ref));
            } else if (computed.containsKey(ref)) {
                rows.add(computed.get(ref));
            } else if (load instanceof SeriesLoadState.Loading) {
                rows.add(new SeriesStats(ref));
            }
        }
        seriesData.clear();
        seriesData.addAll(rows);
        fireTableDataChanged();
    }

    /** A row with "Error" in every statistic column, for a series that failed to load. */
    private static SeriesStats errorRow(SeriesRef ref) {
        Map<String, String> errorValues = new HashMap<>();
        for (Statistic stat : StatisticsRegistry.getAll()) {
            errorValues.put(stat.getName(), "Error");
        }
        return new SeriesStats(ref, errorValues);
    }

    /**
     * Statistics for every series under the current mask mode; the first series is the
     * bivariate reference.
     *
     * <p>In {@link MaskMode#ALL} the mask is identical for every series, so it is built
     * once here and reused — rather than rebuilt inside {@code computeStatistics} per
     * series.</p>
     */
    private Map<SeriesRef, SeriesStats> computeAll(LinkedHashMap<SeriesRef, TimeSeriesData> series) {
        TimeSeriesData referenceData = series.isEmpty() ? null : series.values().iterator().next();

        // In ALL mode both the mask and the masked reference are shared across every
        // series — build each a single time, here, and reuse them for all rows.
        TimeSeriesMasker.Mask allMask = null;
        StatSample sharedReferenceSample = null;
        if (maskMode == MaskMode.ALL) {
            allMask = TimeSeriesMasker.createAllMask(new ArrayList<>(series.values()));
            if (referenceData != null) {
                sharedReferenceSample = new StatSample(allMask.applyToValues(referenceData));
            }
        }

        // The series are already aggregated AND already seasonally masked -
        // TimeSeriesAggregator applies the mask while accumulating, the only point at
        // which calendar months are still distinguishable. Masking again here would
        // filter points stamped at their period's start and empty the table (#235).
        Map<SeriesRef, SeriesStats> computed = new LinkedHashMap<>();
        for (Map.Entry<SeriesRef, TimeSeriesData> entry : series.entrySet()) {
            Map<String, String> values =
                computeStatistics(entry.getValue(), allMask, sharedReferenceSample, referenceData);
            computed.put(entry.getKey(), new SeriesStats(entry.getKey(), values));
        }
        return computed;
    }

    /**
     * Computes all statistics for a series using the current mask mode.
     *
     * @param series      The series to compute statistics for
     * @param allMask     The pre-built {@link MaskMode#ALL} mask; required in ALL mode,
     *                    ignored otherwise (pass {@code null} for EACH/NONE).
     * @param allRefSample The shared reference {@link StatSample} for ALL mode (the
     *                    reference series masked by {@code allMask}); ignored otherwise.
     * @param referenceData The reference series, or {@code null} when there is none;
     *                    used in EACH mode.
     * @return Map of statistic names to computed values
     */
    private Map<String, String> computeStatistics(TimeSeriesData series,
                                                  TimeSeriesMasker.Mask allMask,
                                                  StatSample allRefSample,
                                                  TimeSeriesData referenceData) {
        Map<String, String> values = new HashMap<>();

        // Build the prepared samples for this series and (where relevant) the reference.
        // Masking with the same mask keeps the two samples index-aligned for bivariate
        // statistics. Samples derive sum/mean/min/max/sorted lazily and cache them.
        StatSample seriesSample;
        StatSample referenceSample;

        switch (maskMode) {
            case ALL:
                // Mask and reference sample both built once by computeAll().
                seriesSample = new StatSample(allMask.applyToValues(series));
                referenceSample = allRefSample;
                break;

            case EACH:
                // The mask is specific to this (series, reference) pair.
                if (referenceData != null) {
                    TimeSeriesMasker.Mask eachMask =
                        TimeSeriesMasker.createEachMask(referenceData, series);
                    seriesSample = new StatSample(eachMask.applyToValues(series));
                    referenceSample = new StatSample(eachMask.applyToValues(referenceData));
                } else {
                    seriesSample = new StatSample(series.getValues());
                    referenceSample = null;
                }
                break;

            case NONE:
            default:
                // No masking; bivariate statistics are skipped below, so no reference.
                seriesSample = new StatSample(series.getValues());
                referenceSample = null;
                break;
        }

        // Compute each statistic
        for (Statistic stat : StatisticsRegistry.getAll()) {
            String value;

            // Skip bivariate statistics if mask mode is NONE
            if (stat.isBivariate() && maskMode == MaskMode.NONE) {
                value = "N/A";
            } else {
                value = stat.calculate(seriesSample, referenceSample);
            }

            values.put(stat.getName(), value);
        }

        return values;
    }
}
