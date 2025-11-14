package com.kalix.ide.flowviz.data;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DataSet {
    private final List<TimeSeriesData> series;
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
        series.add(seriesData);
        updateGlobalBounds(seriesData);
        notifySeriesAdded(seriesData);
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
        return series.size();
    }
    
    public boolean isEmpty() {
        return series.isEmpty();
    }
    
    public boolean hasSeries(String name) {
        return series.stream().anyMatch(s -> s.getName().equals(name));
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
    
    // Statistics
    public int getTotalPointCount() {
        return series.stream().mapToInt(TimeSeriesData::getPointCount).sum();
    }
    
    public int getTotalValidPointCount() {
        return series.stream()
            .mapToInt(s -> s.getValidPointCount() != null ? s.getValidPointCount() : 0)
            .sum();
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