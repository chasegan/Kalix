package com.kalix.ide.flowviz.models;

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
 */
public class StatsTableModel extends AbstractTableModel {
    private final List<SeriesStats> seriesData = new ArrayList<>();

    // Masking configuration
    private MaskMode maskMode = MaskMode.ALL;
    private SeriesRef referenceSeries = null;  // First series, used for bivariate stats

    // Cache of original (unmasked) series data, keyed by ref. LinkedHashMap preserves
    // insertion order so the table rows stay in the order the user added them.
    private final Map<SeriesRef, TimeSeriesData> originalSeriesCache = new LinkedHashMap<>();

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
     * Sets the mask mode and recomputes all statistics.
     *
     * @param mode The mask mode to apply
     */
    public void setMaskMode(MaskMode mode) {
        this.maskMode = mode;
        recomputeAllStatistics();
    }

    /**
     * Gets the current mask mode.
     */
    public MaskMode getMaskMode() {
        return maskMode;
    }

    /**
     * Adds a loading placeholder for a series being fetched.
     */
    public void addLoadingSeries(SeriesRef ref) {
        // Remove existing entry if present
        seriesData.removeIf(stats -> stats.ref.equals(ref));
        seriesData.add(new SeriesStats(ref));
        fireTableDataChanged();
    }

    /**
     * Adds or updates a series's stats. Identity comes from the {@link SeriesRef}; the
     * {@link TimeSeriesData}'s legacy name field is ignored.
     */
    public void addOrUpdateSeries(SeriesRef ref, TimeSeriesData data) {
        // Cache original data keyed by ref
        originalSeriesCache.put(ref, data);

        // Update reference series if this is the first series
        if (referenceSeries == null) {
            referenceSeries = ref;
        }

        // If mask mode is ALL, adding a new series changes the mask for all existing series
        // So we need to recompute everything
        if (maskMode == MaskMode.ALL) {
            recomputeAllStatistics();
        } else {
            // For EACH or NONE modes, only this series needs updating (no shared ALL mask
            // or shared reference sample — EACH masks per series, NONE skips bivariate).
            Map<String, String> statisticValues = computeStatistics(data, null, null);

            // Remove existing entry if present
            seriesData.removeIf(stats -> stats.ref.equals(ref));
            seriesData.add(new SeriesStats(ref, statisticValues));
            fireTableDataChanged();
        }
    }

    /**
     * Replaces the entire table contents with the given series and recomputes statistics
     * <em>once</em>. Use this instead of {@link #clear()} followed by repeated
     * {@link #addOrUpdateSeries} when all series are known up front — the per-add path
     * triggers a full recompute on every call, which is O(series²) for a batch.
     *
     * <p>Insertion order of {@code series} becomes the table row order; the first entry
     * becomes the reference series for bivariate statistics.</p>
     */
    public void setSeries(LinkedHashMap<SeriesRef, TimeSeriesData> series) {
        originalSeriesCache.clear();
        originalSeriesCache.putAll(series);
        referenceSeries = series.isEmpty() ? null : series.keySet().iterator().next();
        recomputeAllStatistics();
    }

    /**
     * Adds an error entry for a series that failed to load.
     */
    public void addErrorSeries(SeriesRef ref, String errorMessage) {
        // Remove existing entry if present
        seriesData.removeIf(stats -> stats.ref.equals(ref));

        // Add error stats with "Error" in all columns
        Map<String, String> errorValues = new HashMap<>();
        for (Statistic stat : StatisticsRegistry.getAll()) {
            errorValues.put(stat.getName(), "Error");
        }
        seriesData.add(new SeriesStats(ref, errorValues));
        fireTableDataChanged();
    }

    /**
     * Removes a series from the table.
     */
    public void removeSeries(SeriesRef ref) {
        seriesData.removeIf(stats -> stats.ref.equals(ref));
        originalSeriesCache.remove(ref);

        // Update reference if we removed it
        if (ref.equals(referenceSeries)) {
            referenceSeries = seriesData.isEmpty() ? null : seriesData.get(0).ref;
        }

        // If mask mode is ALL, removing a series changes the mask for all remaining series
        if (maskMode == MaskMode.ALL && !seriesData.isEmpty()) {
            recomputeAllStatistics();
        } else {
            fireTableDataChanged();
        }
    }

    /**
     * Clears all series from the table.
     */
    public void clear() {
        seriesData.clear();
        originalSeriesCache.clear();
        referenceSeries = null;
        fireTableDataChanged();
    }

    /**
     * Recomputes all statistics with the current mask mode.
     * Called when mask mode changes or when series are added/removed in ALL mask mode.
     *
     * <p>In {@link MaskMode#ALL} the mask is identical for every series, so it is built
     * once here and reused — rather than rebuilt inside {@code computeStatistics} per
     * series.</p>
     */
    private void recomputeAllStatistics() {
        // In ALL mode both the mask and the masked reference are shared across every
        // series — build each a single time, here, and reuse them for all rows.
        TimeSeriesMasker.Mask allMask = null;
        StatSample sharedReferenceSample = null;
        if (maskMode == MaskMode.ALL) {
            allMask = TimeSeriesMasker.createAllMask(new ArrayList<>(originalSeriesCache.values()));
            TimeSeriesData referenceData = referenceSeries != null
                ? originalSeriesCache.get(referenceSeries) : null;
            if (referenceData != null) {
                sharedReferenceSample = new StatSample(allMask.applyToValues(referenceData));
            }
        }

        // Rebuild seriesData from scratch based on originalSeriesCache
        List<SeriesStats> newSeriesData = new ArrayList<>();

        for (Map.Entry<SeriesRef, TimeSeriesData> entry : originalSeriesCache.entrySet()) {
            TimeSeriesData originalData = entry.getValue();
            if (originalData != null) {
                Map<String, String> newValues =
                    computeStatistics(originalData, allMask, sharedReferenceSample);
                newSeriesData.add(new SeriesStats(entry.getKey(), newValues));
            }
        }

        seriesData.clear();
        seriesData.addAll(newSeriesData);
        fireTableDataChanged();
    }

    /**
     * Computes all statistics for a series using the current mask mode.
     *
     * @param series      The series to compute statistics for
     * @param allMask     The pre-built {@link MaskMode#ALL} mask; required in ALL mode,
     *                    ignored otherwise (pass {@code null} for EACH/NONE).
     * @param allRefSample The shared reference {@link StatSample} for ALL mode (the
     *                    reference series masked by {@code allMask}); ignored otherwise.
     * @return Map of statistic names to computed values
     */
    private Map<String, String> computeStatistics(TimeSeriesData series,
                                                  TimeSeriesMasker.Mask allMask,
                                                  StatSample allRefSample) {
        Map<String, String> values = new HashMap<>();

        TimeSeriesData referenceData = referenceSeries != null
            ? originalSeriesCache.get(referenceSeries) : null;

        // Build the prepared samples for this series and (where relevant) the reference.
        // Masking with the same mask keeps the two samples index-aligned for bivariate
        // statistics. Samples derive sum/mean/min/max/sorted lazily and cache them.
        StatSample seriesSample;
        StatSample referenceSample;

        switch (maskMode) {
            case ALL:
                // Mask and reference sample both built once by recomputeAllStatistics().
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
