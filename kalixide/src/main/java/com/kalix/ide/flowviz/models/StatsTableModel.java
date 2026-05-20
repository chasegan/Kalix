package com.kalix.ide.flowviz.models;

import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.stats.MaskMode;
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

    @Override
    public int getColumnCount() {
        return 1 + StatisticsRegistry.getStatisticCount();  // "Series" + all statistics
    }

    @Override
    public String getColumnName(int column) {
        String[] columnNames = StatisticsRegistry.getColumnNames();
        if (column >= 0 && column < columnNames.length) {
            return columnNames[column];
        }
        return "";
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (rowIndex >= seriesData.size()) return "";

        SeriesStats stats = seriesData.get(rowIndex);

        if (columnIndex == 0) {
            // First column is the projected display label (recomputed each render so it
            // tracks renames automatically).
            return labelFor(stats.ref);
        }

        // Remaining columns are statistics
        List<Statistic> allStats = StatisticsRegistry.getAll();
        int statIndex = columnIndex - 1;

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
            // For EACH or NONE modes, only this series needs updating (no ALL mask).
            Map<String, String> statisticValues = computeStatistics(data, null);

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
        // In ALL mode the mask is shared across every series — compute it a single time.
        TimeSeriesMasker.Mask allMask = null;
        if (maskMode == MaskMode.ALL) {
            allMask = TimeSeriesMasker.createAllMask(new ArrayList<>(originalSeriesCache.values()));
        }

        // Rebuild seriesData from scratch based on originalSeriesCache
        List<SeriesStats> newSeriesData = new ArrayList<>();

        for (Map.Entry<SeriesRef, TimeSeriesData> entry : originalSeriesCache.entrySet()) {
            TimeSeriesData originalData = entry.getValue();
            if (originalData != null) {
                Map<String, String> newValues = computeStatistics(originalData, allMask);
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
     * @param series  The series to compute statistics for
     * @param allMask The pre-built {@link MaskMode#ALL} mask; required in ALL mode,
     *                ignored otherwise (pass {@code null} for EACH/NONE).
     * @return Map of statistic names to computed values
     */
    private Map<String, String> computeStatistics(TimeSeriesData series,
                                                  TimeSeriesMasker.Mask allMask) {
        Map<String, String> values = new HashMap<>();

        // Get reference series data (always available for bivariate stats)
        TimeSeriesData referenceData = null;
        if (referenceSeries != null) {
            referenceData = originalSeriesCache.get(referenceSeries);
        }

        // Apply masking based on mode
        TimeSeriesData maskedSeries;
        TimeSeriesData maskedReference = null;

        switch (maskMode) {
            case ALL:
                // Shared mask built once by recomputeAllStatistics().
                maskedSeries = allMask.apply(series);
                if (referenceData != null) {
                    maskedReference = allMask.apply(referenceData);
                }
                break;

            case EACH:
                // Create mask for this series and reference
                if (referenceData != null) {
                    TimeSeriesMasker.Mask eachMask = TimeSeriesMasker.createEachMask(referenceData, series);
                    maskedSeries = eachMask.apply(series);
                    maskedReference = eachMask.apply(referenceData);
                } else {
                    // No reference available, use unmasked
                    maskedSeries = series;
                }
                break;

            case NONE:
            default:
                // No masking
                maskedSeries = series;
                maskedReference = referenceData;
                break;
        }

        // Compute each statistic
        for (Statistic stat : StatisticsRegistry.getAll()) {
            String value;

            // Skip bivariate statistics if mask mode is NONE
            if (stat.isBivariate() && maskMode == MaskMode.NONE) {
                value = "N/A";
            } else {
                value = stat.calculate(maskedSeries, maskedReference);
            }

            values.put(stat.getName(), value);
        }

        return values;
    }
}
