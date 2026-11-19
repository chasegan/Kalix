package com.kalix.ide.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link TableCells} against a laid-out table of four 20px rows and three 100px columns,
 * with cell selection on and room below the last row.
 */
class TableCellsTest {

    private static final int ROW_HEIGHT = 20;
    private static final int COLUMN_WIDTH = 100;

    private JTable table;

    @BeforeEach
    void setUp() {
        table = new JTable(4, 3);
        table.setRowHeight(ROW_HEIGHT);
        table.setCellSelectionEnabled(true);
        table.setSize(3 * COLUMN_WIDTH, 10 * ROW_HEIGHT);
        table.doLayout();
    }

    private static Point middleOf(int row, int column) {
        return new Point(column * COLUMN_WIDTH + COLUMN_WIDTH / 2, row * ROW_HEIGHT + ROW_HEIGHT / 2);
    }

    /**
     * Opens {@code menu} as Swing opens a component popup menu, while {@code click} is the event
     * being dispatched. The click goes through the event queue, as a real click does.
     */
    private void openMenuDuring(MouseEvent click, JPopupMenu menu) throws Exception {
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                fireWillBecomeVisible(menu);
            }
        });
        Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(click);
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static void fireWillBecomeVisible(JPopupMenu menu) {
        for (PopupMenuListener listener : menu.getPopupMenuListeners()) {
            listener.popupMenuWillBecomeVisible(new PopupMenuEvent(menu));
        }
    }

    private MouseEvent release(Point at, boolean popupTrigger) {
        int button = popupTrigger ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1;
        return new MouseEvent(table, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
            at.x, at.y, 1, popupTrigger, button);
    }

    @Test
    void menuOpenedByARightClickSelectsTheClickedCell() throws Exception {
        JPopupMenu menu = new JPopupMenu();
        TableCells.selectOnPopup(table, menu);
        table.changeSelection(0, 0, false, false);

        openMenuDuring(release(middleOf(2, 1), true), menu);

        assertArrayEquals(new int[] {2}, table.getSelectedRows());
        assertArrayEquals(new int[] {1}, table.getSelectedColumns());
    }

    @Test
    void menuOpenedOtherwiseLeavesTheSelection() throws Exception {
        JPopupMenu menu = new JPopupMenu();
        TableCells.selectOnPopup(table, menu);
        table.changeSelection(0, 0, false, false);

        // As from the keyboard: the event being dispatched is not a mouse click.
        SwingUtilities.invokeAndWait(() -> fireWillBecomeVisible(menu));

        assertArrayEquals(new int[] {0}, table.getSelectedRows());
        assertArrayEquals(new int[] {0}, table.getSelectedColumns());
    }

    @Test
    void menuOpenedDuringAPlainClickLeavesTheSelection() throws Exception {
        JPopupMenu menu = new JPopupMenu();
        TableCells.selectOnPopup(table, menu);
        table.changeSelection(0, 0, false, false);

        openMenuDuring(release(middleOf(2, 1), false), menu);

        assertArrayEquals(new int[] {0}, table.getSelectedRows());
        assertArrayEquals(new int[] {0}, table.getSelectedColumns());
    }

    @Test
    void cellOutsideTheSelectionBecomesTheSelection() {
        table.changeSelection(0, 0, false, false);

        TableCells.selectCellAt(table, middleOf(2, 1));

        assertArrayEquals(new int[] {2}, table.getSelectedRows());
        assertArrayEquals(new int[] {1}, table.getSelectedColumns());
    }

    @Test
    void cellInsideTheSelectionKeepsIt() {
        table.changeSelection(0, 0, false, false);
        table.changeSelection(2, 2, false, true);

        TableCells.selectCellAt(table, middleOf(1, 1));

        assertArrayEquals(new int[] {0, 1, 2}, table.getSelectedRows());
        assertArrayEquals(new int[] {0, 1, 2}, table.getSelectedColumns());
    }

    @Test
    void belowTheLastRowChangesNothing() {
        table.changeSelection(1, 1, false, false);

        TableCells.selectCellAt(table, new Point(50, 8 * ROW_HEIGHT));

        assertTrue(table.isCellSelected(1, 1));
        assertEquals(1, table.getSelectedRowCount());
    }

    @Test
    void pastTheLastColumnCountsAsTheFirst() {
        table.changeSelection(0, 2, false, false);

        TableCells.selectCellAt(table, new Point(3 * COLUMN_WIDTH + 50, middleOf(3, 0).y));

        assertTrue(table.isCellSelected(3, 0));
    }
}
