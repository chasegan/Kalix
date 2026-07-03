package com.kalix.ide.components;

import javax.swing.*;
import javax.swing.event.MouseInputAdapter;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

// Reference - https://stackoverflow.com/a/21851201
//      Posted by SomethingSomething, modified by community. Retrieved 2026-07-03, License - CC BY-SA 4.0

/// Note that rendering of individual entries is the purview of the chosen TreeCellRenderer implementation.
///
///  Checked state is to be treated as the user's "chosen set", as opposed to {@link JTree}'s selection model.
public class JCheckboxTree extends JTree {
    // Used for defining inner classes
    private final JCheckboxTree thisTree = this;

    public enum CheckState {
        UNCHECKED, CHECKED, PARTIAL
    }

    ///  {@link CheckedNodeMetadata} tracks metadata i.e. checked state, and whether the node has children
    static class CheckedNodeMetadata {
        CheckState state;
        boolean hasChildren;

        public CheckedNodeMetadata(CheckState state, boolean hasChildren) {
            this.state = state;
            this.hasChildren = hasChildren;
        }
    }

    public static class JTristateCheckBox extends JCheckBox {
    }

    HashMap<TreePath, CheckedNodeMetadata> nodeCheckedStateMap;
    HashSet<TreePath> checkedPaths;

    /// Notified whenever the checked-path set changes, whether from a user click or a
    /// programmatic call to {@link #checkPath} / {@link #setCheckedPaths}.
    /// This fires exactly once per logical change and is never fired incidentally
    /// by selection changes.
    public interface CheckChangeListener {
        void checkStateChanged();
    }
    private final List<CheckChangeListener> checkChangeListeners = new ArrayList<>();

    public void addCheckChangeListener(CheckChangeListener listener) {
        checkChangeListeners.add(listener);
    }

    public void removeCheckChangeListener(CheckChangeListener listener) {
        checkChangeListeners.remove(listener);
    }

    private void fireCheckChangeEvent() {
        for (CheckChangeListener listener : checkChangeListeners) {
            listener.checkStateChanged();
        }
    }

    // Logic for tracking state of nodes
    /// Builds node metadata map recursively. Initializes all nodes as unchecked.
    /// Does not modify checkedPaths - nodes start unchecked until explicitly ticked.
    private void buildNodeMetadata(DefaultMutableTreeNode node) {
        var path = new TreePath(node.getPath());

        // Determine if this node has children
        boolean hasChildren = node.getChildCount() > 0;

        // Create or update the node metadata
        var checkedNode = nodeCheckedStateMap.get(path);
        if (checkedNode == null) {
            checkedNode = new CheckedNodeMetadata(CheckState.UNCHECKED, hasChildren);
            nodeCheckedStateMap.put(path, checkedNode);
        } else {
            checkedNode.state = CheckState.UNCHECKED;
            checkedNode.hasChildren = hasChildren;
        }

        // Recursively process children
        for (var child : Collections.list(node.children())) {
            buildNodeMetadata((DefaultMutableTreeNode) child);
        }
    }

    /// Rebuilds node metadata map from root. Clears all checked state.
    /// All nodes start unchecked until explicitly ticked by user interaction.
    private void resetCheckedState() {
        checkedPaths = new HashSet<>();
        nodeCheckedStateMap = new HashMap<>();
        var rootNode = getModel().getRoot();
        if (rootNode == null) {
            return;
        }
        buildNodeMetadata((DefaultMutableTreeNode) rootNode);
    }

    /// Returns existing metadata for a path, or creates it on demand. Needed because the
    /// tree structure can grow between full rebuilds (e.g. a new run node inserted via
    /// {@code nodesWereInserted}), so a path may not have been present the last time
    /// {@link #resetCheckedState()} walked the model.
    private CheckedNodeMetadata getOrCreateMetadata(TreePath nodePath) {
        CheckedNodeMetadata node = nodeCheckedStateMap.get(nodePath);
        if (node == null) {
            Object lastComponent = nodePath.getLastPathComponent();
            boolean hasChildren = lastComponent instanceof DefaultMutableTreeNode treeNode
                    && treeNode.getChildCount() > 0;
            node = new CheckedNodeMetadata(CheckState.UNCHECKED, hasChildren);
            nodeCheckedStateMap.put(nodePath, node);
        }
        return node;
    }

    private void tickPath(TreePath nodePath) {
        CheckedNodeMetadata node = getOrCreateMetadata(nodePath);
        node.state = CheckState.CHECKED;
        this.checkedPaths.add(nodePath);
        if (node.hasChildren) {
            Object lastComponent = nodePath.getLastPathComponent();
            if (lastComponent instanceof DefaultMutableTreeNode treeNode) {
                for (var child : Collections.list(treeNode.children())) {
                    TreePath childPath = nodePath.pathByAddingChild(child);
                    tickPath(childPath);
                }
            }
        }
        updateAncestorStates(nodePath);
    }

    private void untickPath(TreePath nodePath) {
        CheckedNodeMetadata node = getOrCreateMetadata(nodePath);
        node.state = CheckState.UNCHECKED;
        this.checkedPaths.remove(nodePath);
        if (node.hasChildren) {
            Object lastComponent = nodePath.getLastPathComponent();
            if (lastComponent instanceof DefaultMutableTreeNode treeNode) {
                for (var child : Collections.list(treeNode.children())) {
                    TreePath childPath = nodePath.pathByAddingChild(child);
                    untickPath(childPath);
                }
            }
        }
        updateAncestorStates(nodePath);
    }

    /// Recomputes checked state for every ancestor of {@code nodePath}, climbing from its
    /// immediate parent up to the root. Each ancestor's state is derived purely from its
    /// own children: CHECKED if all are CHECKED, UNCHECKED if none are CHECKED or PARTIAL,
    /// and PARTIAL otherwise. Stops climbing as soon as an ancestor's state is unchanged,
    /// since further ancestors (which only depend on this one through that state) cannot
    /// change either.
    private void updateAncestorStates(TreePath nodePath) {
        TreePath parentPath = nodePath.getParentPath();
        while (parentPath != null) {
            CheckedNodeMetadata parentNode = getOrCreateMetadata(parentPath);
            if (!parentNode.hasChildren) {
                parentNode.hasChildren = true;
            }
            Object parentComponent = parentPath.getLastPathComponent();
            if (!(parentComponent instanceof DefaultMutableTreeNode parentTreeNode)) {
                return;
            }

            boolean allChecked = true;
            boolean anyCheckedOrPartial = false;
            for (var child : Collections.list(parentTreeNode.children())) {
                TreePath childPath = parentPath.pathByAddingChild(child);
                CheckedNodeMetadata childNode = nodeCheckedStateMap.get(childPath);
                CheckState childState = childNode != null ? childNode.state : CheckState.UNCHECKED;
                if (childState != CheckState.CHECKED) {
                    allChecked = false;
                }
                if (childState != CheckState.UNCHECKED) {
                    anyCheckedOrPartial = true;
                }
            }
            CheckState newState = allChecked ? CheckState.CHECKED
                    : anyCheckedOrPartial ? CheckState.PARTIAL : CheckState.UNCHECKED;

            if (newState == parentNode.state) {
                return;
            }
            parentNode.state = newState;
            if (newState == CheckState.CHECKED) {
                checkedPaths.add(parentPath);
            } else {
                checkedPaths.remove(parentPath);
            }

            parentPath = parentPath.getParentPath();
        }
    }

    /// Handles logic for toggling path, including updates of checkedPaths and
    /// nodeCheckedHashMap, and setting of internal CheckedNode state.
    /// Toggling a PARTIAL node resolves it to CHECKED (checking all of its descendants).
    private void togglePath(TreePath nodePath) {
        if (nodePath == null) {
            return;
        }

        CheckedNodeMetadata node = getOrCreateMetadata(nodePath);

        boolean currentlyChecked = node.state == CheckState.CHECKED;
        if (currentlyChecked) {
            untickPath(nodePath); // If checked, uncheck it
        } else {
            tickPath(nodePath); // If unchecked or partial, check it
        }
        fireCheckChangeEvent();
    }

    // Override adds resetCheckedState() call
    @Override
    public void setModel(TreeModel model) {
        super.setModel(model);
        resetCheckedState();
        attachStructureListener(model);
    }

    /// Keeps checked-state metadata in sync with full tree rebuilds (e.g. {@code
    /// DefaultTreeModel.reload()}, used when the outputs tree is filtered or repopulated
    /// for a new set of checked sources). Incremental changes (node insert/remove) are left
    /// alone so checked state on unaffected nodes survives - only a structural reload
    /// invalidates the whole path set, since old TreePath instances reference node objects
    /// that no longer exist in the model.
    private void attachStructureListener(TreeModel model) {
        if (model == null) {
            return;
        }
        model.addTreeModelListener(new TreeModelListener() {
            @Override
            public void treeStructureChanged(TreeModelEvent e) {
                resetCheckedState();
            }
            @Override
            public void treeNodesChanged(TreeModelEvent e) {}
            @Override
            public void treeNodesInserted(TreeModelEvent e) {}
            @Override
            public void treeNodesRemoved(TreeModelEvent e) {}
        });
    }

    public TreePath[] getCheckedPaths() {
        return this.checkedPaths.toArray(new TreePath[0]);
    }

    public boolean isPathChecked(TreePath path) {
        return this.checkedPaths.contains(path);
    }

    /// Returns the tri-state checked status of a path (UNCHECKED if the path isn't
    /// currently tracked, e.g. it isn't part of the tree). Used by
    /// {@link com.kalix.ide.renderers.CheckboxTreeCellRenderer} to render the partial
    /// (some-but-not-all-descendants-checked) state.
    public CheckState getCheckState(TreePath path) {
        CheckedNodeMetadata node = nodeCheckedStateMap.get(path);
        return node != null ? node.state : CheckState.UNCHECKED;
    }

    /// Checks a single path, in addition to whatever is already checked. Fires a check
    /// change event. Used for programmatic single-path updates (e.g. auto-checking a
    /// newly-launched run) where wiping out the rest of the checked set is not wanted.
    public void checkPath(TreePath path) {
        tickPath(path);
        fireCheckChangeEvent();
        repaint();
    }

    /// Unchecks a single path, leaving the rest of the checked set alone. Fires a check
    /// change event. Used to drop a stale entry (e.g. a node about to be removed from the
    /// model) before it becomes unreachable via the tree structure.
    public void uncheckPath(TreePath path) {
        untickPath(path);
        fireCheckChangeEvent();
        repaint();
    }

    /// Replaces the entire checked set with exactly the given paths. Fires a single check
    /// change event. Used to restore checked state after a structural rebuild (which clears
    /// checked state via {@link #attachStructureListener}) from a caller's own record of
    /// what should be checked (e.g. by stable ref, not by the now-stale TreePath).
    public void setCheckedPaths(Collection<TreePath> paths) {
        for (TreePath path : new ArrayList<>(checkedPaths)) {
            untickPath(path);
        }
        for (TreePath path : paths) {
            tickPath(path);
        }
        fireCheckChangeEvent();
        repaint();
    }

    public JCheckboxTree(TreeModel treeModel) {
        super(treeModel);
        // Not attaching the structure listener here: super(treeModel) already invoked our
        // setModel() override above (JTree's constructor calls setModel(newModel)
        // internally), which already attached one. Attaching a second time here would
        // double-register it, so resetCheckedState() would run twice per reload().
        this.addMouseListener(new MouseInputAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                TreePath selPath = thisTree.getPathForLocation(e.getX(), e.getY());
                if (selPath != null) {
                    thisTree.togglePath(selPath);
                    thisTree.repaint(); // Repaint to show updated checkbox state
                }
            }
        });
    }
}
