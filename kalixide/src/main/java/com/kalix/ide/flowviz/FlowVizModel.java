package com.kalix.ide.flowviz;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The one owner of a tab's state: the current {@link FlowVizState}, its undo/redo history,
 * and the listeners that project it (the plot, and later the stats and data pages).
 *
 * <p>A change is one {@link #update}; listeners receive the old and new snapshots and
 * work out for themselves what changed. Recording a history entry is a separate step,
 * {@link #record()}, so the owner can first fold in what a change produced (the
 * fitted viewport) and the entry holds what the user saw.
 *
 * <p>EDT only.
 */
public class FlowVizModel {

    /** Why the state changed: a user gesture, an undo/redo/copy, or new data. */
    public enum Cause { USER, RESTORE, DATA }

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

    // Migration shim: updates inside batch() notify once, at the end.
    private int batchDepth;
    private FlowVizState batchStart;

    /** The current state, never null. */
    public FlowVizState current() {
        return state;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** Applies a change and notifies listeners with cause USER; no-op when nothing changed. */
    public void update(UnaryOperator<FlowVizState> change) {
        FlowVizState next = change.apply(state);
        if (next.equals(state)) {
            return;
        }
        FlowVizState old = state;
        state = next;
        if (batchDepth == 0) {
            fire(old, next, Cause.USER);
        }
    }

    /**
     * Runs several updates as one change: listeners hear once, with the state before
     * the first and after the last. Nests. Migration shim for the panel's forwarding
     * setters; removed once callers compose their changes into one {@link #update}.
     */
    public void batch(Runnable changes) {
        if (batchDepth++ == 0) {
            batchStart = state;
        }
        try {
            changes.run();
        } finally {
            if (--batchDepth == 0 && !state.equals(batchStart)) {
                fire(batchStart, state, Cause.USER);
            }
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
        }
    }

    public int size() {
        return history.size();
    }

    private void restore(FlowVizState snapshot) {
        FlowVizState old = state;
        state = snapshot;
        fire(old, snapshot, Cause.RESTORE);
    }

    private void fire(FlowVizState old, FlowVizState now, Cause cause) {
        for (Listener listener : List.copyOf(listeners)) {
            listener.stateChanged(old, now, cause);
        }
    }
}
