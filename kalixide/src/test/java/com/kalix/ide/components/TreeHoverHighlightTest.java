package com.kalix.ide.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JLayer;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.border.MatteBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paints a hover-highlighted tree off screen and reads pixels at the far right of its rows,
 * well clear of the labels. The tree has 40 rows of 20px in a 200px tall scroll pane.
 */
class TreeHoverHighlightTest {

    private static final int ROW_HEIGHT = 20;
    private static final int WIDTH = 300;
    private static final int HEIGHT = 200;

    // Every region the tree was asked to repaint, in tree coordinates.
    private final List<Rectangle> repaints = new ArrayList<>();
    private JTree tree;
    private JScrollPane scroll;
    private JLayer<JScrollPane> layer;

    @BeforeEach
    void setUp() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        for (int i = 0; i < 39; i++) {
            root.add(new DefaultMutableTreeNode("n" + i));
        }
        tree = new JTree(new DefaultTreeModel(root)) {
            @Override
            public void repaint(long tm, int x, int y, int width, int height) {
                repaints.add(new Rectangle(x, y, width, height));
                super.repaint(tm, x, y, width, height);
            }
        };
        tree.setRowHeight(ROW_HEIGHT);
        scroll = new JScrollPane(tree);
        scroll.setBorder(null);
        layer = TreeHoverHighlight.layerAround(scroll);
        layer.setSize(WIDTH, HEIGHT);
        layOut(layer);
    }

    private static void layOut(Component component) {
        component.doLayout();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                layOut(child);
            }
        }
    }

    /** Moves the pointer to the middle of the row currently shown at {@code viewportRow}. */
    private void hoverViewportRow(int viewportRow) {
        int y = scroll.getViewport().getViewPosition().y + viewportRow * ROW_HEIGHT + ROW_HEIGHT / 2;
        mouse(MouseEvent.MOUSE_MOVED, y);
    }

    private void mouse(int id, int y) {
        mouse(id, 10, y);
    }

    private void mouse(int id, int x, int y) {
        tree.dispatchEvent(new MouseEvent(tree, id, 0L, 0, x, y, 0, false));
    }

    /** The colour at the right-hand end of the row shown at {@code viewportRow}. */
    private int rightEndOfViewportRow(int viewportRow) {
        return pixel(scroll.getViewport().getWidth() - 5, viewportRow);
    }

    /** The colour of the vertical scroll bar's track beside {@code viewportRow}. */
    private int scrollBarBeside(int viewportRow) {
        return pixel(WIDTH - 2, viewportRow);
    }

    private int pixel(int x, int viewportRow) {
        return pixelAt(x, viewportRow * ROW_HEIGHT + ROW_HEIGHT / 2);
    }

    private int pixelAt(int x, int y) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        layer.paint(g);
        g.dispose();
        return image.getRGB(x, y);
    }

    @Test
    void hoveredRowIsWashedAcrossItsFullWidth() {
        int plain = rightEndOfViewportRow(2);

        hoverViewportRow(2);

        assertNotEquals(plain, rightEndOfViewportRow(2));
        assertEquals(plain, rightEndOfViewportRow(3));
    }

    @Test
    void washStopsAtTheViewportEdge() {
        int bar = scrollBarBeside(2);

        hoverViewportRow(2);

        assertEquals(bar, scrollBarBeside(2));
    }

    @Test
    void washStopsAtTheViewportsBottomEdge() {
        // A border below the viewport cuts the last row in view in half.
        scroll.setBorder(new MatteBorder(0, 0, ROW_HEIGHT / 2, 0, Color.BLACK));
        layOut(layer);
        int viewportBottom = scroll.getViewport().getHeight();
        int x = WIDTH / 2;
        int border = pixelAt(x, viewportBottom + 1);

        mouse(MouseEvent.MOUSE_MOVED, viewportBottom - 1);

        assertEquals(border, pixelAt(x, viewportBottom + 1));
    }

    @Test
    void scrollPaneThatDoesNotShowATreeIsRefused() {
        JScrollPane notATree = new JScrollPane(new JPanel());

        assertThrows(IllegalArgumentException.class, () -> TreeHoverHighlight.layerAround(notATree));
    }

    /**
     * The look and feel decides whether the scroll pane holds the focus by looking at the
     * viewport's view, so the highlight must not put anything between the two.
     */
    @Test
    void treeStaysTheViewportsView() {
        assertSame(tree, scroll.getViewport().getView());
    }

    @Test
    void washGoesWhenThePointerLeaves() {
        int plain = rightEndOfViewportRow(2);
        hoverViewportRow(2);

        mouse(MouseEvent.MOUSE_EXITED, 2 * ROW_HEIGHT);

        assertEquals(plain, rightEndOfViewportRow(2));
    }

    @Test
    void washFollowsADrag() {
        int plain = rightEndOfViewportRow(2);
        hoverViewportRow(2);

        mouse(MouseEvent.MOUSE_DRAGGED, 5 * ROW_HEIGHT + ROW_HEIGHT / 2);

        assertEquals(plain, rightEndOfViewportRow(2));
        assertNotEquals(plain, rightEndOfViewportRow(5));
    }

    @Test
    void washGoesWhenADragLeavesTheTree() {
        int plain = rightEndOfViewportRow(5);
        hoverViewportRow(2);

        // Beside row 5, past the tree's right edge: the drag still reports to the tree.
        mouse(MouseEvent.MOUSE_DRAGGED, tree.getWidth() + 50, 5 * ROW_HEIGHT + ROW_HEIGHT / 2);

        assertEquals(plain, rightEndOfViewportRow(2));
        assertEquals(plain, rightEndOfViewportRow(5));
    }

    @Test
    void washColourIsReadOncePerLookAndFeel() {
        try {
            UIManager.put("Tree.selectionBackground", Color.RED);
            layer.updateUI();
            hoverViewportRow(2);
            int red = rightEndOfViewportRow(2);

            UIManager.put("Tree.selectionBackground", Color.BLUE);
            // Not re-read on paint: the cached colour holds until the look and feel changes.
            assertEquals(red, rightEndOfViewportRow(2));

            layer.updateUI();
            assertNotEquals(red, rightEndOfViewportRow(2));
        } finally {
            // Null drops the override, so the look and feel's own value shows through again.
            UIManager.put("Tree.selectionBackground", null);
        }
    }

    @Test
    void selectedRowIsNotWashed() {
        tree.setSelectionRow(2);
        int selected = rightEndOfViewportRow(2);

        hoverViewportRow(2);

        assertEquals(selected, rightEndOfViewportRow(2));
    }

    @Test
    void washStaysUnderAStillPointerWhenTheTreeScrolls() {
        int plain = rightEndOfViewportRow(2);
        hoverViewportRow(2);

        scroll.getViewport().setViewPosition(new Point(0, 5 * ROW_HEIGHT));

        // Tree row 7 has scrolled under the pointer; row 2's wash must not travel with it.
        assertNotEquals(plain, rightEndOfViewportRow(2));
        assertEquals(plain, rightEndOfViewportRow(3));
    }

    /**
     * A scroll moves the tree under a still pointer with no mouse event, and the rows the
     * wash left and entered are repainted.
     */
    @Test
    void scrollRepaintsTheRowsTheWashLeftAndEntered() {
        hoverViewportRow(2);
        rightEndOfViewportRow(2);
        repaints.clear();

        scroll.getViewport().setViewPosition(new Point(0, 5 * ROW_HEIGHT));

        assertTrue(repaints.contains(rowBounds(2)), "row the wash left: " + repaints);
        assertTrue(repaints.contains(rowBounds(7)), "row the wash entered: " + repaints);
    }

    private Rectangle rowBounds(int row) {
        return new Rectangle(0, row * ROW_HEIGHT, tree.getWidth(), ROW_HEIGHT);
    }

    @Test
    void washGoesWhenTheRowUnderThePointerIsRemoved() {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        hoverViewportRow(2);

        root.removeAllChildren();
        ((DefaultTreeModel) tree.getModel()).reload();
        layOut(layer);

        // Only the root row is left, so the pointer is over empty space.
        assertEquals(rightEndOfViewportRow(3), rightEndOfViewportRow(2));
    }

    @Test
    void removingTheHoveredRowRepaintsWhereItWas() {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        hoverViewportRow(2);
        repaints.clear();

        root.removeAllChildren();
        ((DefaultTreeModel) tree.getModel()).reload();
        layOut(layer);
        rightEndOfViewportRow(2);

        // The path has no bounds left, so only the remembered ones can clear its wash.
        assertTrue(
            repaints.stream().anyMatch(r -> r.y == 2 * ROW_HEIGHT && r.height == ROW_HEIGHT),
            "row the wash left: " + repaints);
    }

    /**
     * A tree shorter than the viewport fills it, so a row-height change resizes nothing and
     * the viewport fires no change event. Only the paint can see that another row is under the
     * pointer.
     */
    @Test
    void washFollowsTheRowUnderAStillPointerWhenRowHeightsChange() {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        root.removeAllChildren();
        for (int i = 0; i < 3; i++) {
            root.add(new DefaultMutableTreeNode("n" + i));
        }
        ((DefaultTreeModel) tree.getModel()).reload();
        layOut(layer);
        int plain = rightEndOfViewportRow(4);
        hoverViewportRow(2);

        tree.setRowHeight(2 * ROW_HEIGHT);
        layOut(layer);

        // The pointer, 50px down, is now over tree row 1 (40-80px), not row 2 (80-120px).
        assertNotEquals(plain, rightEndOfViewportRow(2));
        assertEquals(plain, rightEndOfViewportRow(4));
    }
}
