package com.kalix.ide.docking;

import java.awt.*;

/**
 * Interface for areas that can accept docked panels.
 * 
 * Drop zones represent regions of the UI where dockable panels can be dropped
 * and integrated. They handle highlighting when panels are dragged over them
 * and manage the integration of panels when they are dropped.
 */
public interface DockZone {
    
    /**
     * Checks if the specified point (in screen coordinates) is within this drop zone.
     * @param screenPoint The point to check in screen coordinates
     * @return true if the point is within this drop zone
     */
    boolean containsPoint(Point screenPoint);
    
    /**
     * Checks if this drop zone can accept the specified panel.
     * @param panel The panel being dragged
     * @return true if the panel can be dropped here
     */
    boolean canAcceptPanel(DockablePanel panel);
    
    /**
     * Attempts to accept and integrate the specified panel.
     * @param panel The panel being dropped
     * @param dropPoint The drop position in screen coordinates
     * @return true if the panel was successfully integrated
     */
    boolean acceptPanel(DockablePanel panel, Point dropPoint);
    
    /**
     * Sets the highlight state of this drop zone.
     * @param highlighted true to highlight, false to remove highlighting
     */
    void setHighlighted(boolean highlighted);
    
    /**
     * Called when a drag operation starts.
     * @param panel The panel being dragged
     */
    void onDragStart(DockablePanel panel);
    
    /**
     * Called when a drag operation ends.
     * @param panel The panel that was being dragged
     */
    void onDragEnd(DockablePanel panel);
    
    /**
     * Gets the container that panels are added to when dropped in this zone.
     * @return The container component
     */
    Container getContainer();
}