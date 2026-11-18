package com.kalix.ide.components;

import com.kalix.ide.utils.TreeRows;

import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.LayerUI;
import javax.swing.tree.TreePath;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;

/**
 * Paints a hover highlight, a translucent wash across the full width of the tree row under
 * the pointer, for a tree in a scroll pane. A helper cannot override the tree's
 * {@code paintComponent}, so a {@link JLayer} paints the wash. Show the layer that
 * {@link #layerAround} returns in place of the scroll pane.
 *
 * <p>The layer goes around the scroll pane, not the tree. The look and feel reads the
 * viewport's view directly to decide whether the scroll pane holds the focus, so a layer
 * between the two would cost the scroll pane its focus border.
 */
public final class TreeHoverHighlight extends LayerUI<JScrollPane> {

    // Subtle enough that the row text stays legible.
    private static final int HOVER_ALPHA = 40;

    private final JTree tree;
    private final JViewport viewport;
    // Where the pointer is, in viewport coordinates; null while it is off the tree. The
    // viewport stays put while the tree scrolls beneath it.
    private Point pointer;
    // The row last highlighted, by path: a row index costs a linear search to find. Re-derived
    // from `pointer` before every paint, so a row gone stale after a scroll, expand, collapse
    // or model change is never drawn.
    private TreePath highlightedPath;
    // Where that row was, in tree coordinates: a path gone from the tree has no bounds left
    // to repaint by.
    private Rectangle highlightedBounds;
    // Looked up once per look and feel, not on every paint.
    private Color hoverColor = themeHoverColor();

    private TreeHoverHighlight(JTree tree, JViewport viewport) {
        this.tree = tree;
        this.viewport = viewport;
    }

    /**
     * Adds the wash to the tree that {@code scrollPane} shows, and returns the layer to put in
     * the layout in the scroll pane's place. Nothing is painted until that layer is in the
     * layout.
     *
     * @throws IllegalArgumentException if the scroll pane's view is not a {@link JTree}
     */
    public static JLayer<JScrollPane> layerAround(JScrollPane scrollPane) {
        if (!(scrollPane.getViewport().getView() instanceof JTree tree)) {
            throw new IllegalArgumentException("The scroll pane must show a JTree");
        }
        TreeHoverHighlight highlight = new TreeHoverHighlight(tree, scrollPane.getViewport());

        MouseAdapter pointerTracker = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                highlight.pointerMoved(e.getPoint());
            }

            // A drag sends no mouseMoved, and keeps reporting to the tree after leaving it.
            @Override
            public void mouseDragged(MouseEvent e) {
                boolean overTree = tree.getVisibleRect().contains(e.getPoint());
                highlight.pointerMoved(overTree ? e.getPoint() : null);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                highlight.pointerMoved(null);
            }
        };
        tree.addMouseMotionListener(pointerTracker);
        tree.addMouseListener(pointerTracker);
        // Scrolling moves the tree under a still pointer, so no mouse event arrives.
        highlight.viewport.addChangeListener(e -> highlight.refresh());
        return new JLayer<>(scrollPane, highlight);
    }

    @Override
    public void updateUI(JLayer<? extends JScrollPane> layer) {
        super.updateUI(layer);
        hoverColor = themeHoverColor();
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        super.paint(g, c);
        // This paint may cover only a strip, so a row change seen here still needs its rows
        // repainted, not just recorded.
        refresh();
        TreePath path = highlightedPath;
        if (path == null || tree.isPathSelected(path)) {
            return;
        }
        Rectangle bounds = highlightedBounds;
        // The wash spans the viewport and is clipped to it, clear of scroll bars and border.
        Rectangle visible = SwingUtilities.convertRectangle(
            viewport, new Rectangle(viewport.getSize()), c);
        int y = SwingUtilities.convertPoint(tree, 0, bounds.y, c).y;
        Graphics wash = g.create();
        wash.clipRect(visible.x, visible.y, visible.width, visible.height);
        wash.setColor(hoverColor);
        wash.fillRect(visible.x, y, visible.width, bounds.height);
        wash.dispose();
    }

    private void pointerMoved(Point inTree) {
        pointer = inTree == null ? null : SwingUtilities.convertPoint(tree, inTree, viewport);
        refresh();
    }

    /** Repaints only the rows the wash left and entered. */
    private void refresh() {
        TreePath path = pathUnderPointer();
        Rectangle bounds = path == null ? null : tree.getPathBounds(path);
        if (!Objects.equals(path, highlightedPath) || !Objects.equals(bounds, highlightedBounds)) {
            repaintRow(highlightedBounds);
            repaintRow(bounds);
            highlightedPath = path;
            highlightedBounds = bounds;
        }
    }

    private TreePath pathUnderPointer() {
        if (pointer == null) {
            return null;
        }
        return TreeRows.fullWidthPathAt(tree, SwingUtilities.convertPoint(viewport, pointer, tree).y);
    }

    private void repaintRow(Rectangle bounds) {
        if (bounds != null) {
            tree.repaint(0, bounds.y, tree.getWidth(), bounds.height);
        }
    }

    private static Color themeHoverColor() {
        Color base = UIManager.getColor("Tree.selectionBackground");
        if (base == null) {
            base = Color.GRAY;
        }
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), HOVER_ALPHA);
    }
}
