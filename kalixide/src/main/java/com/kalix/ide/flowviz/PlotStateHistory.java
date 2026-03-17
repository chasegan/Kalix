package com.kalix.ide.flowviz;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages undo/redo history for plot state.
 * Maintains a list of {@link PlotState} snapshots with a current position pointer.
 * Pushing a new state truncates any redo states ahead of the current position.
 */
public class PlotStateHistory {

    private static final int MAX_HISTORY_SIZE = 50;

    private final List<PlotState> history = new ArrayList<>();
    private int currentIndex = -1;

    public PlotStateHistory() {}

    /**
     * Copy constructor — duplicates the full history and current position.
     * PlotState is immutable so a shallow copy of the list is sufficient.
     */
    public PlotStateHistory(PlotStateHistory other) {
        this.history.addAll(other.history);
        this.currentIndex = other.currentIndex;
    }

    /**
     * Pushes a new state if it differs from the current state.
     * Truncates any redo states ahead of the current position.
     *
     * @return true if the state was pushed (i.e., it was different from current)
     */
    public boolean pushIfChanged(PlotState state) {
        if (state == null) return false;

        // Skip if identical to current state
        if (currentIndex >= 0 && currentIndex < history.size()
                && state.equals(history.get(currentIndex))) {
            return false;
        }

        // Truncate any redo states
        while (history.size() > currentIndex + 1) {
            history.remove(history.size() - 1);
        }

        // Enforce max size by removing oldest entries
        if (history.size() >= MAX_HISTORY_SIZE) {
            history.remove(0);
            currentIndex--;
        }

        history.add(state);
        currentIndex = history.size() - 1;
        return true;
    }

    /**
     * Moves back one step in history.
     *
     * @return the previous state, or null if at the beginning
     */
    public PlotState undo() {
        if (!canUndo()) return null;
        currentIndex--;
        return history.get(currentIndex);
    }

    /**
     * Moves forward one step in history.
     *
     * @return the next state, or null if at the end
     */
    public PlotState redo() {
        if (!canRedo()) return null;
        currentIndex++;
        return history.get(currentIndex);
    }

    public boolean canUndo() {
        return currentIndex > 0;
    }

    public boolean canRedo() {
        return currentIndex < history.size() - 1;
    }

    /**
     * Replaces this history's contents with a copy of another's.
     */
    public void copyFrom(PlotStateHistory other) {
        history.clear();
        history.addAll(other.history);
        currentIndex = other.currentIndex;
    }

    /**
     * Returns the current state, or null if history is empty.
     */
    public PlotState current() {
        if (currentIndex >= 0 && currentIndex < history.size()) {
            return history.get(currentIndex);
        }
        return null;
    }

    public int size() {
        return history.size();
    }
}
