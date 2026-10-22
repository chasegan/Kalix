package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.stats.MaskMode;
import com.kalix.ide.flowviz.stats.SeasonalMaskMode;
import com.kalix.ide.flowviz.stats.TimeSeriesMasker;
import com.kalix.ide.flowviz.transform.AggregatedSeriesCache;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;
import com.kalix.ide.flowviz.transform.AggregationPipeline;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.flowviz.transform.PlotTypeTransformer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The one owner of a tab's state: the current {@link FlowVizState}, its undo/redo history,
 * the listeners that project it (the plot, stats and data pages), and the data derived
 * from it: the aggregated series and the display set every page reads.
 *
 * <p>A change is one {@link #update}, and one history entry; listeners receive the old
 * and new snapshots and work out for themselves what changed. The entry is recorded
 * after every listener has run, so what a change produced (the plot's fitted viewport,
 * folded in with {@link #amend}) is part of it, and undo returns to what the user saw.
 * Nothing is recorded until the model has a pool: setup is not history.
 *
 * <p>EDT only.
 */
public class FlowVizModel {

    /**
     * Why the state changed: a user gesture; a whole new state to show in full (a new or
     * reset tab); an undo, redo or copy; or new data under an unchanged state.
     */
    public enum Cause { USER, REPLACE, RESTORE, DATA }

    /** Receives every change of the current state. */
    @FunctionalInterface
    public interface Listener {
        void stateChanged(FlowVizState old, FlowVizState now, Cause cause);
    }

    // Generous by design: a FlowVizState is a list of refs, a set of sources, a few
    // enums and four doubles, so even a full history is kilobytes per tab. The old
    // 50-entry cap predated source ticks becoming undoable steps and could silently
    // evict "undo to birth" mid-session.
    private static final int MAX_HISTORY_SIZE = 1000;

    private final List<FlowVizState> history = new ArrayList<>();
    private int currentIndex = -1;

    private final List<Listener> listeners = new ArrayList<>();
    private FlowVizState state = FlowVizState.INITIAL;

    private Runnable onHistoryChanged;

    // === DERIVED DATA ===
    // Both are computed on demand and reused while the settings and the pool's data
    // objects are unchanged, so new data is noticed without invalidation calls.
    private DataSet pool;
    private final AggregatedSeriesCache aggregateCache = new AggregatedSeriesCache();

    /** What a display set was built from: the settings it depends on, and its aggregates by identity. */
    private record DisplayKey(AggregationPeriod period, AggregationMethod method, PlotType plotType,
                              MaskMode maskMode, SeasonalMaskMode seasonalMaskMode,
                              List<SeriesRef> visibleSeries) {
    }

    private DisplayKey displayKey;
    private List<TimeSeriesData> displaySources = List.of();
    private DataSet display;

    /** The current state, never null. */
    public FlowVizState current() {
        return state;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** The shared data pool the state's series resolve against. */
    public void setPool(DataSet pool) {
        this.pool = pool;
        this.display = null;
    }

    /**
     * The visible series, aggregated under the current settings, in selection order (the
     * first is the bivariate reference). A fresh map; series missing from the pool are
     * skipped. The seasonal mask is applied inside aggregation (#235).
     */
    public LinkedHashMap<SeriesRef, TimeSeriesData> aggregated() {
        return AggregationPipeline.aggregate(aggregateCache, pool, state.getVisibleSeries(),
            state.getAggregationPeriod(), state.getAggregationMethod(), state.getSeasonalMaskMode());
    }

    /**
     * What the plot draws and the data page tabulates: {@link #aggregated()}, then the
     * overlap mask ({@link TimeSeriesMasker#maskOverlap}), then the plot-type transform.
     * The same object is returned while nothing it depends on has changed.
     */
    public DataSet display() {
        LinkedHashMap<SeriesRef, TimeSeriesData> aggregated = aggregated();
        DisplayKey key = new DisplayKey(state.getAggregationPeriod(), state.getAggregationMethod(),
            state.getPlotType(), state.getMaskMode(), state.getSeasonalMaskMode(), state.getVisibleSeries());
        List<TimeSeriesData> sources = List.copyOf(aggregated.values());
        if (display != null && key.equals(displayKey) && sameObjects(sources, displaySources)) {
            return display;
        }
        DataSet aggregatedSet = new DataSet();
        aggregated.forEach(aggregatedSet::addSeries);
        DataSet masked = TimeSeriesMasker.maskOverlap(aggregatedSet, state.getMaskMode());
        display = PlotTypeTransformer.transform(masked, state.getPlotType(), state.getVisibleSeries());
        displayKey = key;
        displaySources = sources;
        return display;
    }

    private static boolean sameObjects(List<TimeSeriesData> a, List<TimeSeriesData> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    /** Applies a change (cause USER) and records it; no-op when nothing changed. */
    public void update(UnaryOperator<FlowVizState> change) {
        FlowVizState next = change.apply(state);
        if (next.equals(state)) {
            return;
        }
        FlowVizState old = state;
        state = next;
        fire(old, next, Cause.USER);
        recordIfReady();
    }

    /**
     * Replaces the whole state (cause REPLACE) and records it: a new or reset tab, which
     * listeners show in full (the plot fits all of it) even where little changed.
     */
    public void replace(FlowVizState next) {
        FlowVizState old = state;
        state = next;
        fire(old, next, Cause.REPLACE);
        recordIfReady();
    }

    /**
     * Changes the state without notifying anyone or recording an entry: for what no
     * page draws from — the viewport bounds a change produced, and the checked sources,
     * which ride into the next entry recorded.
     */
    public void amend(UnaryOperator<FlowVizState> change) {
        state = change.apply(state);
    }

    /** Called whenever the history changes (an entry recorded, undo, redo, copy). */
    public void setOnHistoryChanged(Runnable onHistoryChanged) {
        this.onHistoryChanged = onHistoryChanged;
    }

    private void recordIfReady() {
        if (pool != null) {
            record();
        }
    }

    /** Tells listeners the data under the state changed (cause DATA); the state itself is unchanged. */
    public void dataChanged() {
        fire(state, state, Cause.DATA);
    }

    /**
     * Records the current state as a history entry, truncating any redo states.
     *
     * @return true if an entry was added (the state differed from the current entry)
     */
    public boolean record() {
        if (currentIndex >= 0 && currentIndex < history.size()
                && state.equals(history.get(currentIndex))) {
            return false;
        }

        // Truncate any redo states
        while (history.size() > currentIndex + 1) {
            history.remove(history.size() - 1);
        }

        // Enforce max size by removing oldest entries
        if (history.size() >= MAX_HISTORY_SIZE && currentIndex > 0) {
            history.remove(0);
            currentIndex--;
        }

        history.add(state);
        currentIndex = history.size() - 1;
        historyChanged();
        return true;
    }

    /**
     * Moves back one step in history and restores that state (cause RESTORE).
     *
     * @return the restored state, or null if at the beginning
     */
    public FlowVizState undo() {
        if (!canUndo()) return null;
        currentIndex--;
        restore(history.get(currentIndex));
        return state;
    }

    /**
     * Moves forward one step in history and restores that state (cause RESTORE).
     *
     * @return the restored state, or null if at the end
     */
    public FlowVizState redo() {
        if (!canRedo()) return null;
        currentIndex++;
        restore(history.get(currentIndex));
        return state;
    }

    public boolean canUndo() {
        return currentIndex > 0;
    }

    public boolean canRedo() {
        return currentIndex < history.size() - 1;
    }

    /**
     * Replaces this model's history with a copy of another's and restores its current
     * entry (cause RESTORE), so a duplicated tab looks identical and undoes the same way.
     */
    public void copyFrom(FlowVizModel other) {
        history.clear();
        history.addAll(other.history);
        currentIndex = other.currentIndex;
        if (currentIndex >= 0) {
            restore(history.get(currentIndex));
        } else {
            historyChanged();
        }
    }

    public int size() {
        return history.size();
    }

    private void restore(FlowVizState snapshot) {
        FlowVizState old = state;
        state = snapshot;
        fire(old, snapshot, Cause.RESTORE);
        historyChanged();
    }

    private void historyChanged() {
        if (onHistoryChanged != null) {
            onHistoryChanged.run();
        }
    }

    private void fire(FlowVizState old, FlowVizState now, Cause cause) {
        for (Listener listener : List.copyOf(listeners)) {
            listener.stateChanged(old, now, cause);
        }
    }
}
