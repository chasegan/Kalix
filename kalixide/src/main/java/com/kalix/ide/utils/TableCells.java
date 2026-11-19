package com.kalix.ide.utils;

import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.EventQueue;
import java.awt.Point;
import java.awt.event.MouseEvent;

/**
 * Selects table cells for right-click menus. As with {@link TreeRows#selectForPopup}, a click
 * inside the selection keeps it. In a tree, empty space clears the selection. Here, a click
 * below the last row leaves the selection alone.
 */
public final class TableCells {

    private TableCells() {
    }

    /**
     * Makes {@code menu} select the right-clicked cell as the menu opens, so its items act on
     * the cell under the cursor. {@code menu} must be the table's component popup menu. A menu
     * opened from the keyboard acts on the current selection.
     *
     * <p>A mouse listener cannot do this. Swing consumes the right-click that opens a component
     * popup menu, and AWT does not pass a consumed event to the mouse listeners of a component
     * with input methods enabled, such as a table.
     */
    public static void selectOnPopup(JTable table, JPopupMenu menu) {
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                if (!(EventQueue.getCurrentEvent() instanceof MouseEvent click) || !click.isPopupTrigger()) {
                    return;
                }
                Point point = SwingUtilities.convertPoint(click.getComponent(), click.getPoint(), table);
                if (table.contains(point)) {
                    selectCellAt(table, point);
                }
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
    }

    /**
     * Moves the selection to the cell at {@code point}, unless that cell is already selected.
     * A point outside the columns counts as the first column. A point below the last row
     * changes nothing.
     */
    static void selectCellAt(JTable table, Point point) {
        int row = table.rowAtPoint(point);
        int column = Math.max(0, table.columnAtPoint(point));
        if (row >= 0 && !table.isCellSelected(row, column)) {
            table.changeSelection(row, column, false, false);
        }
    }
}
