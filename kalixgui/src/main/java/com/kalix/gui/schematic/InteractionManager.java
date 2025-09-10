package com.kalix.gui.schematic;

import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages user interactions with the schematic view.
 * Handles mouse events for pan, zoom, selection, and node manipulation.
 */
public class InteractionManager {
    
    private final ViewportManager viewport;
    
    // Mouse state
    private boolean isDragging = false;
    private boolean isPanning = false;
    private int lastMouseX = 0;
    private int lastMouseY = 0;
    
    // Selection state
    private List<SchematicNode> selectedNodes;
    private SchematicNode hoveredNode = null;
    
    // Interaction settings
    private static final float NODE_SELECTION_TOLERANCE = 5.0f; // pixels
    private static final float ZOOM_SENSITIVITY = 0.1f;
    private static final int PAN_BUTTON = MouseEvent.BUTTON2; // Middle mouse button
    
    public InteractionManager(ViewportManager viewport) {
        this.viewport = viewport;
        this.selectedNodes = new ArrayList<>();
    }
    
    /**
     * Handle mouse press events
     */
    public void handleMousePressed(MouseEvent e) {
        lastMouseX = e.getX();
        lastMouseY = e.getY();
        
        if (e.getButton() == PAN_BUTTON || 
           (e.getButton() == MouseEvent.BUTTON1 && e.isControlDown())) {
            // Start panning
            isPanning = true;
            isDragging = true;
        } else if (e.getButton() == MouseEvent.BUTTON1) {
            // Left click - selection or node dragging
            isDragging = true;
        }
    }
    
    /**
     * Handle mouse drag events
     */
    public void handleMouseDragged(MouseEvent e) {
        if (!isDragging) return;
        
        int deltaX = e.getX() - lastMouseX;
        int deltaY = e.getY() - lastMouseY;
        
        if (isPanning) {
            // Pan the viewport
            viewport.pan(deltaX, deltaY);
        } else {
            // Check if we're dragging selected nodes
            if (!selectedNodes.isEmpty()) {
                // Convert screen delta to world delta
                float worldDeltaX = deltaX / viewport.getZoom();
                float worldDeltaY = -deltaY / viewport.getZoom(); // Flip Y axis
                
                // Move all selected nodes
                for (SchematicNode node : selectedNodes) {
                    node.setPosition(node.getX() + worldDeltaX, node.getY() + worldDeltaY);
                }
            }
        }
        
        lastMouseX = e.getX();
        lastMouseY = e.getY();
    }
    
    /**
     * Handle mouse release events
     */
    public void handleMouseReleased(MouseEvent e) {
        isDragging = false;
        isPanning = false;
    }
    
    /**
     * Handle mouse wheel events for zooming
     */
    public void handleMouseWheel(MouseWheelEvent e) {
        float zoomFactor = 1.0f - (e.getWheelRotation() * ZOOM_SENSITIVITY);
        viewport.zoomAtPoint(e.getX(), e.getY(), zoomFactor);
    }
    
    /**
     * Handle mouse click events for selection
     */
    public void handleMouseClicked(MouseEvent e, List<SchematicNode> nodes) {
        if (e.getButton() != MouseEvent.BUTTON1) return;
        
        // Convert screen coordinates to world coordinates
        Point2D.Float worldPoint = viewport.screenToWorld(e.getX(), e.getY());
        
        // Find node under cursor
        SchematicNode clickedNode = findNodeAt(worldPoint.x, worldPoint.y, nodes);
        
        if (clickedNode != null) {
            // Node selection logic
            if (e.isControlDown() || e.isMetaDown()) {
                // Toggle selection (multi-select)
                if (selectedNodes.contains(clickedNode)) {
                    selectedNodes.remove(clickedNode);
                    clickedNode.setSelected(false);
                } else {
                    selectedNodes.add(clickedNode);
                    clickedNode.setSelected(true);
                }
            } else {
                // Single selection
                clearSelection();
                selectedNodes.add(clickedNode);
                clickedNode.setSelected(true);
            }
        } else {
            // Clicked on empty space - clear selection
            if (!e.isControlDown() && !e.isMetaDown()) {
                clearSelection();
            }
        }
    }
    
    /**
     * Handle mouse movement for hover effects
     */
    public void handleMouseMoved(MouseEvent e, List<SchematicNode> nodes) {
        Point2D.Float worldPoint = viewport.screenToWorld(e.getX(), e.getY());
        SchematicNode newHoveredNode = findNodeAt(worldPoint.x, worldPoint.y, nodes);
        
        // Update hover state
        if (hoveredNode != newHoveredNode) {
            if (hoveredNode != null) {
                hoveredNode.setHighlighted(false);
            }
            
            hoveredNode = newHoveredNode;
            
            if (hoveredNode != null) {
                hoveredNode.setHighlighted(true);
            }
        }
    }
    
    /**
     * Find node at world coordinates
     */
    private SchematicNode findNodeAt(float worldX, float worldY, List<SchematicNode> nodes) {
        // Check nodes in reverse order (top to bottom in rendering order)
        for (int i = nodes.size() - 1; i >= 0; i--) {
            SchematicNode node = nodes.get(i);
            if (node.contains(worldX, worldY)) {
                return node;
            }
        }
        return null;
    }
    
    /**
     * Clear all selections
     */
    public void clearSelection() {
        for (SchematicNode node : selectedNodes) {
            node.setSelected(false);
        }
        selectedNodes.clear();
    }
    
    /**
     * Select all nodes
     */
    public void selectAll(List<SchematicNode> nodes) {
        clearSelection();
        for (SchematicNode node : nodes) {
            selectedNodes.add(node);
            node.setSelected(true);
        }
    }
    
    /**
     * Select nodes within a rectangle
     */
    public void selectInRectangle(float x1, float y1, float x2, float y2, List<SchematicNode> nodes) {
        float minX = Math.min(x1, x2);
        float maxX = Math.max(x1, x2);
        float minY = Math.min(y1, y2);
        float maxY = Math.max(y1, y2);
        
        for (SchematicNode node : nodes) {
            if (node.getX() >= minX && node.getX() <= maxX &&
                node.getY() >= minY && node.getY() <= maxY) {
                if (!selectedNodes.contains(node)) {
                    selectedNodes.add(node);
                    node.setSelected(true);
                }
            }
        }
    }
    
    /**
     * Delete selected nodes
     */
    public List<SchematicNode> deleteSelectedNodes() {
        List<SchematicNode> deletedNodes = new ArrayList<>(selectedNodes);
        clearSelection();
        return deletedNodes;
    }
    
    /**
     * Check if currently dragging nodes
     */
    public boolean isDraggingNodes() {
        return isDragging && !isPanning && !selectedNodes.isEmpty();
    }
    
    /**
     * Check if currently panning
     */
    public boolean isPanning() {
        return isPanning;
    }
    
    /**
     * Get world coordinates of last mouse position
     */
    public Point2D.Float getLastMouseWorldPosition() {
        return viewport.screenToWorld(lastMouseX, lastMouseY);
    }
    
    // Getters and setters
    
    public List<SchematicNode> getSelectedNodes() {
        return new ArrayList<>(selectedNodes);
    }
    
    public void setSelectedNodes(List<SchematicNode> nodes) {
        clearSelection();
        for (SchematicNode node : nodes) {
            selectedNodes.add(node);
            node.setSelected(true);
        }
    }
    
    public SchematicNode getHoveredNode() {
        return hoveredNode;
    }
    
    public boolean hasSelection() {
        return !selectedNodes.isEmpty();
    }
    
    public int getSelectionCount() {
        return selectedNodes.size();
    }
}