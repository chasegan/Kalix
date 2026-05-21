package com.kalix.ide.flowviz.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Container for multiple time series, serving as the shared data source for all plot tabs.
 *
 * <h2>Single Source of Truth</h2>
 * In RunManager, one DataSet instance ({@code plotDataSet}) is shared across all visualization tabs.
 * When data is added/removed, all tabs see the change immediately. Tabs then need to call
 * {@link com.kalix.ide.flowviz.PlotPanel#refreshData} to rebuild their display caches.
 *
 * <h2>Identity</h2>
 * Series are keyed by {@link SeriesRef}. The user-visible label is a separate concern,
 * projected from the ref by {@link LabelResolver} at render time.
 *
 * <h2>Add Replaces Duplicates</h2>
 * {@link #addSeries} replaces any existing series under the same ref.
 *
 * @see com.kalix.ide.windows.RunManager
 * @see com.kalix.ide.windows.VisualizationTabManager
 */
public class DataSet {
    // Storage keyed by SeriesRef. Insertion order preserved (matches plot series stacking order).
    private final Map<SeriesRef, TimeSeriesData> seriesByRef = new LinkedHashMap<>();
    private long globalMinTime = Long.MAX_VALUE;
    private long globalMaxTime = Long.MIN_VALUE;
    private Double globalMinValue;
    private Double globalMaxValue;

    // Thread-safe list for concurrent access during rendering
    private final List<DataSetListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Optional alias resolver: maps a {@link LastSeries} ref to whichever stable-identity
     * ref currently aliases it (typically a {@link RunSeries} for the latest completed run).
     * When set, {@link #addSeries(SeriesRef, TimeSeriesData)}, {@link #getSeries(SeriesRef)},
     * {@link #hasSeries(SeriesRef)} and {@link #removeSeries(SeriesRef)} transparently redirect
     * {@code LastSeries} accesses to the resolved ref — so the pool never stores data under a
     * {@code LastSeries} key, and "Last has changed" is just a different redirect target rather
     * than an invalidation event. Returns {@code null} when no Last is set; the access then
     * falls through to the {@code LastSeries} key directly (which won't exist in storage,
     * yielding {@code null} for reads and adding a sentinel entry for writes — both intended
     * as a "no Last available" signal).
     */
    private Function<LastSeries, SeriesRef> lastSeriesResolver;

    public DataSet() {
    }

    /**
     * Installs the {@link LastSeries} alias resolver — see {@link #lastSeriesResolver}. Pass
     * {@code null} to disable redirection (the default).
     */
    public void setLastSeriesResolver(Function<LastSeries, SeriesRef> resolver) {
        this.lastSeriesResolver = resolver;
    }

    /**
     * Returns the storage key for {@code ref}: for {@link LastSeries} this is the resolver's
     * target (typically a {@link RunSeries}); for any other ref the input is returned unchanged.
     * When the resolver is absent or yields {@code null}, the input ref is also returned
     * unchanged (callers then see either no data or write under the {@code LastSeries} key
     * — only happens when there is genuinely no current Last).
     */
    private SeriesRef storageKey(SeriesRef ref) {
        if (ref instanceof LastSeries last && lastSeriesResolver != null) {
            SeriesRef resolved = lastSeriesResolver.apply(last);
            return resolved != null ? resolved : ref;
        }
        return ref;
    }

    /**
     * Adds (or replaces) a series under the given {@link SeriesRef}. The {@code data}'s
     * legacy name field is ignored — only the {@code ref} is used as identity. Recomputes
     * global bounds when the ref already existed; otherwise extends bounds incrementally.
     *
     * <p>A {@link LastSeries} ref is redirected to its current alias target (see
     * {@link #lastSeriesResolver}), so "Last" data is stored under the underlying run's
     * stable identity and never goes stale when the Last run changes.</p>
     *
     * <p>Listeners are notified with the storage key (the resolved ref).</p>
     */
    public void addSeries(SeriesRef ref, TimeSeriesData data) {
        SeriesRef key = storageKey(ref);
        boolean existed = seriesByRef.containsKey(key);
        seriesByRef.put(key, data);
        if (existed) {
            recomputeGlobalBounds();
            notifyDataChanged();
        } else {
            updateGlobalBounds(data);
            notifySeriesAdded(key, data);
        }
    }

    /**
     * Removes the series identified by {@code ref}. No-op if absent. A {@link LastSeries}
     * ref is redirected to its current alias target before removal.
     */
    public void removeSeries(SeriesRef ref) {
        SeriesRef key = storageKey(ref);
        if (seriesByRef.remove(key) != null) {
            recomputeGlobalBounds();
            notifySeriesRemoved(key);
        }
    }

    /**
     * Removes every series and notifies listeners.
     */
    public void removeAllSeries() {
        List<SeriesRef> removed = new ArrayList<>(seriesByRef.keySet());
        seriesByRef.clear();
        resetGlobalBounds();
        for (SeriesRef ref : removed) {
            notifySeriesRemoved(ref);
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

    /**
     * Returns the series identified by {@code ref}, or {@code null} if absent. A
     * {@link LastSeries} ref is redirected to its current alias target, so the data
     * returned always reflects whichever run is currently Last.
     */
    public TimeSeriesData getSeries(SeriesRef ref) {
        return seriesByRef.get(storageKey(ref));
    }

    /**
     * Returns true if a series is present under {@code ref}. A {@link LastSeries} ref is
     * redirected to its current alias target.
     */
    public boolean hasSeries(SeriesRef ref) {
        return seriesByRef.containsKey(storageKey(ref));
    }

    /**
     * Returns the refs currently in this dataset, in insertion order.
     */
    public List<SeriesRef> getSeriesRefs() {
        return new ArrayList<>(seriesByRef.keySet());
    }

    /**
     * Returns all series data, in insertion order.
     */
    public List<TimeSeriesData> getAllSeries() {
        return new ArrayList<>(seriesByRef.values());
    }

    public int getSeriesCount() {
        return seriesByRef.size();
    }

    public boolean isEmpty() {
        return seriesByRef.isEmpty();
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

    public int getTotalPointCount() {
        return seriesByRef.values().stream().mapToInt(TimeSeriesData::getPointCount).sum();
    }

    // Event listeners for UI updates
    public interface DataSetListener {
        void onSeriesAdded(SeriesRef ref, TimeSeriesData data);
        void onSeriesRemoved(SeriesRef ref);
        void onDataChanged();
    }

    public void addListener(DataSetListener listener) {
        listeners.add(listener);
    }

    public void removeListener(DataSetListener listener) {
        listeners.remove(listener);
    }

    private void notifySeriesAdded(SeriesRef ref, TimeSeriesData data) {
        for (DataSetListener listener : listeners) {
            try {
                listener.onSeriesAdded(ref, data);
            } catch (Exception e) {
                System.err.println("Error in DataSet listener: " + e.getMessage());
            }
        }
    }

    private void notifySeriesRemoved(SeriesRef ref) {
        for (DataSetListener listener : listeners) {
            try {
                listener.onSeriesRemoved(ref);
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
