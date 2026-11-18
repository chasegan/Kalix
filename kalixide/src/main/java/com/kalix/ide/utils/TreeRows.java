package com.kalix.ide.utils;

import javax.swing.JTree;
import javax.swing.tree.TreePath;
import java.awt.Rectangle;

/**
 * Finds and selects tree rows across their full width, matching the full-width hover and
 * selection painting. {@link JTree#getPathForLocation} and {@link JTree#getRowForLocation}
 * only hit the renderer's label, so a click beside a short label misses its row.
 */
public final class TreeRows {

    private TreeRows() {
    }

    /**
     * Returns the path of the row at {@code y}, whatever the x, or null above the first row
     * and below the last.
     */
    public static TreePath fullWidthPathAt(JTree tree, int y) {
        TreePath path = tree.getClosestPathForLocation(0, y);
        Rectangle bounds = path == null ? null : tree.getPathBounds(path);
        return bounds != null && y >= bounds.y && y < bounds.y + bounds.height ? path : null;
    }

    /**
     * Updates the selection for a right-click at {@code y} and returns the path hit, or null.
     * A row outside the selection becomes the selection. A row inside a multi-selection keeps
     * it, so the menu acts on all of it. Empty space clears the selection.
     */
    public static TreePath selectForPopup(JTree tree, int y) {
        TreePath path = fullWidthPathAt(tree, y);
        if (path == null) {
            tree.clearSelection();
        } else if (!tree.isPathSelected(path)) {
            tree.setSelectionPath(path);
        }
        return path;
    }

    /**
     * Makes the row at {@code y} the only selected row, for a menu that acts on one node,
     * and returns its path. Empty space returns null and leaves the selection alone.
     */
    public static TreePath selectOnlyForPopup(JTree tree, int y) {
        TreePath path = fullWidthPathAt(tree, y);
        if (path != null) {
            tree.setSelectionPath(path);
        }
        return path;
    }
}
