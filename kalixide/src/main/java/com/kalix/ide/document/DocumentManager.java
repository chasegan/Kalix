package com.kalix.ide.document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Owns the set of open {@link KalixDocument}s and the notion of which one is
 * <em>active</em>. This is the spine the rest of the UI listens to: status bar,
 * title bar, file watcher, menus and key bindings all act on
 * {@link #getActiveDocument()} and refresh when it changes.
 *
 * <p>In Phase 1 there is exactly one document and the active-document never changes
 * after startup, but the API is shaped for the multi-document future
 * (see {@code docs/multi-document-architecture.md}): {@link #addActiveDocumentChangeListener}
 * lets observers re-bind when the user switches tabs in Phase 3.
 */
public class DocumentManager {

    private final List<KalixDocument> documents = new ArrayList<>();
    private final List<Consumer<KalixDocument>> activeChangeListeners = new ArrayList<>();

    private KalixDocument activeDocument;

    /**
     * Adds a document to the set without changing the active document.
     */
    public void addDocument(KalixDocument document) {
        if (!documents.contains(document)) {
            documents.add(document);
        }
    }

    /**
     * Sets the active document, adding it to the set if not already present, and
     * notifies listeners. Passing the already-active document is a no-op.
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
     * @return an unmodifiable view of all open documents
     */
    public List<KalixDocument> getDocuments() {
        return Collections.unmodifiableList(documents);
    }

    /**
     * Registers a listener invoked whenever the active document changes. The
     * listener receives the new active document (which may be {@code null}).
     */
    public void addActiveDocumentChangeListener(Consumer<KalixDocument> listener) {
        activeChangeListeners.add(listener);
    }

    private void notifyActiveDocumentChanged() {
        for (Consumer<KalixDocument> listener : activeChangeListeners) {
            listener.accept(activeDocument);
        }
    }
}
