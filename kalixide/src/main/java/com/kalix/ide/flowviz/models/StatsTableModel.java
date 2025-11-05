package com.kalix.ide.flowviz.models;

import com.kalix.ide.flowviz.data.TimeSeriesData;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Table model for displaying statistics of multiple time series.
 * Shows series name, min, max, mean, and number of points for each series.
 */
public class StatsTableModel extends AbstractTableModel {
    private final String[] columnNames = {"Series", "Min", "Max", "Mean", "Points"};
    private final List<SeriesStats> seriesData = new ArrayList<>();

    /**
     * Statistics for a single time series.
     */
    public static class SeriesStats {
        public final String name;
        public final String min;
        public final String max;
        public final String mean;
        public final String points;

        /**
         * Creates empty/loading stats for a series.
         */
        public SeriesStats(String name) {
            this.name = name;
            this.min = "";
            this.max = "";
            this.mean = "";
            this.points = "";
        }

        /**
         * Creates stats from actual time series data.
         */
        public SeriesStats(String name, TimeSeriesData data) {
            this.name = name;
            if (data.getPointCount() == 0) {
                this.min = "-";
                this.max = "-";
                this.mean = "-";
                this.points = "0";
            } else {
                this.min = String.format("%.3f", data.getMinValue());
                this.max = String.format("%.3f", data.getMaxValue());
                this.mean = String.format("%.3f", data.getMeanValue());
                this.points = String.valueOf(data.getPointCount());
            }
        }

        /**
         * Protected constructor for subclasses.
         */
        protected SeriesStats(String name, String min, String max, String mean, String points) {
            this.name = name;
            this.min = min;
            this.max = max;
            this.mean = mean;
            this.points = points;
        }
    }

    @Override
    public int getRowCount() {
        return seriesData.size();
    }

    @Override
    public int getColumnCount() {
        return columnNames.length;
    }

    @Override
    public String getColumnName(int column) {
        return columnNames[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (rowIndex >= seriesData.size()) return "";

        SeriesStats stats = seriesData.get(rowIndex);
        switch (columnIndex) {
            case 0: return stats.name;
            case 1: return stats.min;
            case 2: return stats.max;
            case 3: return stats.mean;
            case 4: return stats.points;
            default: return "";
        }
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
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
     */
    public void addOrUpdateSeries(TimeSeriesData data) {
        // Remove existing entry if present
        seriesData.removeIf(stats -> stats.name.equals(data.getName()));
        seriesData.add(new SeriesStats(data.getName(), data));
        fireTableDataChanged();
    }

    /**
     * Adds an error entry for a series that failed to load.
     */
    public void addErrorSeries(String seriesName, String errorMessage) {
        // Remove existing entry if present
        seriesData.removeIf(stats -> stats.name.equals(seriesName));

        // Add error stats
        seriesData.add(new ErrorSeriesStats(seriesName));
        fireTableDataChanged();
    }

    /**
     * Removes a series from the table.
     */
    public void removeSeries(String seriesName) {
        seriesData.removeIf(stats -> stats.name.equals(seriesName));
        fireTableDataChanged();
    }

    /**
     * Clears all series from the table.
     */
    public void clear() {
        seriesData.clear();
        fireTableDataChanged();
    }

    /**
     * Error series stats with "Error" displayed in all value columns.
     */
    private static class ErrorSeriesStats extends SeriesStats {
        public ErrorSeriesStats(String name) {
            super(name, "Error", "Error", "Error", "Error");
        }
    }
}