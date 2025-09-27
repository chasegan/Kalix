package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Central coordinator for docking operations.
 * 
 * Manages drag and drop operations, tracks valid drop zones, handles panel
 * detachment and reattachment, and creates new docking windows as needed.
 * 
 * This class implements the singleton pattern as there should be only one
 * docking manager coordinating all docking operations in the application.
 */
public class DockingManager {
    
    private static DockingManager instance;
    
    private DockablePanel currentlyDragging;
    private boolean isDragging = false;
    private Point dragStartPoint;
    private Point currentDragPoint;
    private List<DockZone> dropZones;
    private DragGlassPane glassPane;
    private Image dragPreviewImage;
    
    /**
     * Private constructor for singleton pattern.
     */
    private DockingManager() {
        dropZones = new ArrayList<>();
        glassPane = new DragGlassPane();
    }
    
    /**
     * Gets the singleton instance of the DockingManager.
     * @return The DockingManager instance
     */
    public static synchronized DockingManager getInstance() {
        if (instance == null) {
            instance = new DockingManager();
        }
        return instance;
    }
    
    /**
     * Registers a drop zone where panels can be docked.
     * @param dropZone The drop zone to register
     */
    public void registerDropZone(DockZone dropZone) {
        if (!dropZones.contains(dropZone)) {
            dropZones.add(dropZone);
        }
    }
    
    /**
     * Unregisters a drop zone.
     * @param dropZone The drop zone to unregister
     */
    public void unregisterDropZone(DockZone dropZone) {
        dropZones.remove(dropZone);
    }
    
    /**
     * Starts a drag operation for the specified panel.
     * @param panel The panel being dragged
     */
    public void startDrag(DockablePanel panel) {
        if (isDragging) return; // Already dragging something
        
        currentlyDragging = panel;
        isDragging = true;
        
        // Create preview image of the panel
        createDragPreviewImage(panel);
        
        // Set up glass pane for drag feedback
        setupGlassPane(panel);
        
        // Notify all drop zones about drag start
        for (DockZone zone : dropZones) {
            zone.onDragStart(panel);
        }
    }
    
    /**
     * Updates the drag operation with the current mouse position.
     * @param screenPoint The current mouse position in screen coordinates
     */
    public void updateDrag(Point screenPoint) {
        if (!isDragging || currentlyDragging == null) return;
        
        currentDragPoint = screenPoint;
        
        // Update drop zone highlights
        updateDropZoneHighlights(screenPoint);
        
        // Update glass pane
        if (glassPane != null) {
            glassPane.updateDragPosition(screenPoint);
        }
    }
    
    /**
     * Finishes the drag operation and handles the drop.
     * @param screenPoint The final drop position in screen coordinates
     */
    public void finishDrag(Point screenPoint) {
        if (!isDragging || currentlyDragging == null) return;
        
        try {
            // Find the drop zone at the current position
            DockZone targetZone = findDropZoneAt(screenPoint);
            
            if (targetZone != null && targetZone.canAcceptPanel(currentlyDragging)) {
                // Drop into existing zone
                handleDropInZone(targetZone, screenPoint);
            } else {
                // Create new detached window
                handleDetachPanel(screenPoint);
            }
            
        } finally {
            // Clean up drag state
            cleanupDragOperation();
        }
    }
    
    /**
     * Cancels the current drag operation.
     */
    public void cancelDrag() {
        if (isDragging) {
            cleanupDragOperation();
        }
    }
    
    /**
     * Creates a preview image of the panel being dragged.
     * @param panel The panel to create a preview of
     */
    private void createDragPreviewImage(DockablePanel panel) {
        if (panel.getWidth() > 0 && panel.getHeight() > 0) {
            dragPreviewImage = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = ((BufferedImage) dragPreviewImage).createGraphics();
            
            try {
                // Render panel to image
                panel.paint(g2d);
            } finally {
                g2d.dispose();
            }
        } else {
            dragPreviewImage = null;
        }
    }
    
    /**
     * Sets up the glass pane for drag feedback.
     * @param panel The panel being dragged
     */
    private void setupGlassPane(DockablePanel panel) {
        // Find the root pane to attach the glass pane to
        JRootPane rootPane = SwingUtilities.getRootPane(panel);
        if (rootPane != null) {
            glassPane.setPreviewImage(dragPreviewImage);
            glassPane.setPreviewSize(panel.getSize());
            glassPane.setVisible(true);
            rootPane.setGlassPane(glassPane);
        }
    }
    
    /**
     * Updates drop zone highlights based on the current drag position.
     * @param screenPoint The current drag position in screen coordinates
     */
    private void updateDropZoneHighlights(Point screenPoint) {
        for (DockZone zone : dropZones) {
            boolean isOver = zone.containsPoint(screenPoint);
            zone.setHighlighted(isOver && zone.canAcceptPanel(currentlyDragging));
        }
    }
    
    /**
     * Finds the drop zone at the specified screen coordinates.
     * @param screenPoint The screen coordinates to check
     * @return The drop zone at the position, or null if none found
     */
    private DockZone findDropZoneAt(Point screenPoint) {
        for (DockZone zone : dropZones) {
            if (zone.containsPoint(screenPoint) && zone.canAcceptPanel(currentlyDragging)) {
                return zone;
            }
        }
        return null;
    }
    
    /**
     * Handles dropping a panel into a specific zone.
     * @param zone The target zone
     * @param dropPoint The drop position
     */
    private void handleDropInZone(DockZone zone, Point dropPoint) {
        if (zone.acceptPanel(currentlyDragging, dropPoint)) {
            // Panel was successfully docked
            currentlyDragging.onAttach(zone.getContainer());
        }
    }
    
    /**
     * Handles detaching a panel to create a new window.
     * @param dropPoint The position where the new window should be created
     */
    private void handleDetachPanel(Point dropPoint) {
        // Remove panel from its current parent
        Container parent = currentlyDragging.getParent();
        if (parent != null) {
            parent.remove(currentlyDragging);
            parent.revalidate();
            parent.repaint();
        }
        
        // Notify panel about detachment
        currentlyDragging.onDetach();
        
        // Create new docking window
        DockingWindow newWindow = new DockingWindow(currentlyDragging);
        newWindow.setLocation(dropPoint.x - 50, dropPoint.y - 50); // Offset slightly from cursor
        newWindow.setVisible(true);
        
        // The panel is now attached to the new window
        currentlyDragging.onAttach(newWindow.getContentPane());
    }
    
    /**
     * Cleans up after a drag operation completes.
     */
    private void cleanupDragOperation() {
        // Hide glass pane
        if (glassPane != null) {
            glassPane.setVisible(false);
        }
        
        // Clear drop zone highlights
        for (DockZone zone : dropZones) {
            zone.setHighlighted(false);
            zone.onDragEnd(currentlyDragging);
        }
        
        // Reset state
        currentlyDragging = null;
        isDragging = false;
        dragStartPoint = null;
        currentDragPoint = null;
        dragPreviewImage = null;
    }
    
    /**
     * Glass pane used for drag feedback during docking operations.
     */
    private static class DragGlassPane extends JComponent implements MouseMotionListener {
        
        private Image previewImage;
        private Dimension previewSize;
        private Point dragPosition;
        
        public DragGlassPane() {
            setOpaque(false);
            addMouseMotionListener(this);
        }
        
        public void setPreviewImage(Image image) {
            this.previewImage = image;
        }
        
        public void setPreviewSize(Dimension size) {
            this.previewSize = size;
        }
        
        public void updateDragPosition(Point screenPoint) {
            // Convert to glass pane coordinates
            Point localPoint = new Point(screenPoint);
            SwingUtilities.convertPointFromScreen(localPoint, this);
            this.dragPosition = localPoint;
            repaint();
        }
        
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            
            if (dragPosition != null && previewSize != null) {
                Rectangle previewBounds = new Rectangle(
                    dragPosition.x - previewSize.width / 2,
                    dragPosition.y - previewSize.height / 2,
                    previewSize.width,
                    previewSize.height
                );
                
                DockHighlighter.paintDragPreview(g, previewBounds, previewImage);
            }
        }
        
        @Override
        public void mouseDragged(MouseEvent e) {
            // Forward to docking manager if needed
        }
        
        @Override
        public void mouseMoved(MouseEvent e) {
            // Not used
        }
    }
}