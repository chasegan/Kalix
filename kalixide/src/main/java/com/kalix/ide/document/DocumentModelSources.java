package com.kalix.ide.document;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link ModelSourceRegistry} backed by the open documents.
 *
 * <p>A thin adapter: {@link KalixDocument} already <em>is</em> a {@link ModelSource},
 * so this only forwards the document set and folds the three
 * {@link DocumentManager} events (opened / closed / active-changed) into the single
 * "something changed" signal auxiliary windows care about.</p>
 */
public class DocumentModelSources implements ModelSourceRegistry {

    private final DocumentManager documentManager;
    private final List<Runnable> listeners = new ArrayList<>();

    public DocumentModelSources(DocumentManager documentManager) {
        this.documentManager = documentManager;
        documentManager.addDocumentOpenedListener(doc -> fireChanged());
        documentManager.addDocumentClosedListener(doc -> fireChanged());
        documentManager.addActiveDocumentChangeListener(doc -> fireChanged());
    }

    @Override
    public List<? extends ModelSource> available() {
        return documentManager.getDocuments();
    }

    @Override
    public ModelSource active() {
        return documentManager.getActiveDocument();
    }

    @Override
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void fireChanged() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
