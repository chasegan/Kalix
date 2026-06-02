package com.kalix.ide.document;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Owns the set of open {@link KalixDocument}s and the notion of which one is
 * <em>active</em>. This is the spine the rest of the UI listens to: the tab strip,
 * contextual view, status bar, title bar, file watcher, menus and key bindings all
 * act on {@link #getActiveDocument()} and refresh when it changes.
 *
 * <p>There is exactly one active pointer. Views observe three events
 * (opened / closed / active-changed) rather than reaching into each other, which keeps
 * the wiring acyclic (see {@code docs/multi-document-architecture.md}).
 */
public class DocumentManager {

    private final List<KalixDocument> documents = new ArrayList<>();
    private final List<Consumer<KalixDocument>> openedListeners = new ArrayList<>();
    private final List<Consumer<KalixDocument>> closedListeners = new ArrayList<>();
    private final List<Consumer<KalixDocument>> activeChangeListeners = new ArrayList<>();

    private KalixDocument activeDocument;

    /**
     * Adds a document to the set (without changing the active document) and notifies
     * opened-listeners.
     */
    public void addDocument(KalixDocument document) {
        if (documents.contains(document)) {
            return;
        }
        documents.add(document);
        notifyOpened(document);
    }

    /**
     * Removes a document from the set, notifies closed-listeners, and — if it was the
     * active document — moves the active pointer to a neighbour (or {@code null} if none
     * remain), notifying active-change listeners.
     */
    public void closeDocument(KalixDocument document) {
        int index = documents.indexOf(document);
        if (index < 0) {
            return;
        }
        documents.remove(index);
        notifyClosed(document);

        if (activeDocument == document) {
            KalixDocument next = documents.isEmpty()
                ? null
                : documents.get(Math.min(index, documents.size() - 1));
            activeDocument = next;
            notifyActiveDocumentChanged();
        }
    }

    /**
     * Sets the active document, adding it to the set if not already present, and
     * notifies active-change listeners. Passing the already-active document is a no-op.
     */
    public void setActiveDocument(KalixDocument document) {
        addDocument(document);
        if (document == activeDocument) {
            return;
        }
        activeDocument = document;
        notifyActiveDocumentChanged();
    }

    /**
     * @return the currently active document, or {@code null} if none is open
     */
    public KalixDocument getActiveDocument() {
        return activeDocument;
    }

    /**
     * @return an unmodifiable view of all open documents, in tab order
     */
    public List<KalixDocument> getDocuments() {
        return Collections.unmodifiableList(documents);
    }

    /**
     * @return the open document backed by the given file, or {@code null} if none
     */
    public KalixDocument findByFile(File file) {
        if (file == null) {
            return null;
        }
        for (KalixDocument document : documents) {
            if (file.equals(document.getFile())) {
                return document;
            }
        }
        return null;
    }

    // --- Listeners ---

    /** Registers a listener invoked when a document is added to the set. */
    public void addDocumentOpenedListener(Consumer<KalixDocument> listener) {
        openedListeners.add(listener);
    }

    /** Registers a listener invoked when a document is removed from the set. */
    public void addDocumentClosedListener(Consumer<KalixDocument> listener) {
        closedListeners.add(listener);
    }

    /**
     * Registers a listener invoked when the active document changes. The listener
     * receives the new active document, which may be {@code null} (transiently, when the
     * last document is closed before a replacement is opened).
     */
    public void addActiveDocumentChangeListener(Consumer<KalixDocument> listener) {
        activeChangeListeners.add(listener);
    }

    private void notifyOpened(KalixDocument document) {
        for (Consumer<KalixDocument> listener : openedListeners) {
            listener.accept(document);
        }
    }

    private void notifyClosed(KalixDocument document) {
        for (Consumer<KalixDocument> listener : closedListeners) {
            listener.accept(document);
        }
    }

    private void notifyActiveDocumentChanged() {
        for (Consumer<KalixDocument> listener : activeChangeListeners) {
            listener.accept(activeDocument);
        }
    }
}
