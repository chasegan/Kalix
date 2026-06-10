package com.kalix.ide.workspace;

import com.kalix.ide.document.DocumentManager;
import com.kalix.ide.document.KalixDocument;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The centre region: a tab strip with one tab per open {@link KalixDocument}, each tab's
 * content being that document's editor. This is the always-present anchor of the work area.
 *
 * <p>It is a thin view over {@link DocumentManager}: it observes opened / closed /
 * active-changed events to add, remove and select tabs, and reports user-driven tab
 * selection and close requests back. A {@code syncing} guard prevents the
 * model→view→model feedback loop when selection changes programmatically.
 *
 * <p>Close requests are delegated to a handler (the host checks for unsaved changes before
 * actually closing) rather than removing tabs directly, so the document set stays the
 * single source of truth.
 */
public class DocumentTabPane extends JPanel {

    private final JTabbedPane tabbedPane;
    private final DocumentManager documentManager;
    private final Consumer<KalixDocument> closeRequestHandler;

    /** Suppresses selection-change feedback while we mutate the tab strip programmatically. */
    private boolean syncing = false;

    public DocumentTabPane(DocumentManager documentManager, Consumer<KalixDocument> closeRequestHandler) {
        super(new BorderLayout());
        this.documentManager = documentManager;
        this.closeRequestHandler = closeRequestHandler;

        tabbedPane = new JTabbedPane();
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        // FlatLaf-native closable tabs.
        tabbedPane.putClientProperty("JTabbedPane.tabClosable", Boolean.TRUE);
        tabbedPane.putClientProperty("JTabbedPane.tabCloseCallback",
            (IntConsumer) this::onTabCloseRequested);
        add(tabbedPane, BorderLayout.CENTER);

        tabbedPane.addChangeListener(e -> onTabSelected());

        // Add middle-click support for closing tabs
        tabbedPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON2) {
                    handleMiddleClick(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON2) {
                    handleMiddleClick(e);
                }
            }

            private void handleMiddleClick(MouseEvent e) {
                int tabIndex = tabbedPane.indexAtLocation(e.getX(), e.getY());

                // Check if click is actually on the tab header area, not just in content area
                Rectangle tabBounds = tabIndex >= 0 ? tabbedPane.getBoundsAt(tabIndex) : null;
                boolean clickOnTabHeader = tabBounds != null && tabBounds.contains(e.getX(), e.getY());

                if (clickOnTabHeader) {
                    // Consume event early to prevent paste
                    e.consume();

                    // Only close on release, not press (standard button behavior)
                    if (e.getID() == MouseEvent.MOUSE_RELEASED) {
                        KalixDocument document = documentAt(tabIndex);
                        if (document != null) {
                            onTabCloseRequested(tabIndex);
                        }
                    }
                }
            }
        });

        documentManager.addDocumentOpenedListener(this::onDocumentOpened);
        documentManager.addDocumentClosedListener(this::onDocumentClosed);
        documentManager.addActiveDocumentChangeListener(this::onActiveDocumentChanged);
    }

    /**
     * Updates the tab title (dirty marker + display name) and tooltip for a document.
     * Called by the host when the document's dirty state or backing file changes.
     */
    public void refreshTab(KalixDocument document) {
        int index = indexOf(document);
        if (index < 0) {
            return;
        }
        tabbedPane.setTitleAt(index, tabTitle(document));
        tabbedPane.setToolTipTextAt(index, tabTooltip(document));
    }

    // --- DocumentManager events ---

    private void onDocumentOpened(KalixDocument document) {
        syncing = true;
        try {
            tabbedPane.addTab(tabTitle(document), document.getEditor());
            int index = indexOf(document);
            tabbedPane.setToolTipTextAt(index, tabTooltip(document));
        } finally {
            syncing = false;
        }
    }

    private void onDocumentClosed(KalixDocument document) {
        int index = indexOf(document);
        if (index < 0) {
            return;
        }
        syncing = true;
        try {
            tabbedPane.removeTabAt(index);
        } finally {
            syncing = false;
        }
    }

    private void onActiveDocumentChanged(KalixDocument document) {
        if (document == null) {
            return;
        }
        int index = indexOf(document);
        if (index >= 0 && tabbedPane.getSelectedIndex() != index) {
            syncing = true;
            try {
                tabbedPane.setSelectedIndex(index);
            } finally {
                syncing = false;
            }
        }
    }

    // --- User-driven tab interactions ---

    private void onTabSelected() {
        if (syncing) {
            return;
        }
        int index = tabbedPane.getSelectedIndex();
        if (index < 0) {
            return;
        }
        KalixDocument document = documentAt(index);
        if (document != null) {
            documentManager.setActiveDocument(document);
        }
    }

    private void onTabCloseRequested(int index) {
        KalixDocument document = documentAt(index);
        if (document != null) {
            closeRequestHandler.accept(document);
        }
    }

    // --- Helpers ---

    private int indexOf(KalixDocument document) {
        return tabbedPane.indexOfComponent(document.getEditor());
    }

    private KalixDocument documentAt(int tabIndex) {
        Component content = tabbedPane.getComponentAt(tabIndex);
        for (KalixDocument document : documentManager.getDocuments()) {
            if (document.getEditor() == content) {
                return document;
            }
        }
        return null;
    }

    private static String tabTitle(KalixDocument document) {
        return (document.isDirty() ? "● " : "") + document.getDisplayName();
    }

    private static String tabTooltip(KalixDocument document) {
        return document.getFile() != null
            ? document.getFile().getAbsolutePath()
            : document.getDisplayName();
    }
}
