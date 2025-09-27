package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;

/**
 * Basic implementation of a dock zone for panels and containers.
 * 
 * This implementation provides a simple rectangular drop zone that can accept
 * any dockable panel and add it to a specified container.
 */
public class BasicDockZone implements DockZone {
    
    private final Container targetContainer;
    private final Component targetComponent;
    private boolean highlighted = false;
    
    /**
     * Creates a dock zone for the specified container.
     * @param container The container that will receive dropped panels
     */
    public BasicDockZone(Container container) {
        this.targetContainer = container;
        this.targetComponent = container;
    }
    
    /**
     * Creates a dock zone for the specified component.
     * The component's parent container will receive dropped panels.
     * @param component The component that defines the drop zone bounds
     */
    public BasicDockZone(Component component) {
        this.targetComponent = component;
        this.targetContainer = component.getParent();
    }
    
    @Override
    public boolean containsPoint(Point screenPoint) {
        if (targetComponent == null || !targetComponent.isVisible()) {
            return false;
        }
        
        // Convert screen point to component coordinates
        Point localPoint = new Point(screenPoint);
        SwingUtilities.convertPointFromScreen(localPoint, targetComponent);
        
        return targetComponent.getBounds().contains(localPoint.x, localPoint.y);
    }
    
    @Override
    public boolean canAcceptPanel(DockablePanel panel) {
        // Basic implementation accepts any panel
        // Subclasses can override for more specific logic
        return panel != null && targetContainer != null;
    }
    
    @Override
    public boolean acceptPanel(DockablePanel panel, Point dropPoint) {
        if (!canAcceptPanel(panel)) {
            return false;
        }
        
        // Remove panel from its current parent
        Container currentParent = panel.getParent();
        if (currentParent != null) {
            currentParent.remove(panel);
            currentParent.revalidate();
            currentParent.repaint();
        }
        
        // Add panel to target container
        targetContainer.add(panel);
        targetContainer.revalidate();
        targetContainer.repaint();
        
        // Disable docking mode since panel is now docked
        panel.setDockingMode(false);
        
        return true;
    }
    
    @Override
    public void setHighlighted(boolean highlighted) {
        if (this.highlighted != highlighted) {
            this.highlighted = highlighted;
            
            // Trigger repaint of the component to show/hide highlight
            if (targetComponent != null) {
                targetComponent.repaint();
            }
        }
    }
    
    @Override
    public void onDragStart(DockablePanel panel) {
        // Default implementation does nothing
        // Subclasses can override for custom behavior
    }
    
    @Override
    public void onDragEnd(DockablePanel panel) {
        // Ensure highlighting is cleared
        setHighlighted(false);
    }
    
    @Override
    public Container getContainer() {
        return targetContainer;
    }
    
    /**
     * Returns whether this drop zone is currently highlighted.
     * @return true if highlighted
     */
    public boolean isHighlighted() {
        return highlighted;
    }
    
    /**
     * Gets the component that defines the bounds of this drop zone.
     * @return The target component
     */
    public Component getTargetComponent() {
        return targetComponent;
    }
}