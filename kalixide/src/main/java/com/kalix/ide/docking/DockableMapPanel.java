package com.kalix.ide.docking;

import com.kalix.ide.MapPanel;

import javax.swing.*;
import java.awt.*;

/**
 * A dockable version of the MapPanel for use in the docking system.
 * 
 * This wrapper allows the existing MapPanel to be used with the docking
 * system without requiring major modifications to the original component.
 */
public class DockableMapPanel extends DockablePanel {
    
    private MapPanel mapPanel;
    
    /**
     * Creates a new DockableMapPanel wrapping an existing MapPanel.
     * @param mapPanel The MapPanel to make dockable
     */
    public DockableMapPanel(MapPanel mapPanel) {
        super(new BorderLayout());
        this.mapPanel = mapPanel;
        
        setupPanel();
    }
    
    /**
     * Creates a new DockableMapPanel with a new MapPanel instance.
     */
    public DockableMapPanel() {
        this(new MapPanel());
    }
    
    /**
     * Sets up the wrapped MapPanel within this dockable panel.
     */
    private void setupPanel() {
        // Add the MapPanel to this dockable panel
        add(mapPanel, BorderLayout.CENTER);
        
        // Forward focus to the wrapped panel when this panel gains focus
        addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                if (mapPanel != null) {
                    mapPanel.requestFocusInWindow();
                }
            }
        });
        
        // Set preferred size based on MapPanel
        if (mapPanel.getPreferredSize() != null) {
            setPreferredSize(mapPanel.getPreferredSize());
        }
    }
    
    /**
     * Gets the wrapped MapPanel.
     * @return The MapPanel instance
     */
    public MapPanel getMapPanel() {
        return mapPanel;
    }
    
    @Override
    protected void onDetach() {
        super.onDetach();
        // MapPanel-specific cleanup when detaching
        // The MapPanel maintains its state, so no special cleanup needed
    }
    
    @Override
    protected void onAttach(Container newParent) {
        super.onAttach(newParent);
        // MapPanel-specific setup when attaching to new parent
        // Request focus to ensure map interactions work properly
        SwingUtilities.invokeLater(() -> {
            if (mapPanel != null) {
                mapPanel.requestFocusInWindow();
            }
        });
    }
    
    /**
     * Convenience method to forward common MapPanel methods.
     * This allows the dockable panel to act as a proxy for the MapPanel.
     */
    
    public void zoomIn() {
        if (mapPanel != null) {
            mapPanel.zoomIn();
        }
    }
    
    public void zoomOut() {
        if (mapPanel != null) {
            mapPanel.zoomOut();
        }
    }
    
    public void resetZoom() {
        if (mapPanel != null) {
            mapPanel.resetZoom();
        }
    }
    
    public void zoomToFit() {
        if (mapPanel != null) {
            mapPanel.zoomToFit();
        }
    }
    
    public void resetView() {
        if (mapPanel != null) {
            mapPanel.resetView();
        }
    }
    
    // Additional forwarding methods can be added as needed
    // for specific MapPanel functionality that needs to be accessible
    // from the docking system
}