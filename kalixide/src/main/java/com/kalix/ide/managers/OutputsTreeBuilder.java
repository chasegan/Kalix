package com.kalix.ide.managers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Builds and updates the timeseries tree (left-bottom panel) in RunManager.
 *
 * <h2>Tree Structure</h2>
 * Series names are dot-delimited (e.g., "node.mygr4j.ds_1") and displayed hierarchically:
 * <pre>
 * node
 * └── mygr4j
 *     ├── ds_1 [Last]           → SeriesLeafNode (single source)
 *     └── ds_2 [Last, Run_1]    → SeriesParentNode (multiple sources)
 *         ├── Last              → SeriesLeafNode (child)
 *         └── Run_1             → SeriesLeafNode (child)
 * </pre>
 *
 * <h2>Node Types</h2>
 * <ul>
 *   <li>{@link SeriesLeafNode} - Plottable leaf with seriesName and source reference</li>
 *   <li>{@link SeriesParentNode} - Parent when same series exists in multiple selected sources</li>
 *   <li>String nodes - Intermediate hierarchy levels (e.g., "node", "mygr4j")</li>
 * </ul>
 *
 * <h2>Rebuild Behavior</h2>
 * {@link #updateTree} does a full rebuild via {@code timeseriesTreeModel.reload()}.
 * Expansion state is preserved by recording expanded paths before rebuild and restoring after.
 * Tree selection is handled separately by RunManager via {@code restoreTreeSelectionFromSelectedSeries()}.
 *
 * <h2>Data Source</h2>
 * Series names come from {@code getSeriesNamesCallback} which calls:
 * <ul>
 *   <li>For runs: {@link com.kalix.ide.cli.RunModelProgram#getOutputsGenerated()}</li>
 *   <li>For datasets: The loaded CSV/KAI column names</li>
 * </ul>
 *
 * @see com.kalix.ide.windows.RunManager#onRunTreeSelectionChanged
 * @see com.kalix.ide.windows.RunManager#restoreTreeSelectionFromSelectedSeries
 */
public class OutputsTreeBuilder {

    private static final Logger logger = LoggerFactory.getLogger(OutputsTreeBuilder.class);

    // Tree components
    private final JTree timeseriesTree;
    private final DefaultTreeModel timeseriesTreeModel;

    // Dependencies (callbacks to RunManager)
    private final Function<Object, List<String>> getSeriesNamesCallback;  // Gets series from RunInfo or LoadedDatasetInfo
    private final BiFunction<String, String, Integer> naturalCompareCallback;  // Natural sorting function

    // Filter state
    private String filterText = "";
    private List<TreePath> preFilterExpansionState = null;

    /**
     * Creates a new OutputsTreeBuilder.
     *
     * @param timeseriesTree The JTree to update
     * @param timeseriesTreeModel The tree's model
     * @param getSeriesNamesCallback Function to get series names from a source (RunInfo or LoadedDatasetInfo)
     * @param naturalCompareCallback Function for natural string comparison
     */
    public OutputsTreeBuilder(
            JTree timeseriesTree,
            DefaultTreeModel timeseriesTreeModel,
            Function<Object, List<String>> getSeriesNamesCallback,
            BiFunction<String, String, Integer> naturalCompareCallback) {
        this.timeseriesTree = timeseriesTree;
        this.timeseriesTreeModel = timeseriesTreeModel;
        this.getSeriesNamesCallback = getSeriesNamesCallback;
        this.naturalCompareCallback = naturalCompareCallback;
    }

    /**
     * Sets the filter text. When non-empty, only nodes matching the filter
     * (case-insensitive) and their ancestors are shown after rebuild.
     */
    public void setFilterText(String filterText) {
        this.filterText = (filterText == null) ? "" : filterText.trim();
    }

    public String getFilterText() {
        return filterText;
    }

    /**
     * Updates the tree with a list of selected sources (runs and/or datasets).
     * Automatically handles single vs multi-source tree strategies.
     */
    public void updateTree(List<Object> selectedRuns, List<Object> selectedDatasets) {
        if (selectedRuns.isEmpty() && selectedDatasets.isEmpty()) {
            showEmptyTree("Select one or more runs or datasets");
            return;
        }

        updateTreeForMultipleSources(selectedRuns, selectedDatasets);
    }

    /**
     * Shows an empty tree with a message.
     */
    public void showEmptyTree(String message) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();
        root.removeAllChildren();
        root.add(new DefaultMutableTreeNode(message));
        timeseriesTreeModel.reload();
    }

    /**
     * Updates the timeseries tree for multiple selected sources (runs and/or datasets).
     * Series available in multiple sources become parent nodes with source children.
     * Series available in only one source become simple leaf nodes.
     */
    private void updateTreeForMultipleSources(List<Object> selectedRuns, List<Object> selectedDatasets) {
        // Remember current expansion state
        List<TreePath> expandedPaths = new ArrayList<>();
        boolean hadDatasetPaths = false;  // Track if previous tree had "file" nodes
        for (int i = 0; i < timeseriesTree.getRowCount(); i++) {
            TreePath path = timeseriesTree.getPathForRow(i);
            if (timeseriesTree.isExpanded(path)) {
                expandedPaths.add(path);
                // Check if this path contains "file" node (dataset tree)
                for (Object component : path.getPath()) {
                    if (component instanceof DefaultMutableTreeNode) {
                        Object userObj = ((DefaultMutableTreeNode) component).getUserObject();
                        if (userObj instanceof String && "file".equals(userObj)) {
                            hadDatasetPaths = true;
                            break;
                        }
                    }
                }
            }
        }

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();
        root.removeAllChildren();

        // If only one source selected, use simplified structure
        boolean isDatasetTree = false;
        if (selectedRuns.size() + selectedDatasets.size() == 1) {
            if (selectedRuns.size() == 1) {
                updateTreeSingleSource(root, selectedRuns.get(0));
            } else {
                updateTreeSingleSource(root, selectedDatasets.get(0));
                isDatasetTree = true;
            }
        } else {
            // Build multi-source hybrid tree
            updateTreeMultiSource(root, selectedRuns, selectedDatasets);
            isDatasetTree = !selectedDatasets.isEmpty();  // Has datasets if any selected
        }

        // Apply filter pruning before reload
        pruneNonMatchingNodes(root);

        timeseriesTreeModel.reload();

        // Save pre-filter expansion state when first entering filter mode
        if (!filterText.isEmpty() && preFilterExpansionState == null) {
            preFilterExpansionState = new ArrayList<>(expandedPaths);
        }

        // Expansion logic
        boolean switchedContext = (isDatasetTree != hadDatasetPaths);
        if (!filterText.isEmpty()) {
            // Filter active - expand all to show matches in context
            for (int i = 0; i < timeseriesTree.getRowCount(); i++) {
                timeseriesTree.expandRow(i);
            }
        } else if (preFilterExpansionState != null) {
            // Filter just cleared - restore pre-filter expansion state
            for (TreePath expandedPath : preFilterExpansionState) {
                TreePath newPath = findEquivalentPath(expandedPath);
                if (newPath != null) {
                    timeseriesTree.expandPath(newPath);
                }
            }
            preFilterExpansionState = null;
        } else if (expandedPaths.isEmpty() || switchedContext) {
            // First time or switched context - expand all nodes
            for (int i = 0; i < timeseriesTree.getRowCount(); i++) {
                timeseriesTree.expandRow(i);
            }
        } else {
            // Restore previous expansion state (same context)
            for (TreePath expandedPath : expandedPaths) {
                TreePath newPath = findEquivalentPath(expandedPath);
                if (newPath != null) {
                    timeseriesTree.expandPath(newPath);
                }
            }
        }
    }

    /**
     * Populates the tree for a single source (run or dataset) using SeriesLeafNode objects.
     */
    private void updateTreeSingleSource(DefaultMutableTreeNode root, Object source) {
        List<String> seriesNames = getSeriesNamesCallback.apply(source);

        if (seriesNames != null && !seriesNames.isEmpty()) {
            seriesNames.sort(naturalCompareCallback::apply);
            for (String seriesName : seriesNames) {
                // Create standalone leaf node with showSeriesName=true (shows "ds_1 [Run_1]")
                SeriesLeafNode leafNode = new SeriesLeafNode(seriesName, source, true);
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(leafNode);
                addHierarchicalNodeToTree(root, seriesName, node);
            }
        } else {
            String message = getNoOutputsMessage(source);
            root.add(new DefaultMutableTreeNode(message));
        }
    }

    /**
     * Populates the tree for multiple sources (runs and/or datasets) using smart hybrid structure.
     */
    private void updateTreeMultiSource(DefaultMutableTreeNode root, List<Object> selectedRuns, List<Object> selectedDatasets) {
        // Map: series name -> list of sources (RunInfo or LoadedDatasetInfo) that have this series
        Map<String, List<Object>> seriesAvailability = new LinkedHashMap<>();

        // Collect all series from all selected sources
        List<Object> allSources = new ArrayList<>();
        allSources.addAll(selectedRuns);
        allSources.addAll(selectedDatasets);

        for (Object source : allSources) {
            List<String> seriesNames = getSeriesNamesCallback.apply(source);
            if (seriesNames != null) {
                for (String seriesName : seriesNames) {
                    seriesAvailability.computeIfAbsent(seriesName, k -> new ArrayList<>()).add(source);
                }
            }
        }

        if (seriesAvailability.isEmpty()) {
            root.add(new DefaultMutableTreeNode("No outputs available from selected sources"));
            return;
        }

        // Sort series names using natural sorting
        List<String> sortedSeries = new ArrayList<>(seriesAvailability.keySet());
        sortedSeries.sort(naturalCompareCallback::apply);

        // Build hybrid tree structure
        for (String seriesName : sortedSeries) {
            List<Object> sourcesWithSeries = seriesAvailability.get(seriesName);

            if (sourcesWithSeries.size() == 1) {
                // Only one source has this series - create standalone leaf node
                Object singleSource = sourcesWithSeries.get(0);
                SeriesLeafNode leafNode = new SeriesLeafNode(seriesName, singleSource, true);
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(leafNode);
                addHierarchicalNodeToTree(root, seriesName, node);
            } else {
                // Multiple sources have this series - create parent with children
                SeriesParentNode parentNode = new SeriesParentNode(seriesName, sourcesWithSeries);
                DefaultMutableTreeNode parentTreeNode = new DefaultMutableTreeNode(parentNode);

                // Add children for each source
                for (Object source : sourcesWithSeries) {
                    SeriesLeafNode childLeafNode = new SeriesLeafNode(seriesName, source, false);
                    parentTreeNode.add(new DefaultMutableTreeNode(childLeafNode));
                }

                addHierarchicalNodeToTree(root, seriesName, parentTreeNode);
            }
        }
    }

    /**
     * Adds a node to the tree while preserving hierarchical structure (dot-delimited paths).
     */
    private void addHierarchicalNodeToTree(DefaultMutableTreeNode root, String seriesName, DefaultMutableTreeNode nodeToAdd) {
        String[] parts = seriesName.split("\\.");
        if (parts.length == 1) {
            // No hierarchy, add directly
            root.add(nodeToAdd);
        } else {
            // Build hierarchy
            DefaultMutableTreeNode current = root;
            for (int i = 0; i < parts.length - 1; i++) {
                DefaultMutableTreeNode child = findOrCreateChild(current, parts[i]);
                current = child;
            }
            current.add(nodeToAdd);
        }
    }

    /**
     * Finds or creates a child node with the given name.
     */
    private DefaultMutableTreeNode findOrCreateChild(DefaultMutableTreeNode parent, String childName) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            Object userObject = child.getUserObject();
            if (userObject instanceof String && userObject.equals(childName)) {
                return child;
            }
        }
        // Not found, create new
        DefaultMutableTreeNode newChild = new DefaultMutableTreeNode(childName);
        parent.add(newChild);
        return newChild;
    }

    /**
     * Finds an equivalent path in the current tree based on node names.
     */
    private TreePath findEquivalentPath(TreePath oldPath) {
        if (oldPath == null || oldPath.getPathCount() <= 1) {
            return null;
        }

        // Build path of node names
        String[] pathNames = new String[oldPath.getPathCount() - 1]; // Skip root
        for (int i = 1; i < oldPath.getPathCount(); i++) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) oldPath.getPathComponent(i);
            pathNames[i - 1] = node.getUserObject().toString();
        }

        // Try to find equivalent path in current tree
        DefaultMutableTreeNode currentNode = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();
        List<Object> newPathComponents = new ArrayList<>();
        newPathComponents.add(currentNode);

        for (String pathName : pathNames) {
            DefaultMutableTreeNode foundChild = null;
            for (int i = 0; i < currentNode.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) currentNode.getChildAt(i);
                if (child.getUserObject().toString().equals(pathName)) {
                    foundChild = child;
                    break;
                }
            }

            if (foundChild == null) {
                return null; // Path doesn't exist in new tree
            }

            newPathComponents.add(foundChild);
            currentNode = foundChild;
        }

        return new TreePath(newPathComponents.toArray());
    }

    /**
     * Gets an appropriate message when no outputs are available.
     */
    private String getNoOutputsMessage(Object source) {
        // This is a simplified version - RunManager can customize based on RunStatus
        return "No outputs available";
    }

    /**
     * Debug helper to log tree structure.
     */
    public void logTreeStructure(DefaultMutableTreeNode node, int depth) {
        String indent = "  ".repeat(depth);
        Object userObj = node.getUserObject();
        String nodeDesc;
        if (userObj instanceof SeriesLeafNode leaf) {
            nodeDesc = "SeriesLeafNode: displayString='" + leaf + "', fullName='" + leaf.seriesName + "'";
        } else if (userObj instanceof SeriesParentNode) {
            nodeDesc = "SeriesParentNode: " + userObj;
        } else {
            nodeDesc = "String: '" + userObj + "'";
        }
        logger.info("{}└─ {}", indent, nodeDesc);

        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            logTreeStructure(child, depth + 1);
        }
    }

    /**
     * Recursively collects all SeriesLeafNode objects from a tree node.
     * If the node is a leaf, adds it directly. If it's a parent, recursively collects from children.
     * This enables selecting a parent node (like "node.node9") to plot all its children.
     *
     * Top-level folder nodes (direct children of root) are NOT recursively expanded to prevent
     * accidentally plotting hundreds of series.
     */
    public void collectLeafNodes(DefaultMutableTreeNode node, List<SeriesLeafNode> leaves) {
        if (node == null) return;

        Object userObject = node.getUserObject();

        // Check if this is a SeriesLeafNode (plottable leaf)
        if (userObject instanceof SeriesLeafNode) {
            leaves.add((SeriesLeafNode) userObject);
            return;
        }

        // Check if this is a SeriesParentNode (select all children)
        if (userObject instanceof SeriesParentNode) {
            // Add all runs for this series
            for (int i = 0; i < node.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                collectLeafNodes(child, leaves);
            }
            return;
        }

        // For regular folder nodes (String user objects), check depth
        if (!node.isLeaf() && userObject instanceof String) {
            // Check if this is a top-level folder (direct child of invisible root)
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();
            if (node.getParent() == root) {
                // Top-level folder - don't recurse to prevent accidental mass plotting
                // User must select specific sub-folders or series
                return;
            }

            // Not top-level - recurse normally
            for (int i = 0; i < node.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                collectLeafNodes(child, leaves);
            }
        }
    }

    /**
     * Check if a node represents a special message (like "No outputs available")
     */
    public static boolean isSpecialMessageNode(DefaultMutableTreeNode node) {
        String variableName = node.getUserObject().toString();
        return variableName.equals("No outputs available") ||
               variableName.equals("Outputs will appear when simulation completes") ||
               variableName.equals("No outputs available from selected runs") ||
               variableName.equals("No outputs available from selected sources") ||
               variableName.equals("No series available from this dataset") ||
               variableName.equals("Select one or more runs or datasets");
    }

    // ========== Filtering ==========

    /**
     * Removes nodes that don't match the current filter text.
     * Parent/intermediate nodes are kept if any descendant matches.
     * Called after the tree is fully built but before reload().
     */
    private void pruneNonMatchingNodes(DefaultMutableTreeNode parent) {
        if (filterText.isEmpty()) return;

        String lowerFilter = filterText.toLowerCase();

        // Work backwards to avoid index shifting during removal
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            if (!nodeMatchesFilter(child)) {
                parent.remove(i);
            } else if (child.getUserObject() instanceof String && !isSpecialMessageNode(child)) {
                // If this node's own text matches, keep all descendants intact
                String nodeText = child.getUserObject().toString().toLowerCase();
                if (!nodeText.contains(lowerFilter)) {
                    // Node kept only because of matching descendants - prune non-matching children
                    pruneNonMatchingNodes(child);
                    if (child.getChildCount() == 0) {
                        parent.remove(i);
                    }
                }
            }
        }
    }

    /**
     * Checks if a node or any of its descendants matches the current filter.
     * Matches against display text (toString()), case-insensitive.
     */
    private boolean nodeMatchesFilter(DefaultMutableTreeNode node) {
        if (filterText.isEmpty()) return true;

        String lowerFilter = filterText.toLowerCase();
        Object userObject = node.getUserObject();

        if (userObject != null && userObject.toString().toLowerCase().contains(lowerFilter)) {
            return true;
        }

        // Check descendants
        for (int i = 0; i < node.getChildCount(); i++) {
            if (nodeMatchesFilter((DefaultMutableTreeNode) node.getChildAt(i))) {
                return true;
            }
        }
        return false;
    }

    // ========== Inner Classes ==========

    /**
     * Represents a leaf node that is a plottable series from a specific source (run or dataset).
     */
    public static class SeriesLeafNode {
        public final String seriesName;
        public final Object source;  // The source object (RunInfo, LoadedDatasetInfo, etc.)
        public final boolean showSeriesName;  // If true, show "ds_1 [Run_1]", else just "Run_1"

        public SeriesLeafNode(String seriesName, Object source, boolean showSeriesName) {
            this.seriesName = seriesName;
            this.source = source;
            this.showSeriesName = showSeriesName;
        }

        @Override
        public String toString() {
            String sourceName = getSourceName(source);

            if (showSeriesName) {
                // Standalone leaf: show "ds_1 [Run_1]" or "ds_1 [data.csv]"
                String lastSegment = seriesName.contains(".")
                    ? seriesName.substring(seriesName.lastIndexOf('.') + 1)
                    : seriesName;
                return lastSegment + " [" + sourceName + "]";
            } else {
                // Child of parent node: just show "Run_1" or "data.csv"
                return sourceName;
            }
        }

        private String getSourceName(Object source) {
            // Use reflection to get the name field, works for any source type with a name field
            try {
                var nameField = source.getClass().getField("runName");
                return (String) nameField.get(source);
            } catch (NoSuchFieldException e) {
                try {
                    var nameField = source.getClass().getField("fileName");
                    return (String) nameField.get(source);
                } catch (NoSuchFieldException | IllegalAccessException ex) {
                    return source.toString();
                }
            } catch (IllegalAccessException e) {
                return source.toString();
            }
        }
    }

    /**
     * Represents a parent node for a series available in multiple sources (runs and/or datasets).
     */
    public static class SeriesParentNode {
        public final String seriesName;
        public final List<Object> sourcesWithSeries;  // List of source objects

        public SeriesParentNode(String seriesName, List<Object> sourcesWithSeries) {
            this.seriesName = seriesName;
            this.sourcesWithSeries = sourcesWithSeries;
        }

        @Override
        public String toString() {
            // Extract just the last segment of the series name (e.g., "ds_1" from "node.node9.ds_1")
            String lastSegment = seriesName.contains(".")
                ? seriesName.substring(seriesName.lastIndexOf('.') + 1)
                : seriesName;

            String[] sourceLabels = sourcesWithSeries.stream()
                .map(this::getSourceName)
                .toArray(String[]::new);
            return lastSegment + " [" + String.join(", ", sourceLabels) + "]";
        }

        private String getSourceName(Object source) {
            // Use reflection to get the name field, works for any source type
            try {
                var nameField = source.getClass().getField("runName");
                return (String) nameField.get(source);
            } catch (NoSuchFieldException e) {
                try {
                    var nameField = source.getClass().getField("fileName");
                    return (String) nameField.get(source);
                } catch (NoSuchFieldException | IllegalAccessException ex) {
                    return source.toString();
                }
            } catch (IllegalAccessException e) {
                return source.toString();
            }
        }
    }
}
