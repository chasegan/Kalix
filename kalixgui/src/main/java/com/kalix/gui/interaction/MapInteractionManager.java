package com.kalix.gui.interaction;

import com.kalix.gui.MapPanel;
import com.kalix.gui.model.HydrologicalModel;
import com.kalix.gui.model.ModelNode;

import java.awt.Point;
import java.awt.geom.Point2D;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Manages map interactions including node dragging and coordinate updates.
 * Handles the complex logic of dragging multiple selected nodes simultaneously
 * and synchronizing changes back to the text editor.
 */
public class MapInteractionManager {
    
    private final MapPanel mapPanel;
    private final HydrologicalModel model;
    
    // Drag state tracking
    private boolean isDragging = false;
    private Point dragStartScreen;
    private Point2D dragStartWorld;
    private Map<String, Point2D> originalNodePositions;
    
    // Reference to text coordinate updater (will be added later)
    private TextCoordinateUpdater textUpdater;
    
    public MapInteractionManager(MapPanel mapPanel, HydrologicalModel model) {
        this.mapPanel = mapPanel;
        this.model = model;
        this.originalNodePositions = new HashMap<>();
    }
    
    /**
     * Set the text coordinate updater for bidirectional sync.
     * @param textUpdater The text updater instance
     */
    public void setTextUpdater(TextCoordinateUpdater textUpdater) {
        this.textUpdater = textUpdater;
    }
    
    /**
     * Check if a drag operation can start at the given screen point.
     * Dragging can start if clicking on a selected node.
     * @param screenPoint Mouse press location
     * @return true if drag can start
     */
    public boolean canStartDrag(Point screenPoint) {
        String nodeAtPoint = mapPanel.getNodeAtPoint(screenPoint);
        return nodeAtPoint != null && model.isNodeSelected(nodeAtPoint);
    }
    
    /**
     * Start a drag operation.
     * @param screenPoint Starting mouse position
     */
    public void startDrag(Point screenPoint) {
        if (!canStartDrag(screenPoint)) {
            return;
        }
        
        isDragging = true;
        dragStartScreen = new Point(screenPoint);
        dragStartWorld = screenToWorld(screenPoint);
        
        // Store original positions of all selected nodes
        originalNodePositions.clear();
        Set<String> selectedNodes = model.getSelectedNodes();
        for (String nodeName : selectedNodes) {
            ModelNode node = model.getNode(nodeName);
            if (node != null) {
                originalNodePositions.put(nodeName, new Point2D.Double(node.getX(), node.getY()));
            }
        }
    }
    
    /**
     * Update drag positions during mouse drag.
     * @param currentScreenPoint Current mouse position
     */
    public void updateDrag(Point currentScreenPoint) {
        if (!isDragging) {
            return;
        }
        
        // Calculate world-space movement delta
        Point2D currentWorld = screenToWorld(currentScreenPoint);
        double deltaX = currentWorld.getX() - dragStartWorld.getX();
        double deltaY = currentWorld.getY() - dragStartWorld.getY();
        
        // Update positions of all selected nodes
        for (Map.Entry<String, Point2D> entry : originalNodePositions.entrySet()) {
            String nodeName = entry.getKey();
            Point2D originalPos = entry.getValue();
            
            double newX = originalPos.getX() + deltaX;
            double newY = originalPos.getY() + deltaY;
            
            // Update node position in model
            model.updateNodePosition(nodeName, newX, newY);
        }
    }
    
    /**
     * End the drag operation and commit changes.
     * @param endScreenPoint Final mouse position
     */
    public void endDrag(Point endScreenPoint) {
        if (!isDragging) {
            return;
        }
        
        isDragging = false;
        
        // Final position update
        updateDrag(endScreenPoint);
        
        // Sync changes to text editor if updater is available
        if (textUpdater != null) {
            for (String nodeName : originalNodePositions.keySet()) {
                ModelNode node = model.getNode(nodeName);
                if (node != null) {
                    textUpdater.updateNodeCoordinate(nodeName, node.getX(), node.getY());
                }
            }
        }
        
        // Clear drag state
        originalNodePositions.clear();
        dragStartScreen = null;
        dragStartWorld = null;
    }
    
    /**
     * Cancel the current drag operation and restore original positions.
     */
    public void cancelDrag() {
        if (!isDragging) {
            return;
        }
        
        // Restore original positions
        for (Map.Entry<String, Point2D> entry : originalNodePositions.entrySet()) {
            String nodeName = entry.getKey();
            Point2D originalPos = entry.getValue();
            model.updateNodePosition(nodeName, originalPos.getX(), originalPos.getY());
        }
        
        isDragging = false;
        originalNodePositions.clear();
        dragStartScreen = null;
        dragStartWorld = null;
    }
    
    /**
     * Check if currently dragging.
     * @return true if drag operation is active
     */
    public boolean isDragging() {
        return isDragging;
    }
    
    /**
     * Handle a node click for navigation purposes.
     * Scrolls the text editor to the clicked node's definition.
     * @param nodeName Name of the node that was clicked
     * @return true if the node was found and scrolled to, false otherwise
     */
    public boolean handleNodeClick(String nodeName) {
        if (textUpdater != null && nodeName != null) {
            return textUpdater.scrollToNode(nodeName);
        }
        return false;
    }
    
    /**
     * Convert screen coordinates to world coordinates.
     * @param screenPoint Screen coordinates
     * @return World coordinates
     */
    private Point2D screenToWorld(Point screenPoint) {
        double zoomLevel = mapPanel.getZoomLevel();
        double panX = mapPanel.getPanX();
        double panY = mapPanel.getPanY();
        
        double worldX = (screenPoint.x - panX) / zoomLevel;
        double worldY = (screenPoint.y - panY) / zoomLevel;
        
        return new Point2D.Double(worldX, worldY);
    }
    
    /**
     * Delete all currently selected nodes from both the model and text.
     * This orchestrates deletion across the data model and text editor.
     */
    public void deleteSelectedNodes() {
        Set<String> nodesToDelete = model.getSelectedNodes();
        
        if (nodesToDelete.isEmpty()) {
            return; // Nothing to delete
        }
        
        System.out.println("MapInteractionManager: Deleting " + nodesToDelete.size() + " selected nodes");
        
        // Delete from the data model first
        model.deleteSelectedNodes();
        
        // Delete corresponding sections from text if updater is available
        if (textUpdater != null) {
            textUpdater.deleteNodesFromText(nodesToDelete);
        }
        
        System.out.println("MapInteractionManager: Node deletion completed");
    }
}