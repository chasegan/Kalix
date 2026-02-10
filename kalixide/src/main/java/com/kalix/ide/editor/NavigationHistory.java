package com.kalix.ide.editor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages navigation history for the text editor, enabling Back/Forward navigation.
 * Uses a single list with a current index pointer to track visited positions.
 *
 * Positions are only added for "jump" navigation (e.g., Go to Node, mouse clicks
 * that change lines) rather than incremental moves (arrow keys, typing).
 *
 * Uses javax.swing.text.Position internally to automatically track document changes,
 * so navigation points remain valid even after text is inserted or deleted.
 */
public class NavigationHistory {
    private static final Logger logger = LoggerFactory.getLogger(NavigationHistory.class);

    /**
     * Represents a position in the editor (used for input/output).
     */
    public record Position(int offset, int line) {}

    /**
     * Internal entry that uses document Position for automatic offset tracking.
     */
    private static class HistoryEntry {
        final javax.swing.text.Position docPosition;
        final int originalLine;

        HistoryEntry(javax.swing.text.Position docPosition, int originalLine) {
            this.docPosition = docPosition;
            this.originalLine = originalLine;
        }

        int getCurrentOffset() {
            return docPosition.getOffset();
        }
    }

    private final List<HistoryEntry> history = new ArrayList<>();
    private int currentIndex = -1;
    private Document document;

    // Maximum history size to prevent unbounded memory growth
    private static final int MAX_HISTORY_SIZE = 100;

    // Listeners for state changes
    private Runnable stateChangeListener;

    /**
     * Sets a listener to be notified when navigation state changes.
     * This is used to update button enabled states.
     */
    public void setStateChangeListener(Runnable listener) {
        this.stateChangeListener = listener;
    }

    /**
     * Sets the document reference for creating tracked positions.
     * Must be called before recording any positions.
     */
    public void setDocument(Document document) {
        this.document = document;
    }

    /**
     * Creates a HistoryEntry from a Position, using the document to create
     * a tracked position that automatically adjusts with document changes.
     */
    private HistoryEntry createEntry(Position position) {
        if (document == null) {
            logger.warn("Document not set, navigation history may not track edits correctly");
            return null;
        }

        try {
            // Clamp offset to valid range
            int offset = Math.max(0, Math.min(position.offset(), document.getLength()));
            javax.swing.text.Position docPos = document.createPosition(offset);
            return new HistoryEntry(docPos, position.line());
        } catch (BadLocationException e) {
            logger.warn("Failed to create document position at offset {}", position.offset());
            return null;
        }
    }

    /**
     * Records a navigation jump to a new position.
     * Should be called before navigating to the new position.
     *
     * @param currentPosition The current position before the jump
     * @param newPosition The position being jumped to
     */
    public void recordJump(Position currentPosition, Position newPosition) {
        // Only record if it's an actual line change
        if (currentPosition.line() == newPosition.line()) {
            return;
        }

        // If we're not at the end of history, truncate forward history
        if (currentIndex < history.size() - 1) {
            history.subList(currentIndex + 1, history.size()).clear();
        }

        // Add the current position (where we're jumping from) if it's different
        // from the last recorded position
        if (history.isEmpty() || !entriesOnSameLine(history.get(history.size() - 1), currentPosition.line())) {
            HistoryEntry entry = createEntry(currentPosition);
            if (entry != null) {
                history.add(entry);
            }
        }

        // Add the new position
        HistoryEntry newEntry = createEntry(newPosition);
        if (newEntry != null) {
            history.add(newEntry);
            currentIndex = history.size() - 1;
        }

        // Trim history if it exceeds maximum size
        while (history.size() > MAX_HISTORY_SIZE) {
            history.remove(0);
            currentIndex--;
        }

        logger.debug("Recorded jump: line {} -> line {}, history size: {}, index: {}",
            currentPosition.line(), newPosition.line(), history.size(), currentIndex);

        notifyStateChange();
    }

    /**
     * Records a single position when first navigating (e.g., initial mouse click).
     *
     * @param position The position to record
     */
    public void recordPosition(Position position) {
        // If we're not at the end of history, truncate forward history
        if (currentIndex < history.size() - 1) {
            history.subList(currentIndex + 1, history.size()).clear();
        }

        // Only add if different from last position
        if (history.isEmpty() || !entriesOnSameLine(history.get(history.size() - 1), position.line())) {
            HistoryEntry entry = createEntry(position);
            if (entry != null) {
                history.add(entry);
                currentIndex = history.size() - 1;

                // Trim history if it exceeds maximum size
                while (history.size() > MAX_HISTORY_SIZE) {
                    history.remove(0);
                    currentIndex--;
                }

                logger.debug("Recorded position: line {}, history size: {}, index: {}",
                    position.line(), history.size(), currentIndex);

                notifyStateChange();
            }
        }
    }

    /**
     * @return true if there is a previous position to go back to
     */
    public boolean canGoBack() {
        return currentIndex > 0;
    }

    /**
     * @return true if there is a forward position to go to
     */
    public boolean canGoForward() {
        return currentIndex < history.size() - 1;
    }

    /**
     * Goes back to the previous position in history.
     *
     * @return The previous position, or null if can't go back
     */
    public Position goBack() {
        if (!canGoBack()) {
            return null;
        }

        currentIndex--;
        HistoryEntry entry = history.get(currentIndex);
        Position position = new Position(entry.getCurrentOffset(), entry.originalLine);
        logger.debug("Going back to offset {}, original line {}, index: {}",
            entry.getCurrentOffset(), entry.originalLine, currentIndex);
        notifyStateChange();
        return position;
    }

    /**
     * Goes forward to the next position in history.
     *
     * @return The next position, or null if can't go forward
     */
    public Position goForward() {
        if (!canGoForward()) {
            return null;
        }

        currentIndex++;
        HistoryEntry entry = history.get(currentIndex);
        Position position = new Position(entry.getCurrentOffset(), entry.originalLine);
        logger.debug("Going forward to offset {}, original line {}, index: {}",
            entry.getCurrentOffset(), entry.originalLine, currentIndex);
        notifyStateChange();
        return position;
    }

    /**
     * Clears all navigation history.
     */
    public void clear() {
        history.clear();
        currentIndex = -1;
        notifyStateChange();
    }

    /**
     * Checks if an entry is on the same line as the given line number.
     */
    private boolean entriesOnSameLine(HistoryEntry entry, int line) {
        return entry.originalLine == line;
    }

    private void notifyStateChange() {
        if (stateChangeListener != null) {
            stateChangeListener.run();
        }
    }
}
