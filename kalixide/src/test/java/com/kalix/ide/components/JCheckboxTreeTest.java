package com.kalix.ide.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JCheckboxTree}'s checked-state model. The mouse hit-testing is
 * exercised manually (it needs a laid-out tree), but the state logic — recursive
 * tick/untick, tri-state ancestor roll-up, check-order preservation, and change-only
 * event firing — is where regressions hide, so it's pinned down here.
 *
 * <p>Order matters to consumers: the Run Manager builds the plotted-series list from
 * {@link JCheckboxTree#getCheckedPaths()}, and the first series is the reference for
 * difference-style plot types, so iteration must reflect the order paths were checked.
 */
class JCheckboxTreeTest {

    private DefaultMutableTreeNode root;
    private DefaultMutableTreeNode parent1;
    private DefaultMutableTreeNode parent2;
    private DefaultMutableTreeNode leafA;
    private DefaultMutableTreeNode leafB;
    private DefaultMutableTreeNode leafC;
    private DefaultMutableTreeNode leafD;
    private DefaultTreeModel model;
    private JCheckboxTree tree;

    @BeforeEach
    void setUp() {
        root = new DefaultMutableTreeNode("root");
        parent1 = new DefaultMutableTreeNode("parent1");
        parent2 = new DefaultMutableTreeNode("parent2");
        leafA = new DefaultMutableTreeNode("a");
        leafB = new DefaultMutableTreeNode("b");
        leafC = new DefaultMutableTreeNode("c");
        leafD = new DefaultMutableTreeNode("d");
        parent1.add(leafA);
        parent1.add(leafB);
        parent2.add(leafC);
        parent2.add(leafD);
        root.add(parent1);
        root.add(parent2);
        model = new DefaultTreeModel(root);
        tree = new JCheckboxTree(model);
    }

    private TreePath path(DefaultMutableTreeNode node) {
        return new TreePath(node.getPath());
    }

    // ---- recursive tick/untick and tri-state roll-up ----

    @Test
    void checkingParentChecksAllDescendants() {
        tree.checkPath(path(parent1));

        assertTrue(tree.isPathChecked(path(parent1)));
        assertTrue(tree.isPathChecked(path(leafA)));
        assertTrue(tree.isPathChecked(path(leafB)));
        assertEquals(JCheckboxTree.CheckState.CHECKED, tree.getCheckState(path(parent1)));
    }

    @Test
    void parentIsPartialWhenSomeChildrenChecked() {
        tree.checkPath(path(leafA));

        assertEquals(JCheckboxTree.CheckState.PARTIAL, tree.getCheckState(path(parent1)));
        assertEquals(JCheckboxTree.CheckState.PARTIAL, tree.getCheckState(path(root)));
        assertFalse(tree.isPathChecked(path(parent1)));
    }

    @Test
    void parentRollsUpToCheckedWhenAllChildrenChecked() {
        tree.checkPath(path(leafA));
        tree.checkPath(path(leafB));

        assertEquals(JCheckboxTree.CheckState.CHECKED, tree.getCheckState(path(parent1)));
        assertTrue(tree.isPathChecked(path(parent1)));
    }

    @Test
    void uncheckingOneChildDemotesParentToPartial() {
        tree.checkPath(path(parent1));
        tree.setCheckedPaths(List.of(path(leafA)));

        assertEquals(JCheckboxTree.CheckState.PARTIAL, tree.getCheckState(path(parent1)));
        assertFalse(tree.isPathChecked(path(parent1)));
        assertTrue(tree.isPathChecked(path(leafA)));
        assertFalse(tree.isPathChecked(path(leafB)));
    }

    @Test
    void recheckingParentPicksUpChildrenInsertedSinceLastTick() {
        tree.checkPath(path(parent1));
        DefaultMutableTreeNode leafE = new DefaultMutableTreeNode("e");
        parent1.add(leafE);
        model.nodesWereInserted(parent1, new int[]{parent1.getIndex(leafE)});

        tree.checkPath(path(parent1));

        assertTrue(tree.isPathChecked(path(leafE)));
    }

    // ---- check-order preservation ----

    @Test
    void checkedPathsPreserveCheckOrder() {
        tree.checkPath(path(leafC));
        tree.checkPath(path(leafA));

        assertArrayEquals(new TreePath[]{path(leafC), path(leafA)}, tree.getCheckedPaths());
    }

    @Test
    void setCheckedPathsAppliesGivenOrder() {
        tree.checkPath(path(leafA));
        tree.setCheckedPaths(List.of(path(leafD), path(leafB)));

        assertArrayEquals(new TreePath[]{path(leafD), path(leafB)}, tree.getCheckedPaths());
    }

    // ---- change-only event firing ----

    @Test
    void eventsFireOnlyOnActualChange() {
        AtomicInteger events = new AtomicInteger();
        tree.addCheckChangeListener(events::incrementAndGet);

        tree.checkPath(path(leafA));
        assertEquals(1, events.get());

        tree.checkPath(path(leafA)); // already checked — silent
        assertEquals(1, events.get());

        tree.removePath(path(leafB)); // never checked — silent
        assertEquals(1, events.get());

        tree.removePath(path(leafA)); // was checked — fires
        assertEquals(2, events.get());
    }

    @Test
    void batchAddFiresSingleEvent() {
        AtomicInteger events = new AtomicInteger();
        tree.addCheckChangeListener(events::incrementAndGet);

        tree.addCheckedPaths(List.of(path(leafA), path(leafC)));
        assertEquals(1, events.get());

        tree.addCheckedPaths(List.of(path(leafA), path(leafC))); // all already checked — silent
        assertEquals(1, events.get());
    }

    // ---- removePath housekeeping ----

    @Test
    void removePathForgetsSubtreeMetadata() {
        tree.checkPath(path(parent1));
        tree.removePath(path(parent1));

        assertEquals(0, tree.getCheckedPaths().length);
        assertFalse(tree.nodeCheckedStateMap.containsKey(path(parent1)));
        assertFalse(tree.nodeCheckedStateMap.containsKey(path(leafA)));
        assertFalse(tree.nodeCheckedStateMap.containsKey(path(leafB)));
        // Untouched siblings keep their entries
        assertTrue(tree.nodeCheckedStateMap.containsKey(path(parent2)));
    }

    // ---- structural rebuilds ----

    @Test
    void structuralReloadClearsCheckedState() {
        tree.checkPath(path(parent1));
        model.reload();

        assertEquals(0, tree.getCheckedPaths().length);
        assertEquals(JCheckboxTree.CheckState.UNCHECKED, tree.getCheckState(path(leafA)));
    }
}
