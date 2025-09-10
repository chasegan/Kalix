package com.kalix.gui.model;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simplified data model for hydrological model visualization.
 * Optimized for thousands of nodes with fast lookup and change tracking.
 */
public class HydrologicalModel {
    
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
    
    // Change listeners for UI updates
    private final List<ModelChangeListener> changeListeners;
    
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
     * Parse and update model from INI text
     */
    public void parseFromIniText(String iniText) {
        ModelParser.ParseResult result = ModelParser.parse(iniText);
        updateFromParsedData(result);
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
                System.err.println("Error in model change listener: " + e.getMessage());
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
        
        incrementVersion();
        notifyListeners(new ModelChangeEvent(ModelChangeEvent.Type.MODEL_CLEARED, null));
    }
}