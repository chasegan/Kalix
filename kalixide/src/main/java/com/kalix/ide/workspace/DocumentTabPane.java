package com.kalix.ide.workspace;

import com.kalix.ide.components.TabDragReorderer;
import com.kalix.ide.document.DocumentManager;
import com.kalix.ide.document.KalixDocument;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * The centre region: a tab strip with one tab per open {@link KalixDocument},
 * each tab's content being that document's editor. This is the always-present
 * anchor of the work area.
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

    /** Suppresses selection-change feedback while we mutate the tab strip programmatically. */
    private boolean syncing = false;

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
        Supplier<java.io.File> projectDirectorySupplier
    ) {
        super(new BorderLayout());
        this.documentManager = documentManager;
        this.closeRequestHandler = closeRequestHandler;
        this.contextMenuRequestHandler = contextMenuRequestHandler;
        this.tabNames = new ArrayList<String>();
        this.projectDirectorySupplier = projectDirectorySupplier;

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
            tabbedPane.addTab(tabTitle(document), document.getEditor());
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
     * Full rebuild of tab names from the {@link #documentManager}.
     * Updates {@link #tabNames} and refreshes every tab's title.
     * <p>
     * Documents sharing a plain display name (e.g. two {@code model.ini} files in different
     * folders) are disambiguated by prefixing enough of their parent directory names to make
     * them unique, without walking above the open project root.
     */
    private void rebuildTabNames() {
        List<KalixDocument> documents = documentManager.getDocuments();
        List<String> displayNames = new ArrayList<>(documents.size());
        for (KalixDocument document : documents) {
            displayNames.add(document.getDisplayName());
        }

        HashMap<String, List<Integer>> collisions = new HashMap<>();
        for (int index = 0; index < displayNames.size(); index++) {
            collisions.computeIfAbsent(displayNames.get(index), k -> new ArrayList<>())
                      .add(index);
        }

        File root = projectDirectorySupplier.get();
        for (List<Integer> collisionIndices : collisions.values()) {
            if (collisionIndices.size() == 1) {
                continue; // no collision to resolve
            }
            disambiguate(documents, displayNames, collisionIndices, root);
        }
        this.tabNames = displayNames;
    }

    private void refreshTabs() {
        List<KalixDocument> documents = documentManager.getDocuments();
        for (int i = 0; i < documents.size(); i++) {
            tabbedPane.setTitleAt(i, tabTitle(documents.get(i)));
        }
    }

    /**
     * Resolves a single group of colliding tab names in place, by walking up each document's
     * path (nearest ancestor first) and prefixing progressively more of it until the names in
     * the group are unique, or the project root / an undated document stops further progress.
     */
    private void disambiguate(
        List<KalixDocument> documents,
        List<String> names,
        List<Integer> collisionIndices,
        File root
    ) {
        HashMap<Integer, List<String>> ancestors = new HashMap<>();
        int maxDepth = 0;
        for (int index : collisionIndices) {
            List<String> segments = ancestorSegments(documents.get(index).getFile(), root);
            ancestors.put(index, segments);
            maxDepth = Math.max(maxDepth, segments.size());
        }

        int depth = 0;
        while (true) {
            HashMap<String, Integer> counts = new HashMap<>();
            for (int index : collisionIndices) {
                String candidate = disambiguatedName(documents.get(index), ancestors.get(index), depth);
                counts.merge(candidate, 1, Integer::sum);
            }
            boolean allUnique = counts.values().stream().allMatch(count -> count == 1);
            if (allUnique || depth >= maxDepth) {
                for (int index : collisionIndices) {
                    names.set(index, disambiguatedName(documents.get(index), ancestors.get(index), depth));
                }
                return;
            }
            depth++;
        }
    }

    /** Ancestor directory names of {@code file}, nearest first, stopping at (excluding) {@code root}. */
    private static List<String> ancestorSegments(File file, File root) {
        List<String> segments = new ArrayList<>();
        if (file == null) {
            return segments; // Undated documents (e.g. "Untitled") cannot be disambiguated.
        }
        File parent = file.getParentFile();
        while (parent != null && !parent.equals(root)) {
            String name = parent.getName();
            segments.add(name.isEmpty() ? parent.getPath() : name);
            parent = parent.getParentFile();
        }
        return segments;
    }

    /** The tab name for {@code document}, prefixed with up to {@code depth} ancestor segments. */
    private static String disambiguatedName(KalixDocument document, List<String> segments, int depth) {
        String base = document.getDisplayName();
        int use = Math.min(depth, segments.size());
        if (use == 0) {
            return base;
        }
        StringBuilder name = new StringBuilder();
        for (int i = use - 1; i >= 0; i--) {
            name.append(segments.get(i)).append('/');
        }
        return name.append(base).toString();
    }
}
