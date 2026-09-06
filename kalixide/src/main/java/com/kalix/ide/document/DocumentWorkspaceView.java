package com.kalix.ide.document;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The {@link WorkspaceView} backed by the open documents.
 *
 * <p>A thin adapter: {@link KalixDocument} already <em>is</em> a {@link OpenModel},
 * so this only forwards the document set and folds the three
 * {@link DocumentManager} events (opened / closed / active-changed) into the single
 * "something changed" signal auxiliary windows care about.</p>
 */
public class DocumentWorkspaceView implements WorkspaceView {

    private final DocumentManager documentManager;
    private final Supplier<File> projectRootSupplier;
    private final List<Runnable> listeners = new ArrayList<>();

    /**
     * @param projectRootSupplier the open project folder, read on demand (the project
     *                            tree it comes from is built after this registry)
     */
    public DocumentWorkspaceView(DocumentManager documentManager,
                                Supplier<File> projectRootSupplier) {
        this.documentManager = documentManager;
        this.projectRootSupplier = projectRootSupplier;
        documentManager.addDocumentOpenedListener(doc -> fireChanged());
        documentManager.addDocumentClosedListener(doc -> fireChanged());
        documentManager.addActiveDocumentChangeListener(doc -> fireChanged());
    }

    @Override
    public List<? extends OpenModel> openModels() {
        // Model documents only: auxiliary windows (the Optimiser's model selector,
        // future Run Manager listings) must never see a text/data tab as a model.
        List<KalixDocument> models = new ArrayList<>();
        for (KalixDocument document : documentManager.getDocuments()) {
            if (document.isModel()) {
                models.add(document);
            }
        }
        return models;
    }

    @Override
    public OpenModel activeModel() {
        KalixDocument active = documentManager.getActiveDocument();
        return active != null && active.isModel() ? active : null;
    }

    @Override
    public File projectRoot() {
        return projectRootSupplier != null ? projectRootSupplier.get() : null;
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
