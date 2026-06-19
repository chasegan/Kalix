package com.kalix.ide.components;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.MouseInputAdapter;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.dnd.DragSource;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * Adds drag-to-reorder behaviour to a {@link JTabbedPane}, with a translucent "ghost" of the
 * dragged tab tracking the cursor and a theme-coloured insertion line showing where it will land.
 * The tabs themselves do not move until the drop, so the strip stays still under the ghost.
 *
 * <p>The controller is deliberately agnostic about how the pane is built. It works entirely in the
 * tabbed pane's own coordinate space (derived from {@link MouseEvent#getLocationOnScreen()}), so it
 * does not matter whether mouse events arrive on the tab strip itself or on a custom tab component:
 *
 * <ul>
 *   <li>{@link #attachToStrip()} — for a pane with standard tabs, where the strip receives the
 *       mouse events directly.</li>
 *   <li>{@link #attachToHandle(Component)} — for a pane using custom tab components (via
 *       {@code setTabComponentAt}), which swallow the strip's mouse events; call once per tab,
 *       passing the component that should act as the drag handle.</li>
 * </ul>
 *
 * <p>The actual reorder is delegated to a {@link TabMover} so each host can keep its own backing
 * model (document order, tab list, …) as the single source of truth; this class never assumes how a
 * move is realised beyond computing the source and destination indices. A press that never crosses
 * the drag threshold is treated as a plain click and simply selects the pressed tab — which a pane
 * with custom tab components does not do natively, and which is a harmless no-op for standard tabs.
 */
public final class TabDragReorderer {

    /** Performs an actual tab move and updates any backing model. */
    @FunctionalInterface
    public interface TabMover {
        /** Move the tab at {@code fromIndex} to {@code toIndex}. */
        void moveTab(int fromIndex, int toIndex);
    }

    /** Pixels the pointer must travel before a press becomes a drag (so a click doesn't reorder). */
    private static final int DRAG_THRESHOLD = 5;

    private final JTabbedPane tabbedPane;
    private final TabMover mover;
    private final DragGhostGlassPane ghostGlassPane = new DragGhostGlassPane();

    private int draggedTabIndex = -1;
    private Point dragStartPoint = null;        // in tabbedPane coordinates
    /** Where in the dragged tab the grab started, so the ghost tracks naturally under the cursor. */
    private Point grabOffset = null;
    private boolean dragGhostActive = false;
    /** Glass pane to restore when the drag ends. */
    private Component savedGlassPane = null;
    /** The gap the tab would drop into (0..tabCount), or -1 when none. */
    private int dragTargetGap = -1;
    /** Snapshot of the dragged tab, painted as the translucent ghost. */
    private BufferedImage dragGhostImage = null;

    public TabDragReorderer(JTabbedPane tabbedPane, TabMover mover) {
        this.tabbedPane = tabbedPane;
        this.mover = mover;
    }

    /**
     * Enables dragging for a pane with standard tabs by listening on the tab strip itself.
     */
    public void attachToStrip() {
        attachTo(tabbedPane);
    }

    /**
     * Enables dragging from a custom tab component. Call once per tab, passing the component that
     * should act as the drag handle (e.g. the tab's title label). Panes that set custom tab
     * components swallow the strip's mouse events, so the handle is where the drag must originate.
     */
    public void attachToHandle(Component dragHandle) {
        attachTo(dragHandle);
    }

    private void attachTo(Component source) {
        MouseInputAdapter handler = new MouseInputAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                onPress(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                onDrag(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                onRelease(e);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (!dragGhostActive) {
                    tabbedPane.setCursor(Cursor.getDefaultCursor());
                }
            }
        };
        source.addMouseListener(handler);
        source.addMouseMotionListener(handler);
    }

    private void onPress(MouseEvent e) {
        // Only left-click starts a drag; other buttons (e.g. popup, middle-click close) pass through.
        if (e.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        Point p = pointInTabbedPane(e);
        int tabIndex = tabIndexAt(p);
        if (tabIndex >= 0) {
            Rectangle tabBounds = tabbedPane.getBoundsAt(tabIndex);
            draggedTabIndex = tabIndex;
            dragStartPoint = p;
            grabOffset = new Point(p.x - tabBounds.x, p.y - tabBounds.y);
        }
    }

    private void onDrag(MouseEvent e) {
        if (draggedTabIndex < 0 || dragStartPoint == null) {
            return;
        }
        Point p = pointInTabbedPane(e);
        if (!dragGhostActive) {
            // Begin the ghost drag only past a small threshold, so a click doesn't drag.
            if (Math.abs(p.x - dragStartPoint.x) <= DRAG_THRESHOLD
                    && Math.abs(p.y - dragStartPoint.y) <= DRAG_THRESHOLD) {
                return;
            }
            if (!activateDragGhost()) {
                return;
            }
        }
        updateDragGhost(p);
    }

    private void onRelease(MouseEvent e) {
        if (draggedTabIndex < 0) {
            return;
        }
        if (dragGhostActive) {
            // The ghost drag actually started: reorder to wherever the insertion line points.
            int dest = destinationIndex(draggedTabIndex, dragTargetGap);
            deactivateDragGhost();
            if (dest >= 0) {
                mover.moveTab(draggedTabIndex, dest);
            }
        } else {
            // A plain click: select the pressed tab (custom tab components don't do this natively).
            tabbedPane.setSelectedIndex(draggedTabIndex);
        }
        draggedTabIndex = -1;
        dragStartPoint = null;
        grabOffset = null;
        dragTargetGap = -1;
    }

    /** Converts an event's location into the tabbed pane's coordinate space, via the screen. */
    private Point pointInTabbedPane(MouseEvent e) {
        Point p = e.getLocationOnScreen();
        SwingUtilities.convertPointFromScreen(p, tabbedPane);
        return p;
    }

    /** The tab index containing {@code p} (in tabbedPane coordinates), or -1 if none. */
    private int tabIndexAt(Point p) {
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Rectangle r = tabbedPane.getBoundsAt(i);
            if (r != null && r.contains(p)) {
                return i;
            }
        }
        return -1;
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
     * Translates a drop gap (0..tabCount) into the destination index for the move, or {@code -1} if
     * the tab would not actually move. Pure function, exposed package-private for testing.
     */
    static int destinationIndex(int from, int gap) {
        if (gap < 0) {
            return -1;
        }
        int dest = gap > from ? gap - 1 : gap;
        return dest == from ? -1 : dest;
    }

    /**
     * Glass-pane overlay that paints the translucent dragged-tab ghost and the insertion line. It is
     * transparent to the mouse so the drag keeps reaching the tab strip, and repaints only the dirty
     * region as the ghost moves.
     */
    private static final class DragGhostGlassPane extends JComponent {

        /** Used only if the look and feel exposes no accent colour. */
        private static final Color FALLBACK_LINE_COLOR = new Color(0x1E88E5);
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
                g2.setColor(insertionLineColor());
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

        /**
         * The current theme's accent colour for the insertion line, resolved at paint time so it
         * tracks theme switches. Falls back through related look-and-feel keys, then to a fixed blue.
         */
        private static Color insertionLineColor() {
            Color c = UIManager.getColor("Component.focusedBorderColor");
            if (c == null) {
                c = UIManager.getColor("Component.accentColor");
            }
            if (c == null) {
                c = UIManager.getColor("Component.focusColor");
            }
            return c != null ? c : FALLBACK_LINE_COLOR;
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
