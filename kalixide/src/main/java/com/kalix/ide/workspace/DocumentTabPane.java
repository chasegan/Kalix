package com.kalix.ide.workspace;

import com.kalix.ide.components.TabDragReorderer;
import com.kalix.ide.document.DocumentLabels;
import com.kalix.ide.document.DocumentManager;
import com.kalix.ide.document.KalixDocument;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * The centre region: a tab strip with one tab per open {@link KalixDocument}.
 * Each tab's content is the document's editor — or, for a document with a
 * contextual view (the map for a model), an editor|context
 * {@link DocumentSplitView} sharing one remembered divider across all tabs.
 * This is the always-present anchor of the work area.
 *
 * <p>
 * It is a thin view over {@link DocumentManager}: it observes opened / closed /
 * active-changed events to add, remove and select tabs, and reports user-driven
 * tab selection and close requests back. A {@code syncing} guard prevents the
 * model→view→model feedback loop when selection changes programmatically.
 *
 * <p>
 * Close requests are delegated to a handler (the host checks for unsaved
 * changes before actually closing) rather than removing tabs directly, so the
 * document set stays the single source of truth.
 */
public class DocumentTabPane extends JPanel {

    private final JTabbedPane tabbedPane;
    private final DocumentManager documentManager;
    private final Consumer<KalixDocument> closeRequestHandler;
    private final ContextMenuRequestHandler contextMenuRequestHandler;
    private final Supplier<java.io.File> projectDirectorySupplier;
    private final ContextSplitCoordinator contextSplitCoordinator;

    /** Suppresses selection-change feedback while we mutate the tab strip programmatically. */
    private boolean syncing = false;

    /**
     * The root component each document contributes as its tab's content. Tab↔document
     * resolution goes through this map, never through {@code getEditor()} directly, so
     * the root can become a composite (editor | contextual view) without breaking
     * close buttons, context menus or drag-reorder.
     */
    private final Map<KalixDocument, Component> tabRoots = new IdentityHashMap<>();

    List<String> tabNames;

    /** Requests the tree's right-click context menu be shown for the given files. */
    @FunctionalInterface
    public interface ContextMenuRequestHandler {
        void showContextMenu(List<File> files, Component invoker, int x, int y);
    }

    public DocumentTabPane(
        DocumentManager documentManager,
        Consumer<KalixDocument> closeRequestHandler,
        ContextMenuRequestHandler contextMenuRequestHandler,
        Supplier<java.io.File> projectDirectorySupplier,
        ContextSplitCoordinator contextSplitCoordinator
    ) {
        super(new BorderLayout());
        this.documentManager = documentManager;
        this.closeRequestHandler = closeRequestHandler;
        this.contextMenuRequestHandler = contextMenuRequestHandler;
        this.tabNames = new ArrayList<>();
        this.projectDirectorySupplier = projectDirectorySupplier;
        this.contextSplitCoordinator = contextSplitCoordinator;

        tabbedPane = new JTabbedPane();
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        // FlatLaf-native closable tabs.
        tabbedPane.putClientProperty("JTabbedPane.tabClosable", Boolean.TRUE);
        tabbedPane.putClientProperty(
            "JTabbedPane.tabCloseCallback",
            (IntConsumer) this::onTabCloseRequested
        );
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

        // Add context menu support
        tabbedPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                // Right click
                if (e.getButton() == MouseEvent.BUTTON3) {
                    int tabIndex = tabbedPane.indexAtLocation(e.getX(), e.getY());

                    // Check if click is actually on the tab header area, not just in content area
                    Rectangle tabBounds = tabIndex >= 0 ? tabbedPane.getBoundsAt(tabIndex) : null;
                    boolean clickOnTabHeader = tabBounds != null && tabBounds.contains(e.getX(), e.getY());

                    if (clickOnTabHeader) {
                        if (e.getID() == MouseEvent.MOUSE_RELEASED) {
                            KalixDocument document = documentAt(tabIndex);
                            if (document == null) {
                                return;
                            }
                            File file = document.getFile();
                            // QOL: Change active document
                            documentManager.setActiveDocument(document);
                            // Show context menu
                            if (file != null) { // unsaved documents have no tree entry to show a menu for
                                contextMenuRequestHandler.showContextMenu(
                                    List.of(file), tabbedPane, e.getX(), e.getY());
                            }
                        }
                    }
                }
            }
        });

        // Add tab drag-and-drop support
        addTabDragAndDrop();

        documentManager.addDocumentOpenedListener(this::onDocumentOpened);
        documentManager.addDocumentClosedListener(this::onDocumentClosed);
        documentManager.addActiveDocumentChangeListener(this::onActiveDocumentChanged);
    }

    /**
     * Adds ghost-style drag-and-drop support for reordering tabs. These are standard tabs, so the
     * reorderer listens on the strip itself.
     */
    private void addTabDragAndDrop() {
        new TabDragReorderer(tabbedPane, this::moveTab).attachToStrip();
    }

    /**
     * Moves a tab from one position to another and updates the document order.
     */
    private void moveTab(int fromIndex, int toIndex) {
        if (fromIndex == toIndex || fromIndex < 0 || toIndex < 0 ||
            fromIndex >= tabbedPane.getTabCount() || toIndex >= tabbedPane.getTabCount()) {
            return;
        }

        syncing = true;
        try {
            // Get tab info before removing
            String title = tabbedPane.getTitleAt(fromIndex);
            Component component = tabbedPane.getComponentAt(fromIndex);
            String tooltip = tabbedPane.getToolTipTextAt(fromIndex);

            // Remove and re-insert the tab
            tabbedPane.removeTabAt(fromIndex);
            tabbedPane.insertTab(title, null, component, tooltip, toIndex);

            // Select the moved tab
            tabbedPane.setSelectedIndex(toIndex);

            // Update the document order in DocumentManager
            documentManager.moveDocument(fromIndex, toIndex);
        } finally {
            syncing = false;
            tabbedPane.setCursor(Cursor.getDefaultCursor());
        }
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
        // A backing-file change (e.g. Untitled -> saved, or Save As) can change collisions
        // with other open tabs, so names must be rebuilt rather than reusing the cached ones.
        rebuildTabNames();
        refreshTabs();
        tabbedPane.setToolTipTextAt(index, tabTooltip(document));
    }

    // --- DocumentManager events ---

    private void onDocumentOpened(KalixDocument document) {
        syncing = true;
        try {
            // May add a conflict
            this.rebuildTabNames();
            Component root = tabRootFor(document);
            tabRoots.put(document, root);
            tabbedPane.addTab(tabTitle(document), root);
            int index = indexOf(document);
            tabbedPane.setToolTipTextAt(index, tabTooltip(document));
            this.refreshTabs();
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
            // May remove conflict
            this.rebuildTabNames();
            tabbedPane.removeTabAt(index);
            tabRoots.remove(document);
            this.refreshTabs();
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
        focusEditorOf(document);
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

    /**
     * The component a document's tab shows: the bare editor for a document with no
     * contextual view, or an editor|context {@link DocumentSplitView} sharing the
     * one remembered divider for a document that has one (the map for a model).
     * Built once per document and stored in {@link #tabRoots}; tab↔document
     * resolution never assumes the root is the editor.
     */
    private Component tabRootFor(KalixDocument document) {
        Component contextView = document.getContextView();
        if (contextView == null) {
            return document.getEditor();
        }
        return new DocumentSplitView(document.getEditor(), contextView, contextSplitCoordinator);
    }

    // --- Contextual view (the region inside each tab) ---

    /**
     * Collapses or expands the contextual-view region. The shared state changes for
     * every tab; the active tab's split is re-laid out immediately, hidden tabs
     * catch up when next shown (see {@link DocumentSplitView}).
     */
    public void setContextViewCollapsed(boolean collapsed) {
        contextSplitCoordinator.setCollapsed(collapsed);
        Component root = tabRoots.get(documentManager.getActiveDocument());
        if (root instanceof DocumentSplitView view) {
            view.applySharedLayout();
        }
    }

    public boolean isContextViewCollapsed() {
        return contextSplitCoordinator.isCollapsed();
    }

    public void toggleContextView() {
        setContextViewCollapsed(!isContextViewCollapsed());
    }

    /**
     * Puts keyboard focus in the newly active document's editor. With composite tab
     * content the tab pane would otherwise leave focus on the tab header (or hand it
     * to the composite's first focusable child), so typing after a tab switch must
     * be routed explicitly. Deferred so it runs after the selection settles; skipped
     * if the active document changed again in the meantime.
     */
    private void focusEditorOf(KalixDocument document) {
        SwingUtilities.invokeLater(() -> {
            // Leave focus alone while a menu is open: the tab context-menu path
            // activates the document and then shows its popup, so this deferred focus
            // would otherwise land under the open menu and degrade its keyboard handling.
            if (MenuSelectionManager.defaultManager().getSelectedPath().length > 0) {
                return;
            }
            if (documentManager.getActiveDocument() == document) {
                document.getEditor().getTextArea().requestFocusInWindow();
            }
        });
    }

    int indexOf(KalixDocument document) {
        Component root = tabRoots.get(document);
        return root != null ? tabbedPane.indexOfComponent(root) : -1;
    }

    KalixDocument documentAt(int tabIndex) {
        Component content = tabbedPane.getComponentAt(tabIndex);
        for (KalixDocument document : documentManager.getDocuments()) {
            if (tabRoots.get(document) == content) {
                return document;
            }
        }
        return null;
    }

    /** The underlying tab strip — package-private, for tests. */
    JTabbedPane getTabbedPane() {
        return tabbedPane;
    }

    private String tabTitle(KalixDocument document) {
        int index = this.indexOf(document);
        String tabName = (index >= 0 && index < tabNames.size())
            ? tabNames.get(index)
            : document.getDisplayName();
        return (document.isDirty() ? "● " : "") + tabName;
    }

    private static String tabTooltip(KalixDocument document) {
        return document.getFile() != null
            ? document.getFile().getAbsolutePath()
            : document.getDisplayName();
    }

    /**
     * Full rebuild of tab names from the {@link #documentManager}, then refreshes every
     * tab's title.
     *
     * <p>Naming — including how documents sharing a display name are disambiguated — is
     * delegated to {@link DocumentLabels}, the single resolver every surface that names a
     * model goes through. Keeping the algorithm here as well would let the tab strip and
     * the Optimiser's model selector drift into labelling the same two files differently
     * (see {@code manifestos/identity-and-labels.md} §2.3).</p>
     */
    private void rebuildTabNames() {
        this.tabNames = DocumentLabels.labelsFor(
            documentManager.getDocuments(), projectDirectorySupplier.get());
    }

    private void refreshTabs() {
        List<KalixDocument> documents = documentManager.getDocuments();
        for (int i = 0; i < documents.size(); i++) {
            tabbedPane.setTitleAt(i, tabTitle(documents.get(i)));
        }
    }
}
