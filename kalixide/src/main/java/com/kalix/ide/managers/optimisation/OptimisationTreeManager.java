package com.kalix.ide.managers.optimisation;

import com.kalix.ide.models.optimisation.OptimisationInfo;
import com.kalix.ide.models.optimisation.OptimisationStatus;
import com.kalix.ide.models.optimisation.OptimisationResult;
import com.kalix.ide.renderers.OptimisationTreeCellRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.tree.*;
import javax.swing.event.TreeSelectionListener;
import javax.swing.event.TreeSelectionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Manages the tree structure for optimisation runs.
 * Handles node creation, updates, selection, and context menus.
 */
public class OptimisationTreeManager {

    private static final Logger logger = LoggerFactory.getLogger(OptimisationTreeManager.class);

    private final JTree tree;
    private final DefaultTreeModel treeModel;
    private final DefaultMutableTreeNode rootNode;
    private final DefaultMutableTreeNode currentOptimisationsNode;
    private final Map<String, DefaultMutableTreeNode> sessionToNodeMap = new HashMap<>();

    // Context menu actions
    private Consumer<OptimisationInfo> showModelAction;
    private Consumer<OptimisationInfo> showOptimisedModelAction;
    private Consumer<OptimisationInfo> compareModelAction;
    private Consumer<OptimisationInfo> saveResultsAction;
    private Consumer<OptimisationInfo> stopOptimisationAction;
    private Consumer<OptimisationInfo> renameAction;
    private Consumer<OptimisationInfo> removeAction;

    // Selection handling callbacks
    private Runnable onNoSelectionCallback;
    private Consumer<OptimisationInfo> onOptimisationSelectedCallback;
    private Runnable onFolderSelectedCallback;
    private Runnable saveCurrentConfigCallback;

    /**
     * Creates a new OptimisationTreeManager.
     */
    public OptimisationTreeManager() {
        // Build tree structure
        this.rootNode = new DefaultMutableTreeNode("Optimisations");
        this.currentOptimisationsNode = new DefaultMutableTreeNode("Optimisation runs");
        this.rootNode.add(currentOptimisationsNode);

        this.treeModel = new DefaultTreeModel(rootNode);
        this.tree = new JTree(treeModel);

        // Configure tree appearance
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new OptimisationTreeCellRenderer());

        // Setup context menu
        setupContextMenu();

        // Expand the optimisation runs node by default
        tree.expandRow(0);
    }

    /**
     * Gets the JTree component.
     *
     * @return The tree component
     */
    public JTree getTree() {
        return tree;
    }

    /**
     * Adds a tree selection listener.
     *
     * @param listener The selection listener to add
     */
    public void addTreeSelectionListener(TreeSelectionListener listener) {
        tree.addTreeSelectionListener(listener);
    }

    /**
     * Adds a new optimisation to the tree.
     *
     * @param sessionKey The session key
     * @param info The optimisation info
     */
    public void addOptimisation(String sessionKey, OptimisationInfo info) {
        DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(info);
        currentOptimisationsNode.add(newNode);
        sessionToNodeMap.put(sessionKey, newNode);

        // Update tree model
        treeModel.nodeStructureChanged(currentOptimisationsNode);

        // Auto-expand and select
        tree.expandPath(new TreePath(currentOptimisationsNode.getPath()));
        TreePath path = new TreePath(newNode.getPath());
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);

        logger.debug("Added optimisation {} to tree", info.getName());
    }

    /**
     * Removes an optimisation from the tree.
     *
     * @param sessionKey The session key
     */
    public void removeOptimisation(String sessionKey) {
        DefaultMutableTreeNode node = sessionToNodeMap.get(sessionKey);
        if (node != null) {
            DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
            parent.remove(node);
            sessionToNodeMap.remove(sessionKey);
            treeModel.nodeStructureChanged(parent);

            logger.debug("Removed optimisation with session {}", sessionKey);
        }
    }

    /**
     * Updates the status of an optimisation in the tree.
     *
     * @param sessionKey The session key
     * @param status The new status
     */
    public void updateOptimisationStatus(String sessionKey, OptimisationStatus status) {
        DefaultMutableTreeNode node = sessionToNodeMap.get(sessionKey);
        if (node != null) {
            Object userObject = node.getUserObject();
            if (userObject instanceof OptimisationInfo) {
                // Status is determined dynamically by OptimisationInfo.getStatus()
                // Just trigger a repaint
                treeModel.nodeChanged(node);
            }
        }
    }

    /**
     * Updates the result for an optimisation.
     *
     * @param sessionKey The session key
     * @param result The optimisation result
     */
    public void updateOptimisationResult(String sessionKey, OptimisationResult result) {
        DefaultMutableTreeNode node = sessionToNodeMap.get(sessionKey);
        if (node != null) {
            Object userObject = node.getUserObject();
            if (userObject instanceof OptimisationInfo) {
                OptimisationInfo info = (OptimisationInfo) userObject;
                info.setResult(result);
                treeModel.nodeChanged(node);
            }
        }
    }

    /**
     * Selects an optimisation in the tree.
     *
     * @param sessionKey The session key
     */
    public void selectOptimisation(String sessionKey) {
        DefaultMutableTreeNode node = sessionToNodeMap.get(sessionKey);
        if (node != null) {
            TreePath path = new TreePath(node.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        }
    }

    /**
     * Gets the currently selected optimisation info.
     *
     * @return The selected optimisation info, or null if none selected
     */
    public OptimisationInfo getSelectedOptimisation() {
        TreePath path = tree.getSelectionPath();
        if (path != null) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            Object userObject = node.getUserObject();
            if (userObject instanceof OptimisationInfo) {
                return (OptimisationInfo) userObject;
            }
        }
        return null;
    }

    /**
     * Gets the tree node for a session.
     *
     * @param sessionKey The session key
     * @return The tree node, or null if not found
     */
    public DefaultMutableTreeNode getNodeForSession(String sessionKey) {
        return sessionToNodeMap.get(sessionKey);
    }

    /**
     * Refreshes the display of a specific node.
     *
     * @param sessionKey The session key
     */
    public void refreshNode(String sessionKey) {
        DefaultMutableTreeNode node = sessionToNodeMap.get(sessionKey);
        if (node != null) {
            treeModel.nodeChanged(node);
        }
    }

    /**
     * Refreshes the entire tree.
     */
    public void refreshTree() {
        treeModel.nodeStructureChanged(rootNode);
    }

    /**
     * Sets up the context menu for the tree.
     */
    public void setupContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();

        // Show Model
        JMenuItem showModelItem = new JMenuItem("Show Model");
        showModelItem.addActionListener(e -> {
            OptimisationInfo info = getSelectedOptimisation();
            if (info != null && showModelAction != null) {
                showModelAction.accept(info);
            }
        });
        contextMenu.add(showModelItem);

        // Show Optimised Model (only for completed)
        JMenuItem showOptimisedItem = new JMenuItem("Show Optimised Model");
        showOptimisedItem.addActionListener(e -> {
            OptimisationInfo info = getSelectedOptimisation();
            if (info != null && showOptimisedModelAction != null) {
                showOptimisedModelAction.accept(info);
            }
        });
        contextMenu.add(showOptimisedItem);

        // Compare Models (only for completed)
        JMenuItem compareItem = new JMenuItem("Compare Models");
        compareItem.addActionListener(e -> {
            OptimisationInfo info = getSelectedOptimisation();
            if (info != null && compareModelAction != null) {
                compareModelAction.accept(info);
            }
        });
        contextMenu.add(compareItem);

        contextMenu.addSeparator();

        // Save Results (only for completed)
        JMenuItem saveResultsItem = new JMenuItem("Save Results");
        saveResultsItem.addActionListener(e -> {
            OptimisationInfo info = getSelectedOptimisation();
            if (info != null && saveResultsAction != null) {
                saveResultsAction.accept(info);
            }
        });
        contextMenu.add(saveResultsItem);

        contextMenu.addSeparator();

        // Stop Optimisation (only for running)
        JMenuItem stopItem = new JMenuItem("Stop Optimisation");
        stopItem.addActionListener(e -> {
            OptimisationInfo info = getSelectedOptimisation();
            if (info != null && stopOptimisationAction != null) {
                stopOptimisationAction.accept(info);
            }
        });
        contextMenu.add(stopItem);

        contextMenu.addSeparator();

        // Rename
        JMenuItem renameItem = new JMenuItem("Rename");
        renameItem.addActionListener(e -> {
            OptimisationInfo info = getSelectedOptimisation();
            if (info != null && renameAction != null) {
                renameAction.accept(info);
            }
        });
        contextMenu.add(renameItem);

        // Remove
        JMenuItem removeItem = new JMenuItem("Remove");
        removeItem.addActionListener(e -> {
            OptimisationInfo info = getSelectedOptimisation();
            if (info != null && removeAction != null) {
                removeAction.accept(info);
            }
        });
        contextMenu.add(removeItem);

        // Add mouse listener for right-click
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e, contextMenu);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e, contextMenu);
                }
            }

            private void showContextMenu(MouseEvent e, JPopupMenu menu) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    tree.setSelectionPath(path);
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();

                    if (node.getUserObject() instanceof OptimisationInfo) {
                        OptimisationInfo info = (OptimisationInfo) node.getUserObject();
                        OptimisationStatus status = info.getStatus();

                        // Enable/disable menu items based on status
                        showOptimisedItem.setEnabled(status == OptimisationStatus.DONE);
                        compareItem.setEnabled(status == OptimisationStatus.DONE);
                        saveResultsItem.setEnabled(status == OptimisationStatus.DONE);
                        stopItem.setEnabled(info.isRunning());

                        menu.show(tree, e.getX(), e.getY());
                    }
                }
            }
        });
    }

    // Setters for context menu actions
    public void setShowModelAction(Consumer<OptimisationInfo> action) {
        this.showModelAction = action;
    }

    public void setShowOptimisedModelAction(Consumer<OptimisationInfo> action) {
        this.showOptimisedModelAction = action;
    }

    public void setCompareModelAction(Consumer<OptimisationInfo> action) {
        this.compareModelAction = action;
    }

    public void setSaveResultsAction(Consumer<OptimisationInfo> action) {
        this.saveResultsAction = action;
    }

    public void setStopOptimisationAction(Consumer<OptimisationInfo> action) {
        this.stopOptimisationAction = action;
    }

    public void setRenameAction(Consumer<OptimisationInfo> action) {
        this.renameAction = action;
    }

    public void setRemoveAction(Consumer<OptimisationInfo> action) {
        this.removeAction = action;
    }

    /**
     * Updates the tree node display for a specific session.
     * Triggers a refresh of the node's visual representation.
     *
     * @param node The tree node to update
     * @param currentStatus The current status
     * @param previousStatus The previous status
     */
    public void updateTreeNodeForSession(DefaultMutableTreeNode node,
                                         OptimisationStatus currentStatus,
                                         OptimisationStatus previousStatus) {
        if (node != null && treeModel != null) {
            // Notify tree model of change (triggers renderer update)
            treeModel.nodeChanged(node);

            // If this was a significant status change, expand the tree
            if (previousStatus != currentStatus &&
                (currentStatus == OptimisationStatus.DONE || currentStatus == OptimisationStatus.ERROR)) {
                tree.expandPath(new TreePath(currentOptimisationsNode.getPath()));
            }
        }
    }

    /**
     * Handles tree selection changes.
     * This method should be called from a TreeSelectionListener.
     *
     * @param e The tree selection event
     */
    public void handleTreeSelection(TreeSelectionEvent e) {
        TreePath selectedPath = e.getNewLeadSelectionPath();

        if (selectedPath == null) {
            // No selection - invoke callback
            if (onNoSelectionCallback != null) {
                onNoSelectionCallback.run();
            }
            return;
        }

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();

        if (selectedNode.getUserObject() instanceof OptimisationInfo) {
            // Save current config before switching
            if (saveCurrentConfigCallback != null) {
                saveCurrentConfigCallback.run();
            }

            // Optimisation node selected
            OptimisationInfo optInfo = (OptimisationInfo) selectedNode.getUserObject();
            if (onOptimisationSelectedCallback != null) {
                onOptimisationSelectedCallback.accept(optInfo);
            }
        } else {
            // Folder node selected
            if (onFolderSelectedCallback != null) {
                onFolderSelectedCallback.run();
            }
        }
    }

    // Setters for selection callbacks
    public void setOnNoSelectionCallback(Runnable callback) {
        this.onNoSelectionCallback = callback;
    }

    public void setOnOptimisationSelectedCallback(Consumer<OptimisationInfo> callback) {
        this.onOptimisationSelectedCallback = callback;
    }

    public void setOnFolderSelectedCallback(Runnable callback) {
        this.onFolderSelectedCallback = callback;
    }

    public void setSaveCurrentConfigCallback(Runnable callback) {
        this.saveCurrentConfigCallback = callback;
    }
}