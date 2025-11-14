package com.kalix.ide.flowviz.models;

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
    private String referenceSeries = null;  // Name of first series

    // Cache of original (unmasked) series data
    // Using LinkedHashMap to preserve insertion order
    private final Map<String, TimeSeriesData> originalSeriesCache = new LinkedHashMap<>();

    /**
     * Statistics for a single time series with all computed values.
     */
    public static class SeriesStats {
        public final String name;
        public final Map<String, String> statisticValues;  // Statistic name -> computed value

        /**
         * Creates empty/loading stats for a series.
         */
        public SeriesStats(String name) {
            this.name = name;
            this.statisticValues = new HashMap<>();
            // Initialize with empty values
            for (Statistic stat : StatisticsRegistry.getAll()) {
                this.statisticValues.put(stat.getName(), "");
            }
        }

        /**
         * Creates stats with computed values.
         */
        public SeriesStats(String name, Map<String, String> statisticValues) {
            this.name = name;
            this.statisticValues = new HashMap<>(statisticValues);
        }
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
            // First column is series name
            return stats.name;
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
    public void addLoadingSeries(String seriesName) {
        // Remove existing entry if present
        seriesData.removeIf(stats -> stats.name.equals(seriesName));
        seriesData.add(new SeriesStats(seriesName));
        fireTableDataChanged();
    }

    /**
     * Adds or updates series with actual data.
     * Stores the original data and computes statistics with current mask mode.
     */
    public void addOrUpdateSeries(TimeSeriesData data) {
        // Cache original data
        originalSeriesCache.put(data.getName(), data);

        // Update reference series if this is the first series
        if (referenceSeries == null) {
            referenceSeries = data.getName();
        }

        // If mask mode is ALL, adding a new series changes the mask for all existing series
        // So we need to recompute everything
        if (maskMode == MaskMode.ALL) {
            recomputeAllStatistics();
        } else {
            // For EACH or NONE modes, only this series needs updating
            Map<String, String> statisticValues = computeStatistics(data);

            // Remove existing entry if present
            seriesData.removeIf(stats -> stats.name.equals(data.getName()));
            seriesData.add(new SeriesStats(data.getName(), statisticValues));
            fireTableDataChanged();
        }
    }

    /**
     * Adds an error entry for a series that failed to load.
     */
    public void addErrorSeries(String seriesName, String errorMessage) {
        // Remove existing entry if present
        seriesData.removeIf(stats -> stats.name.equals(seriesName));

        // Add error stats with "Error" in all columns
        Map<String, String> errorValues = new HashMap<>();
        for (Statistic stat : StatisticsRegistry.getAll()) {
            errorValues.put(stat.getName(), "Error");
        }
        seriesData.add(new SeriesStats(seriesName, errorValues));
        fireTableDataChanged();
    }

    /**
     * Removes a series from the table.
     */
    public void removeSeries(String seriesName) {
        seriesData.removeIf(stats -> stats.name.equals(seriesName));
        originalSeriesCache.remove(seriesName);

        // Update reference if we removed it
        if (seriesName.equals(referenceSeries)) {
            referenceSeries = seriesData.isEmpty() ? null : seriesData.get(0).name;
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
     */
    private void recomputeAllStatistics() {
        // Rebuild seriesData from scratch based on originalSeriesCache
        List<SeriesStats> newSeriesData = new ArrayList<>();

        for (String seriesName : originalSeriesCache.keySet()) {
            TimeSeriesData originalData = originalSeriesCache.get(seriesName);
            if (originalData != null) {
                Map<String, String> newValues = computeStatistics(originalData);
                newSeriesData.add(new SeriesStats(seriesName, newValues));
            }
        }

        seriesData.clear();
        seriesData.addAll(newSeriesData);
        fireTableDataChanged();
    }

    /**
     * Computes all statistics for a series using the current mask mode.
     *
     * @param series The series to compute statistics for
     * @return Map of statistic names to computed values
     */
    private Map<String, String> computeStatistics(TimeSeriesData series) {
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
                // Create mask where ALL series have data
                List<TimeSeriesData> allSeries = new ArrayList<>(originalSeriesCache.values());
                TimeSeriesData allMask = TimeSeriesMasker.createAllMask(allSeries);
                maskedSeries = TimeSeriesMasker.applyMask(series, allMask);
                if (referenceData != null) {
                    maskedReference = TimeSeriesMasker.applyMask(referenceData, allMask);
                }
                break;

            case EACH:
                // Create mask for this series and reference
                if (referenceData != null) {
                    TimeSeriesData eachMask = TimeSeriesMasker.createEachMask(referenceData, series);
                    maskedSeries = TimeSeriesMasker.applyMask(series, eachMask);
                    maskedReference = TimeSeriesMasker.applyMask(referenceData, eachMask);
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
