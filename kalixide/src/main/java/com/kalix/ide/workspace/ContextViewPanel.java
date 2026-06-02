package com.kalix.ide.workspace;

import com.kalix.ide.document.DocumentManager;
import com.kalix.ide.document.KalixDocument;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;

/**
 * The right-hand contextual view: a pure projection of the active tab. It shows the
 * active document's {@linkplain KalixDocument#getContextView() contextual view} — the
 * map for a model document — and an empty placeholder when there is none.
 *
 * <p>There is no independent "active model" state here; what is shown is purely a
 * function of the active document (see {@code docs/multi-document-architecture.md}).
 */
public class ContextViewPanel extends JPanel {

    private final JPanel placeholder = new JPanel();
    private Component current;

    public ContextViewPanel(DocumentManager documentManager) {
        super(new BorderLayout());
        showDocument(documentManager.getActiveDocument());
        documentManager.addActiveDocumentChangeListener(this::showDocument);
    }

    /**
     * Displays the contextual view of the given document, or the placeholder if the
     * document is {@code null} or has no contextual view.
     */
    public void showDocument(KalixDocument document) {
        Component next = document != null ? document.getContextView() : null;
        if (next == null) {
            next = placeholder;
        }
        if (next == current) {
            return;
        }
        removeAll();
        current = next;
        add(current, BorderLayout.CENTER);
        revalidate();
        repaint();
    }
}
