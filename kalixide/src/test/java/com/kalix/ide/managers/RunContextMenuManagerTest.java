package com.kalix.ide.managers;

import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests the run tree's right-click selection. A plain category row shows no menu, so only the
 * selection changes and no popup needs a display. The menu actions are not reached, so their
 * collaborators are left null.
 */
class RunContextMenuManagerTest {

    @Test
    void rightClickBesideACategoryLabelSelectsOnlyIt() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Runs");
        root.add(new DefaultMutableTreeNode("Current runs"));
        root.add(new DefaultMutableTreeNode("Run library"));
        DefaultTreeModel model = new DefaultTreeModel(root);
        JTree tree = new JTree(model);
        tree.setRootVisible(false);
        tree.setSize(400, 200);
        new RunContextMenuManager(null, tree, null, model, null, null, null, null, null, null,
            null, null).setupRunTreeContextMenu();
        tree.setSelectionRow(1);

        TreePath category = tree.getPathForRow(0);
        Rectangle bounds = tree.getPathBounds(category);
        int x = tree.getWidth() - 5;
        int y = bounds.y + bounds.height / 2;
        // The stock hit test misses here.
        assertNull(tree.getPathForLocation(x, y));

        tree.dispatchEvent(new MouseEvent(
            tree, MouseEvent.MOUSE_RELEASED, 0L, 0, x, y, 1, true, MouseEvent.BUTTON3));

        assertEquals(category, tree.getSelectionPath());
        assertEquals(1, tree.getSelectionCount());
    }
}
