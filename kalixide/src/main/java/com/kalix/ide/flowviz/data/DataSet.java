package com.kalix.ide.flowviz.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Container for multiple time series, serving as the shared data source for all plot tabs.
 *
 * <h2>Single Source of Truth</h2>
 * In RunManager, one DataSet instance ({@code plotDataSet}) is shared across all visualization tabs.
 * When data is added/removed, all tabs see the change immediately. Tabs then need to call
 * {@link com.kalix.ide.flowviz.PlotPanel#refreshData} to rebuild their display caches.
 *
 * <h2>Series Naming</h2>
 * Series names include the source suffix, e.g., "node.mygr4j.ds_1 [Last]" or "node.mygr4j.ds_1 [Run_1]".
 * This allows the same output from different runs to coexist in the dataset.
 *
 * <h2>Add Replaces Duplicates</h2>
 * {@link #addSeries} removes any existing series with the same name before adding.
 * This ensures {@link #getSeries} always returns the latest data and prevents duplicate entries.
 *
 * @see com.kalix.ide.windows.RunManager
 * @see com.kalix.ide.windows.VisualizationTabManager
 */
public class DataSet {
    // Legacy storage keyed by the rendered string name. Will be removed once all
    // call sites use the SeriesRef-keyed API below.
    private final List<TimeSeriesData> series;
    // New storage keyed by SeriesRef. Populated in parallel by the new API; the two
    // are kept independent during the Phase 1 migration so the old API behaviour is
    // unchanged. Insertion order preserved (matches plot series stacking order).
    private final Map<SeriesRef, TimeSeriesData> seriesByRef = new LinkedHashMap<>();
    private long globalMinTime = Long.MAX_VALUE;
    private long globalMaxTime = Long.MIN_VALUE;
    private Double globalMinValue;
    private Double globalMaxValue;

    // Thread-safe list for concurrent access during rendering
    private final List<DataSetListener> listeners = new CopyOnWriteArrayList<>();

    public DataSet() {
        this.series = new ArrayList<>();
    }
    
    public void addSeries(TimeSeriesData seriesData) {
        // Remove any existing series with the same name to ensure we don't have duplicates
        // This makes add idempotent and ensures getSeries() returns the latest data
        String name = seriesData.getName();
        boolean existed = series.removeIf(s -> s.getName().equals(name));

        series.add(seriesData);

        if (existed) {
            // Data was replaced - recompute bounds and notify
            recomputeGlobalBounds();
            notifyDataChanged();
        } else {
            updateGlobalBounds(seriesData);
            notifySeriesAdded(seriesData);
        }
    }
    
    public void removeSeries(String seriesName) {
        series.removeIf(s -> s.getName().equals(seriesName));
        recomputeGlobalBounds();
        notifySeriesRemoved(seriesName);
    }
    
    public void removeAllSeries() {
        List<String> removedNames = series.stream().map(TimeSeriesData::getName).toList();
        series.clear();
        resetGlobalBounds();
        
        for (String name : removedNames) {
            notifySeriesRemoved(name);
        }
    }
    
    private void updateGlobalBounds(TimeSeriesData newSeries) {
        if (newSeries.getPointCount() == 0) return;
        
        // Update time bounds
        globalMinTime = Math.min(globalMinTime, newSeries.getFirstTimestamp());
        globalMaxTime = Math.max(globalMaxTime, newSeries.getLastTimestamp());
        
        // Update value bounds
        Double seriesMin = newSeries.getMinValue();
        Double seriesMax = newSeries.getMaxValue();
        
        if (seriesMin != null && seriesMax != null) {
            if (globalMinValue == null || seriesMin < globalMinValue) {
                globalMinValue = seriesMin;
            }
            if (globalMaxValue == null || seriesMax > globalMaxValue) {
                globalMaxValue = seriesMax;
            }
        }
    }
    
    private void recomputeGlobalBounds() {
        resetGlobalBounds();
        for (TimeSeriesData seriesData : series) {
            updateGlobalBounds(seriesData);
        }
        // Phase 1: also include refs-keyed sidecar storage so bounds stay coherent
        // when both APIs are in use. Folds into a single loop when the legacy
        // string-keyed storage is removed.
        for (TimeSeriesData seriesData : seriesByRef.values()) {
            updateGlobalBounds(seriesData);
        }
    }
    
    private void resetGlobalBounds() {
        globalMinTime = Long.MAX_VALUE;
        globalMaxTime = Long.MIN_VALUE;
        globalMinValue = null;
        globalMaxValue = null;
    }
    
    // Getters
    public List<TimeSeriesData> getAllSeries() {
        return new ArrayList<>(series);
    }
    
    public TimeSeriesData getSeries(String name) {
        return series.stream()
            .filter(s -> s.getName().equals(name))
            .findFirst()
            .orElse(null);
    }
    
    public List<String> getSeriesNames() {
        return series.stream().map(TimeSeriesData::getName).toList();
    }
    
    public int getSeriesCount() {
        // Both storages are populated during the Phase 1 migration; the count is the
        // union (callers don't care which API the data came in through).
        return series.size() + seriesByRef.size();
    }

    public boolean isEmpty() {
        return series.isEmpty() && seriesByRef.isEmpty();
    }
    
    public boolean hasSeries(String name) {
        return series.stream().anyMatch(s -> s.getName().equals(name));
    }

    // ====================================================================
    // SeriesRef-keyed API (Phase 1 — additive; coexists with the string API
    // above during the identity refactor). Listeners are not fired by this
    // path yet; that wiring lands when the legacy API is removed and the two
    // storage models are unified.
    // ====================================================================

    /**
     * Adds (or replaces) a series under the given {@link SeriesRef}. The {@code data}'s
     * legacy name field is ignored — only the {@code ref} is used as identity. Recomputes
     * global bounds when the ref already existed; otherwise extends bounds incrementally.
     */
    public void addSeries(SeriesRef ref, TimeSeriesData data) {
        boolean existed = seriesByRef.containsKey(ref);
        seriesByRef.put(ref, data);
        if (existed) {
            recomputeGlobalBounds();
        } else {
            updateGlobalBounds(data);
        }
    }

    /**
     * Removes the series identified by {@code ref}. No-op if absent.
     */
    public void removeSeries(SeriesRef ref) {
        if (seriesByRef.remove(ref) != null) {
            recomputeGlobalBounds();
        }
    }

    /**
     * Returns the series identified by {@code ref}, or {@code null} if absent.
     */
    public TimeSeriesData getSeries(SeriesRef ref) {
        return seriesByRef.get(ref);
    }

    /**
     * Returns true if a series is present under {@code ref}.
     */
    public boolean hasSeries(SeriesRef ref) {
        return seriesByRef.containsKey(ref);
    }

    /**
     * Returns the refs currently in this dataset, in insertion order.
     */
    public List<SeriesRef> getSeriesRefs() {
        return new ArrayList<>(seriesByRef.keySet());
    }

    // Global bounds
    public long getGlobalMinTime() {
        return globalMinTime == Long.MAX_VALUE ? 0 : globalMinTime;
    }
    
    public long getGlobalMaxTime() {
        return globalMaxTime == Long.MIN_VALUE ? 0 : globalMaxTime;
    }
    
    public Double getGlobalMinValue() {
        return globalMinValue;
    }
    
    public Double getGlobalMaxValue() {
        return globalMaxValue;
    }
    
    public long getTimeRange() {
        if (globalMinTime == Long.MAX_VALUE || globalMaxTime == Long.MIN_VALUE) {
            return 0;
        }
        return globalMaxTime - globalMinTime;
    }
    
    public Double getValueRange() {
        if (globalMinValue == null || globalMaxValue == null) {
            return null;
        }
        return globalMaxValue - globalMinValue;
    }
    
    // Statistics — both storages contribute during the Phase 1 migration; folds into a
    // single stream when the legacy string-keyed storage is removed.
    public int getTotalPointCount() {
        int legacy = series.stream().mapToInt(TimeSeriesData::getPointCount).sum();
        int ref = seriesByRef.values().stream().mapToInt(TimeSeriesData::getPointCount).sum();
        return legacy + ref;
    }

    public int getTotalValidPointCount() {
        int legacy = series.stream()
            .mapToInt(s -> s.getValidPointCount() != null ? s.getValidPointCount() : 0)
            .sum();
        int ref = seriesByRef.values().stream()
            .mapToInt(s -> s.getValidPointCount() != null ? s.getValidPointCount() : 0)
            .sum();
        return legacy + ref;
    }
    
    // Event listeners for UI updates
    public interface DataSetListener {
        void onSeriesAdded(TimeSeriesData series);
        void onSeriesRemoved(String seriesName);
        void onDataChanged();
    }
    
    public void addListener(DataSetListener listener) {
        listeners.add(listener);
    }
    
    public void removeListener(DataSetListener listener) {
        listeners.remove(listener);
    }
    
    private void notifySeriesAdded(TimeSeriesData series) {
        for (DataSetListener listener : listeners) {
            try {
                listener.onSeriesAdded(series);
            } catch (Exception e) {
                System.err.println("Error in DataSet listener: " + e.getMessage());
            }
        }
    }
    
    private void notifySeriesRemoved(String seriesName) {
        for (DataSetListener listener : listeners) {
            try {
                listener.onSeriesRemoved(seriesName);
            } catch (Exception e) {
                System.err.println("Error in DataSet listener: " + e.getMessage());
            }
        }
    }
    
    private void notifyDataChanged() {
        for (DataSetListener listener : listeners) {
            try {
                listener.onDataChanged();
            } catch (Exception e) {
                System.err.println("Error in DataSet listener: " + e.getMessage());
            }
        }
    }
}