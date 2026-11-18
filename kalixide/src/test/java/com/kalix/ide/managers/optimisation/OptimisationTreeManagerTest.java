package com.kalix.ide.managers.optimisation;

import com.kalix.ide.managers.SessionTreeBookkeeping;
import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.tree.TreePath;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests the optimisation tree's right-click selection. It uses the folder row because that
 * row shows no menu, so only the selection changes and no popup needs a display.
 */
class OptimisationTreeManagerTest {

    @Test
    void rightClickBesideAFolderLabelSelectsIt() {
        JTree tree = new OptimisationTreeManager(new SessionTreeBookkeeping<>()).getTree();
        tree.setSize(400, 200);
        TreePath folder = tree.getPathForRow(0);
        Rectangle bounds = tree.getPathBounds(folder);
        int x = tree.getWidth() - 5;
        int y = bounds.y + bounds.height / 2;
        // The stock hit test misses here.
        assertNull(tree.getPathForLocation(x, y));

        tree.dispatchEvent(new MouseEvent(
            tree, MouseEvent.MOUSE_RELEASED, 0L, 0, x, y, 1, true, MouseEvent.BUTTON3));

        assertEquals(folder, tree.getSelectionPath());
    }
}
