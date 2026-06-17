package com.kalix.ide.workspace;

import com.kalix.ide.document.DocumentManager;
import com.kalix.ide.document.KalixDocument;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.dnd.DragSource;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
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

    /** Tab drag-and-drop state */
    private int draggedTabIndex = -1;
    private Point dragStartPoint = null;
    /** Where in the dragged tab the grab started, so the ghost tracks naturally under the cursor. */
    private Point grabOffset = null;
    /** Glass-pane overlay that paints the translucent dragged-tab ghost and the insertion line. */
    private final DragGhostGlassPane ghostGlassPane = new DragGhostGlassPane();
    /** True once the drag threshold is crossed and the ghost overlay is installed. */
    private boolean dragGhostActive = false;
    /** Glass pane to restore when the drag ends. */
    private Component savedGlassPane = null;
    /** The gap the tab would drop into (0..tabCount), or -1 when none. */
    private int dragTargetGap = -1;
    /** Snapshot of the dragged tab, painted as the translucent ghost. */
    private BufferedImage dragGhostImage = null;

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

        // Add tab drag-and-drop support
        addTabDragAndDrop();

        documentManager.addDocumentOpenedListener(this::onDocumentOpened);
        documentManager.addDocumentClosedListener(this::onDocumentClosed);
        documentManager.addActiveDocumentChangeListener(this::onActiveDocumentChanged);
    }

    /**
     * Adds drag-and-drop support for reordering tabs.
     */
    private void addTabDragAndDrop() {
        tabbedPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // Only start drag on left-click
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }

                int tabIndex = tabbedPane.indexAtLocation(e.getX(), e.getY());
                if (tabIndex >= 0) {
                    Rectangle tabBounds = tabbedPane.getBoundsAt(tabIndex);
                    if (tabBounds != null && tabBounds.contains(e.getX(), e.getY())) {
                        draggedTabIndex = tabIndex;
                        dragStartPoint = e.getPoint();
                        grabOffset = new Point(e.getX() - tabBounds.x, e.getY() - tabBounds.y);
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (draggedTabIndex >= 0) {
                    // Only reorder if the ghost drag actually started (threshold crossed); a plain
                    // click leaves the tabs untouched and just selects, as before.
                    if (dragGhostActive) {
                        int dest = destinationIndex(draggedTabIndex, dragTargetGap);
                        deactivateDragGhost();
                        if (dest >= 0) {
                            moveTab(draggedTabIndex, dest);
                        }
                    }
                    draggedTabIndex = -1;
                    dragStartPoint = null;
                    grabOffset = null;
                    dragTargetGap = -1;
                }
            }
        });

        tabbedPane.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (draggedTabIndex < 0 || dragStartPoint == null) {
                    return;
                }
                if (!dragGhostActive) {
                    // Begin the ghost drag only past a small threshold, so a click doesn't drag.
                    if (Math.abs(e.getX() - dragStartPoint.x) <= 5
                            && Math.abs(e.getY() - dragStartPoint.y) <= 5) {
                        return;
                    }
                    if (!activateDragGhost()) {
                        return;
                    }
                }
                updateDragGhost(e.getPoint());
            }
        });

        tabbedPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                if (!dragGhostActive) {
                    tabbedPane.setCursor(Cursor.getDefaultCursor());
                }
            }
        });
    }

    /**
     * Installs the glass-pane ghost overlay and snapshots the dragged tab. Returns {@code false}
     * (the drag stays inert) if there is no root pane to host the overlay or the tab can't be
     * captured.
     */
    private boolean activateDragGhost() {
        JRootPane rootPane = SwingUtilities.getRootPane(tabbedPane);
        if (rootPane == null) {
            return false;
        }
        dragGhostImage = captureTabImage(draggedTabIndex);
        if (dragGhostImage == null) {
            return false;
        }
        savedGlassPane = rootPane.getGlassPane();
        rootPane.setGlassPane(ghostGlassPane);
        ghostGlassPane.setVisible(true);
        tabbedPane.setCursor(DragSource.DefaultMoveDrop);
        dragGhostActive = true;
        return true;
    }

    /** Removes the ghost overlay and restores the previous glass pane and cursor. */
    private void deactivateDragGhost() {
        ghostGlassPane.clear();
        ghostGlassPane.setVisible(false);
        JRootPane rootPane = SwingUtilities.getRootPane(tabbedPane);
        if (rootPane != null && savedGlassPane != null) {
            rootPane.setGlassPane(savedGlassPane);
        }
        savedGlassPane = null;
        dragGhostImage = null;
        dragGhostActive = false;
        tabbedPane.setCursor(Cursor.getDefaultCursor());
    }

    /** Recomputes the drop gap and repaints the ghost + insertion line for the current pointer. */
    private void updateDragGhost(Point pointInTabbedPane) {
        dragTargetGap = targetGapIndex(pointInTabbedPane);

        // Ghost top-left, tracking under the cursor by the original grab offset.
        Point ghostTopLeft = new Point(pointInTabbedPane.x - grabOffset.x, pointInTabbedPane.y - grabOffset.y);
        Point ghostInGlass = SwingUtilities.convertPoint(tabbedPane, ghostTopLeft, ghostGlassPane);

        // Insertion line: left edge of the target tab, or right edge of the last tab at the end.
        int count = tabbedPane.getTabCount();
        Rectangle ref = dragTargetGap < count
            ? tabbedPane.getBoundsAt(dragTargetGap)
            : tabbedPane.getBoundsAt(count - 1);
        if (ref == null) {
            ghostGlassPane.update(dragGhostImage, ghostInGlass, null, 0);
            return;
        }
        int lineXInTab = dragTargetGap < count ? ref.x : ref.x + ref.width;
        Point lineTopInGlass = SwingUtilities.convertPoint(tabbedPane, new Point(lineXInTab, ref.y), ghostGlassPane);
        ghostGlassPane.update(dragGhostImage, ghostInGlass, lineTopInGlass, ref.height);
    }

    /** Captures the visual of tab {@code index} as a standalone image, or {@code null} if unavailable. */
    private BufferedImage captureTabImage(int index) {
        Rectangle r = tabbedPane.getBoundsAt(index);
        if (r == null || r.x < 0 || r.y < 0
                || tabbedPane.getWidth() <= 0 || tabbedPane.getHeight() <= 0) {
            return null;
        }
        BufferedImage full = new BufferedImage(
            tabbedPane.getWidth(), tabbedPane.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = full.createGraphics();
        tabbedPane.paint(g);
        g.dispose();
        // Clamp to the image bounds: scroll layout can report a tab partly past the strip edge.
        int w = Math.min(r.width, full.getWidth() - r.x);
        int h = Math.min(r.height, full.getHeight() - r.y);
        if (w <= 0 || h <= 0) {
            return null;
        }
        return full.getSubimage(r.x, r.y, w, h);
    }

    /** The gap index (0..tabCount) the pointer is over, decided by tab midpoints. */
    private int targetGapIndex(Point pt) {
        int count = tabbedPane.getTabCount();
        for (int i = 0; i < count; i++) {
            Rectangle r = tabbedPane.getBoundsAt(i);
            if (r == null) {
                continue;
            }
            if (pt.x < r.x + r.width / 2) {
                return i;
            }
        }
        return count;
    }

    /**
     * Translates a drop gap (0..tabCount) into the destination index for {@link #moveTab}, or
     * {@code -1} if the tab would not actually move.
     */
    private int destinationIndex(int from, int gap) {
        if (gap < 0) {
            return -1;
        }
        int dest = gap > from ? gap - 1 : gap;
        return dest == from ? -1 : dest;
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

    /**
     * A click-through glass pane that paints the translucent dragged-tab ghost following the
     * cursor, plus a vertical insertion line at the drop gap. {@code contains()} returns
     * {@code false} so the underlying tab strip keeps receiving the drag events that drive it,
     * and repaints are confined to the affected region so the rest of the window isn't redrawn
     * on every motion event.
     */
    private static final class DragGhostGlassPane extends JComponent {

        private static final Color LINE_COLOR = new Color(0x1E88E5);
        private static final float GHOST_ALPHA = 0.6f;

        private BufferedImage ghost;
        private Point ghostLocation;
        private Point lineTop;     // null when there is no insertion line
        private int lineHeight;
        private Rectangle lastDirty;

        DragGhostGlassPane() {
            setOpaque(false);
        }

        void update(BufferedImage ghost, Point ghostLocation, Point lineTop, int lineHeight) {
            this.ghost = ghost;
            this.ghostLocation = ghostLocation;
            this.lineTop = lineTop;
            this.lineHeight = lineHeight;
            Rectangle newDirty = dirtyBounds();
            repaintRegion(union(lastDirty, newDirty));
            lastDirty = newDirty;
        }

        void clear() {
            ghost = null;
            ghostLocation = null;
            lineTop = null;
            repaintRegion(lastDirty);
            lastDirty = null;
        }

        @Override
        public boolean contains(int x, int y) {
            return false; // transparent to the mouse so the drag keeps reaching the tab strip
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (lineTop != null) {
                int x = lineTop.x;
                int y0 = lineTop.y;
                int y1 = lineTop.y + lineHeight;
                g2.setColor(LINE_COLOR);
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(x, y0, x, y1);
                g2.fillPolygon(new int[]{x - 4, x + 4, x}, new int[]{y0, y0, y0 + 5}, 3);
                g2.fillPolygon(new int[]{x - 4, x + 4, x}, new int[]{y1, y1, y1 - 5}, 3);
            }

            if (ghost != null && ghostLocation != null) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, GHOST_ALPHA));
                g2.drawImage(ghost, ghostLocation.x, ghostLocation.y, null);
            }
            g2.dispose();
        }

        /** Bounds of what we currently draw (ghost + line), padded, or null if nothing. */
        private Rectangle dirtyBounds() {
            Rectangle r = null;
            if (ghost != null && ghostLocation != null) {
                r = new Rectangle(ghostLocation.x, ghostLocation.y, ghost.getWidth(), ghost.getHeight());
            }
            if (lineTop != null) {
                Rectangle lr = new Rectangle(lineTop.x - 6, lineTop.y - 6, 12, lineHeight + 12);
                r = (r == null) ? lr : r.union(lr);
            }
            if (r != null) {
                r.grow(2, 2);
            }
            return r;
        }

        private void repaintRegion(Rectangle r) {
            if (r != null) {
                repaint(r);
            }
        }

        private static Rectangle union(Rectangle a, Rectangle b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return a.union(b);
        }
    }
}
