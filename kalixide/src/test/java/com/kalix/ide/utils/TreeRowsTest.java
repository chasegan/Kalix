package com.kalix.ide.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests {@link TreeRows} against a laid-out tree of four 20px rows (root, a, b, c) in a 400px
 * wide component, so every row has empty space to the right of its label.
 */
class TreeRowsTest {

    private static final int ROW_HEIGHT = 20;
    private static final int WIDTH = 400;

    private JTree tree;

    @BeforeEach
    void setUp() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        root.add(new DefaultMutableTreeNode("a"));
        root.add(new DefaultMutableTreeNode("b"));
        root.add(new DefaultMutableTreeNode("c"));
        tree = new JTree(new DefaultTreeModel(root));
        tree.setRowHeight(ROW_HEIGHT);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setSize(WIDTH, 10 * ROW_HEIGHT);
    }

    private static int middleOf(int row) {
        return row * ROW_HEIGHT + ROW_HEIGHT / 2;
    }

    @Test
    void rowIsHitBesideItsLabel() {
        int y = middleOf(2);
        // The stock hit test misses here, which is the reason TreeRows exists.
        assertNull(tree.getPathForLocation(WIDTH - 5, y));

        assertEquals(tree.getPathForRow(2), TreeRows.fullWidthPathAt(tree, y));
    }

    @Test
    void rowEdgesBelongToOneRowEach() {
        assertEquals(tree.getPathForRow(1), TreeRows.fullWidthPathAt(tree, ROW_HEIGHT));
        assertEquals(tree.getPathForRow(1), TreeRows.fullWidthPathAt(tree, 2 * ROW_HEIGHT - 1));
        assertEquals(tree.getPathForRow(2), TreeRows.fullWidthPathAt(tree, 2 * ROW_HEIGHT));
    }

    @Test
    void noRowBelowTheLastOrAboveTheFirst() {
        assertNull(TreeRows.fullWidthPathAt(tree, 4 * ROW_HEIGHT));
        assertNull(TreeRows.fullWidthPathAt(tree, -1));
    }

    @Test
    void popupOnUnselectedRowSelectsOnlyThatRow() {
        tree.setSelectionRows(new int[] {1, 2});

        assertEquals(tree.getPathForRow(3), TreeRows.selectForPopup(tree, middleOf(3)));
        assertArrayEquals(new int[] {3}, tree.getSelectionRows());
    }

    @Test
    void popupInsideMultiSelectionKeepsIt() {
        tree.setSelectionRows(new int[] {1, 2});

        assertEquals(tree.getPathForRow(2), TreeRows.selectForPopup(tree, middleOf(2)));
        assertArrayEquals(new int[] {1, 2}, tree.getSelectionRows());
    }

    @Test
    void popupOnEmptySpaceClearsSelection() {
        tree.setSelectionRows(new int[] {1, 2});

        assertNull(TreeRows.selectForPopup(tree, 6 * ROW_HEIGHT));
        assertEquals(0, tree.getSelectionCount());
    }

    @Test
    void popupForOneNodeReplacesAMultiSelectionItIsInside() {
        tree.setSelectionRows(new int[] {1, 2});

        assertEquals(tree.getPathForRow(2), TreeRows.selectOnlyForPopup(tree, middleOf(2)));
        assertArrayEquals(new int[] {2}, tree.getSelectionRows());
    }

    @Test
    void popupForOneNodeOnEmptySpaceKeepsTheSelection() {
        tree.setSelectionRows(new int[] {1, 2});

        assertNull(TreeRows.selectOnlyForPopup(tree, 6 * ROW_HEIGHT));
        assertArrayEquals(new int[] {1, 2}, tree.getSelectionRows());
    }
}
