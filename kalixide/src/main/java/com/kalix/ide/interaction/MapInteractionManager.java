package com.kalix.ide.interaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kalix.ide.MapPanel;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelNode;

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
    private Point2D dragStartWorld;
    private final Map<String, Point2D> originalNodePositions;

    // Rotation state tracking
    private boolean isRotating = false;
    private Point2D rotationCenter;
    private double rotationStartAngle;
    
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
     * Check if a rotation operation can start.
     * Rotation requires at least 2 selected nodes.
     * @return true if rotation can start
     */
    public boolean canStartRotation() {
        return model.getSelectedNodeCount() >= 2;
    }

    /**
     * Start a drag operation (translation).
     * @param screenPoint Starting mouse position
     */
    public void startDrag(Point screenPoint) {
        startDrag(screenPoint, false);
    }

    /**
     * Start a drag or rotation operation.
     * @param screenPoint Starting mouse position
     * @param rotate If true, start a rotation instead of translation
     */
    public void startDrag(Point screenPoint, boolean rotate) {
        if (rotate) {
            if (!canStartRotation()) {
                return;
            }
        } else {
            if (!canStartDrag(screenPoint)) {
                return;
            }
        }

        isDragging = true;
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

        if (rotate) {
            isRotating = true;
            // Compute centroid of selected nodes
            double sumX = 0;
            double sumY = 0;
            for (Point2D pos : originalNodePositions.values()) {
                sumX += pos.getX();
                sumY += pos.getY();
            }
            int count = originalNodePositions.size();
            rotationCenter = new Point2D.Double(sumX / count, sumY / count);
            rotationStartAngle = Math.atan2(
                dragStartWorld.getY() - rotationCenter.getY(),
                dragStartWorld.getX() - rotationCenter.getX()
            );
        }
    }
    
    /**
     * Update drag positions during mouse drag.
     * Applies translation or rotation depending on the current mode.
     * @param currentScreenPoint Current mouse position
     */
    public void updateDrag(Point currentScreenPoint) {
        if (!isDragging) {
            return;
        }

        Point2D currentWorld = screenToWorld(currentScreenPoint);

        if (isRotating) {
            // Rotation mode: apply 2D rotation matrix around centroid
            double currentAngle = Math.atan2(
                currentWorld.getY() - rotationCenter.getY(),
                currentWorld.getX() - rotationCenter.getX()
            );
            double deltaAngle = currentAngle - rotationStartAngle;
            double cos = Math.cos(deltaAngle);
            double sin = Math.sin(deltaAngle);

            for (Map.Entry<String, Point2D> entry : originalNodePositions.entrySet()) {
                String nodeName = entry.getKey();
                Point2D originalPos = entry.getValue();

                double dx = originalPos.getX() - rotationCenter.getX();
                double dy = originalPos.getY() - rotationCenter.getY();
                double newX = rotationCenter.getX() + dx * cos - dy * sin;
                double newY = rotationCenter.getY() + dx * sin + dy * cos;

                model.updateNodePosition(nodeName, newX, newY);
            }
        } else {
            // Translation mode: apply delta offset
            double deltaX = currentWorld.getX() - dragStartWorld.getX();
            double deltaY = currentWorld.getY() - dragStartWorld.getY();

            for (Map.Entry<String, Point2D> entry : originalNodePositions.entrySet()) {
                String nodeName = entry.getKey();
                Point2D originalPos = entry.getValue();

                double newX = originalPos.getX() + deltaX;
                double newY = originalPos.getY() + deltaY;

                model.updateNodePosition(nodeName, newX, newY);
            }
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
            // Batch update all moved nodes as a single atomic operation
            java.util.Map<String, java.awt.geom.Point2D.Double> nodeUpdates = new java.util.HashMap<>();
            for (String nodeName : originalNodePositions.keySet()) {
                ModelNode node = model.getNode(nodeName);
                if (node != null) {
                    nodeUpdates.put(nodeName, new java.awt.geom.Point2D.Double(node.getX(), node.getY()));
                }
            }
            if (!nodeUpdates.isEmpty()) {
                textUpdater.updateNodeCoordinates(nodeUpdates);
            }
        }
        
        // Clear drag state
        originalNodePositions.clear();
        dragStartWorld = null;
        isRotating = false;
        rotationCenter = null;
    }

    /**
     * Check if currently dragging (translation or rotation).
     * @return true if drag operation is active
     */
    public boolean isDragging() {
        return isDragging;
    }

    /**
     * Check if currently rotating.
     * @return true if rotation operation is active
     */
    public boolean isRotating() {
        return isRotating;
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
     * Delete all currently selected nodes and links from both the model and text.
     * This orchestrates deletion across the data model and text editor.
     * All deletions are performed as a single atomic operation for undo support.
     */
    public void deleteSelectedElements() {
        Set<String> nodesToDelete = model.getSelectedNodes();
        Set<String> linksToDelete = model.getSelectedLinks();

        if (nodesToDelete.isEmpty() && linksToDelete.isEmpty()) {
            return; // Nothing to delete
        }

        // Delete from text first (single atomic operation handles all)
        if (textUpdater != null) {
            textUpdater.deleteSelectedElements(nodesToDelete, linksToDelete);
        }

        // Delete from the data model
        if (!nodesToDelete.isEmpty()) {
            model.deleteSelectedNodes();
        }
        if (!linksToDelete.isEmpty()) {
            model.deleteLinks(linksToDelete);
        }
    }
}