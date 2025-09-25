package com.kalix.ide.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simplified data model for hydrological model visualization.
 * Optimized for thousands of nodes with fast lookup and change tracking.
 */
public class HydrologicalModel {
    private static final Logger logger = LoggerFactory.getLogger(HydrologicalModel.class);
    
    // Core data structures optimized for performance
    private final Map<String, ModelNode> nodes;
    private final List<ModelLink> links;
    private final SpatialIndex spatialIndex;
    
    // Change tracking for incremental updates
    private final Set<String> modifiedNodes;
    private final Set<String> modifiedLinks;
    private final Set<String> removedNodes;
    private final Set<String> removedLinks;
    
    // Version tracking for change detection
    private volatile long version = 0;
    
    // Previous parse result for incremental updates
    private ModelParser.ParseResult previousParseResult;
    
    // Change listeners for UI updates
    private final List<ModelChangeListener> changeListeners;
    
    // Selection tracking
    private final Set<String> selectedNodes;
    private final Set<String> selectedLinks;
    
    public HydrologicalModel() {
        // Use concurrent collections for thread safety
        this.nodes = new ConcurrentHashMap<>();
        this.links = new CopyOnWriteArrayList<>();
        this.spatialIndex = new SpatialIndex(100.0); // 100 unit grid cells
        
        // Change tracking
        this.modifiedNodes = ConcurrentHashMap.newKeySet();
        this.modifiedLinks = ConcurrentHashMap.newKeySet();
        this.removedNodes = ConcurrentHashMap.newKeySet();
        this.removedLinks = ConcurrentHashMap.newKeySet();
        
        // Listeners
        this.changeListeners = new CopyOnWriteArrayList<>();
        
        // Selection
        this.selectedNodes = ConcurrentHashMap.newKeySet();
        this.selectedLinks = ConcurrentHashMap.newKeySet();
    }
    
    /**
     * Add or update a node in the model
     */
    public void addNode(ModelNode node) {
        Objects.requireNonNull(node, "Node cannot be null");
        Objects.requireNonNull(node.getName(), "Node name cannot be null");
        
        // Remove old node from spatial index if it exists
        ModelNode oldNode = nodes.get(node.getName());
        if (oldNode != null) {
            spatialIndex.removeNode(oldNode);
        }
        
        nodes.put(node.getName(), node);
        spatialIndex.addNode(node);
        modifiedNodes.add(node.getName());
        removedNodes.remove(node.getName());
        
        incrementVersion();
        notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.NODE_ADDED, node.getName()));
    }
    
    /**
     * Remove a node from the model
     */
    public void removeNode(String nodeName) {
        Objects.requireNonNull(nodeName, "Node name cannot be null");
        
        ModelNode removed = nodes.remove(nodeName);
        if (removed != null) {
            spatialIndex.removeNode(removed);
            modifiedNodes.remove(nodeName);
            removedNodes.add(nodeName);
            
            incrementVersion();
            notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.NODE_REMOVED, nodeName));
        }
    }
    
    /**
     * Add a link to the model
     */
    public void addLink(ModelLink link) {
        Objects.requireNonNull(link, "Link cannot be null");
        
        links.add(link);
        String linkId = link.getUpstreamTerminus() + "->" + link.getDownstreamTerminus();
        modifiedLinks.add(linkId);
        removedLinks.remove(linkId);
        
        incrementVersion();
        notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.LINK_ADDED, linkId));
    }
    
    /**
     * Remove a link from the model
     */
    public void removeLink(ModelLink link) {
        Objects.requireNonNull(link, "Link cannot be null");
        
        if (links.remove(link)) {
            String linkId = link.getUpstreamTerminus() + "->" + link.getDownstreamTerminus();
            modifiedLinks.remove(linkId);
            removedLinks.add(linkId);
            
            incrementVersion();
            notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.LINK_REMOVED, linkId));
        }
    }
    
    /**
     * Get a node by name (thread-safe)
     */
    public ModelNode getNode(String nodeName) {
        return nodes.get(nodeName);
    }
    
    /**
     * Get all nodes (returns immutable view)
     */
    public Collection<ModelNode> getAllNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }
    
    /**
     * Get all links (returns immutable view)
     */
    public List<ModelLink> getAllLinks() {
        return Collections.unmodifiableList(links);
    }
    
    /**
     * Update model from parsed INI data
     */
    public void updateFromParsedData(ModelParser.ParseResult parseResult) {
        // Clear existing data
        nodes.clear();
        links.clear();
        spatialIndex.clear();
        
        // Add parsed nodes (batch operation for performance)
        for (ModelNode node : parseResult.getNodes()) {
            nodes.put(node.getName(), node);
            spatialIndex.addNode(node);
        }
        
        // Add parsed links
        links.addAll(parseResult.getLinks());
        
        // Clear change tracking since this is a full update
        clearChangeTracking();
        incrementVersion();
        notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.MODEL_RELOADED, null));
    }
    
    /**
     * Get all modified nodes since last clear
     */
    public Set<String> getModifiedNodes() {
        return Collections.unmodifiableSet(modifiedNodes);
    }
    
    /**
     * Get all modified links since last clear
     */
    public Set<String> getModifiedLinks() {
        return Collections.unmodifiableSet(modifiedLinks);
    }
    
    /**
     * Clear change tracking (call after processing changes)
     */
    public void clearChangeTracking() {
        modifiedNodes.clear();
        modifiedLinks.clear();
        removedNodes.clear();
        removedLinks.clear();
    }
    
    /**
     * Get current version (increases with each change)
     */
    public long getVersion() {
        return version;
    }
    
    /**
     * Get model statistics for performance monitoring
     */
    public ModelStatistics getStatistics() {
        return new ModelStatistics(
            nodes.size(),
            links.size(),
            modifiedNodes.size(),
            modifiedLinks.size(),
            version
        );
    }
    
    /**
     * Parse and update model from INI text (full update)
     */
    public void parseFromIniText(String iniText) {
        ModelParser.ParseResult result = ModelParser.parse(iniText);
        updateFromParsedData(result);
    }
    
    /**
     * Parse and incrementally update model from INI text
     */
    public void parseFromIniTextIncremental(String iniText) {
        ModelParser.ParseResult newResult = ModelParser.parse(iniText);
        
        if (previousParseResult == null) {
            // First parse - do full update
            updateFromParsedData(newResult);
            previousParseResult = newResult;
            return;
        }
        
        // Calculate diff and apply incremental updates
        ModelDiff diff = new ModelDiff(previousParseResult, newResult);
        
        if (!diff.hasChanges()) {
            // No changes - skip update
            return;
        }
        
        applyIncrementalUpdate(diff);
        previousParseResult = newResult;
    }
    
    /**
     * Apply incremental updates based on model diff
     */
    private void applyIncrementalUpdate(ModelDiff diff) {
        // Remove nodes
        for (String nodeName : diff.getRemovedNodes()) {
            ModelNode removedNode = nodes.remove(nodeName);
            if (removedNode != null) {
                spatialIndex.removeNode(removedNode);
                removedNodes.add(nodeName);
                modifiedNodes.remove(nodeName); // Clean up tracking
            }
        }
        
        // Add and modify nodes
        for (String nodeName : diff.getAddedNodes()) {
            ModelNode newNode = diff.getNodeUpdates().get(nodeName);
            if (newNode != null) {
                nodes.put(nodeName, newNode);
                spatialIndex.addNode(newNode);
                modifiedNodes.add(nodeName);
                removedNodes.remove(nodeName); // Clean up tracking
            }
        }
        
        for (String nodeName : diff.getModifiedNodes()) {
            // Remove old node from spatial index
            ModelNode oldNode = nodes.get(nodeName);
            if (oldNode != null) {
                spatialIndex.removeNode(oldNode);
            }
            
            // Add updated node
            ModelNode newNode = diff.getNodeUpdates().get(nodeName);
            if (newNode != null) {
                nodes.put(nodeName, newNode);
                spatialIndex.addNode(newNode);
                modifiedNodes.add(nodeName);
            }
        }
        
        // Update links if they changed
        int affectedLinkCount = 0;
        if (diff.areLinksChanged()) {
            links.clear();
            links.addAll(diff.getNewLinks());
            // Clear and rebuild link change tracking
            modifiedLinks.clear();
            removedLinks.clear();
            for (ModelLink link : diff.getNewLinks()) {
                String linkId = link.getUpstreamTerminus() + "->" + link.getDownstreamTerminus();
                modifiedLinks.add(linkId);
            }
            affectedLinkCount = diff.getNewLinks().size();
        }
        
        incrementVersion();
        
        // Send single consolidated event with total affected counts
        int totalAffectedNodes = diff.getAddedNodes().size() + diff.getRemovedNodes().size() + diff.getModifiedNodes().size();
        if (totalAffectedNodes > 0 || affectedLinkCount > 0) {
            notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.MODEL_RELOADED, "incremental", 
                totalAffectedNodes, affectedLinkCount));
        }
    }
    
    // Spatial query methods for performance
    
    /**
     * Get nodes within a rectangular region (for viewport rendering)
     */
    public List<ModelNode> getNodesInRegion(double minX, double minY, double maxX, double maxY) {
        return spatialIndex.getNodesInRegion(minX, minY, maxX, maxY);
    }
    
    /**
     * Get nodes near a point (for mouse interaction)
     */
    public List<ModelNode> getNodesNear(double x, double y) {
        return spatialIndex.getNodesNear(x, y);
    }
    
    /**
     * Get spatial index statistics for monitoring
     */
    public SpatialIndex.IndexStatistics getSpatialStatistics() {
        return spatialIndex.getStatistics();
    }
    
    // Change listener management
    
    public void addChangeListener(ModelChangeListener listener) {
        changeListeners.add(listener);
    }
    
    public void removeChangeListener(ModelChangeListener listener) {
        changeListeners.remove(listener);
    }
    
    // Internal helper methods
    
    private void incrementVersion() {
        version++;
    }
    
    private void notifyListeners(ModelChangeEvent event) {
        for (ModelChangeListener listener : changeListeners) {
            try {
                listener.onModelChanged(event);
            } catch (Exception e) {
                // Log error but don't break other listeners
                logger.warn("Error in model change listener: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Clear all data (for new model)
     */
    public void clear() {
        nodes.clear();
        links.clear();
        spatialIndex.clear();
        
        modifiedNodes.clear();
        modifiedLinks.clear();
        removedNodes.clear();
        removedLinks.clear();
        selectedNodes.clear();
        selectedLinks.clear();

        incrementVersion();
        notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.MODEL_CLEARED, null));
    }
    
    // Selection management
    
    /**
     * Select a node, optionally adding to current selection
     * @param nodeName Name of node to select
     * @param addToSelection If true, add to selection; if false, replace selection
     */
    public void selectNode(String nodeName, boolean addToSelection) {
        if (!nodes.containsKey(nodeName)) {
            return; // Node doesn't exist
        }
        
        if (!addToSelection) {
            selectedNodes.clear();
            selectedLinks.clear();
        }
        
        selectedNodes.add(nodeName);
        incrementVersion();
        notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.NODE_SELECTED, nodeName));
    }
    
    /**
     * Deselect a specific node
     * @param nodeName Name of node to deselect
     */
    public void deselectNode(String nodeName) {
        if (selectedNodes.remove(nodeName)) {
            incrementVersion();
            notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.NODE_DESELECTED, nodeName));
        }
    }
    
    /**
     * Clear all selections (nodes and links)
     */
    public void clearSelection() {
        boolean hadSelection = !selectedNodes.isEmpty() || !selectedLinks.isEmpty();
        if (hadSelection) {
            selectedNodes.clear();
            selectedLinks.clear();
            incrementVersion();
            notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.SELECTION_CLEARED, null));
        }
    }
    
    /**
     * Check if a node is selected
     * @param nodeName Name of node to check
     * @return true if node is selected
     */
    public boolean isNodeSelected(String nodeName) {
        return selectedNodes.contains(nodeName);
    }
    
    /**
     * Get all selected node names
     * @return Set of selected node names (defensive copy)
     */
    public Set<String> getSelectedNodes() {
        return new HashSet<>(selectedNodes);
    }
    
    /**
     * Get count of selected nodes
     * @return Number of selected nodes
     */
    public int getSelectedNodeCount() {
        return selectedNodes.size();
    }

    // Link selection management

    /**
     * Create a unique ID for a link based on its upstream and downstream nodes
     * @param link The link to create an ID for
     * @return String ID for the link
     */
    private String getLinkId(ModelLink link) {
        return link.getUpstreamTerminus() + "->" + link.getDownstreamTerminus();
    }

    /**
     * Select a link, optionally adding to current selection
     * @param link Link to select
     * @param addToSelection If true, add to selection; if false, replace selection
     */
    public void selectLink(ModelLink link, boolean addToSelection) {
        if (link == null || !links.contains(link)) {
            return; // Link doesn't exist
        }

        if (!addToSelection) {
            selectedNodes.clear();
            selectedLinks.clear();
        }

        String linkId = getLinkId(link);
        selectedLinks.add(linkId);
        incrementVersion();
        notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.LINK_SELECTED, linkId));
    }

    /**
     * Deselect a specific link
     * @param link Link to deselect
     */
    public void deselectLink(ModelLink link) {
        if (link == null) {
            return;
        }

        String linkId = getLinkId(link);
        if (selectedLinks.remove(linkId)) {
            incrementVersion();
            notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.LINK_DESELECTED, linkId));
        }
    }

    /**
     * Check if a link is selected
     * @param link Link to check
     * @return true if link is selected
     */
    public boolean isLinkSelected(ModelLink link) {
        if (link == null) {
            return false;
        }
        return selectedLinks.contains(getLinkId(link));
    }

    /**
     * Get all selected link IDs
     * @return Set of selected link IDs (defensive copy)
     */
    public Set<String> getSelectedLinks() {
        return new HashSet<>(selectedLinks);
    }

    /**
     * Get count of selected links
     * @return Number of selected links
     */
    public int getSelectedLinkCount() {
        return selectedLinks.size();
    }

    /**
     * Get total count of selected items (nodes + links)
     * @return Total number of selected items
     */
    public int getTotalSelectedCount() {
        return selectedNodes.size() + selectedLinks.size();
    }
    
    // Node coordinate updates for dragging
    
    /**
     * Update the position of a node (for dragging operations)
     * @param nodeName Name of the node to update
     * @param x New X coordinate
     * @param y New Y coordinate
     */
    public void updateNodePosition(String nodeName, double x, double y) {
        ModelNode existingNode = nodes.get(nodeName);
        if (existingNode == null) {
            return; // Node doesn't exist
        }
        
        // Create a new node with updated coordinates (ModelNode is immutable)
        ModelNode updatedNode = new ModelNode(existingNode.getName(), existingNode.getType(), x, y);
        
        // Update in spatial index
        spatialIndex.removeNode(existingNode);
        spatialIndex.addNode(updatedNode);
        
        // Update in main collection
        nodes.put(nodeName, updatedNode);
        modifiedNodes.add(nodeName);
        removedNodes.remove(nodeName);
        
        incrementVersion();
        notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.NODE_MODIFIED, nodeName));
    }
    
    /**
     * Delete all currently selected nodes from the model.
     * Removes nodes from all collections, spatial index, and clears selection.
     */
    public void deleteSelectedNodes() {
        Set<String> nodesToDelete = new HashSet<>(selectedNodes);
        
        if (nodesToDelete.isEmpty()) {
            return; // Nothing to delete
        }
        
        // Remove nodes from main collection and spatial index
        for (String nodeName : nodesToDelete) {
            ModelNode nodeToRemove = nodes.get(nodeName);
            if (nodeToRemove != null) {
                // Remove from spatial index
                spatialIndex.removeNode(nodeToRemove);
                
                // Remove from main collection
                nodes.remove(nodeName);
                
                // Update change tracking
                modifiedNodes.remove(nodeName);
                removedNodes.add(nodeName);
                
                // Notify of individual node removal
                notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.NODE_REMOVED, nodeName));
            }
        }
        
        // Clear selection since deleted nodes can't be selected
        selectedNodes.clear();
        
        // Update version and notify of selection clear
        incrementVersion();
        notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.SELECTION_CLEARED, null));
        
        System.out.println("HydrologicalModel: Deleted " + nodesToDelete.size() + " selected nodes");
    }
}