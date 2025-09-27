package com.kalix.ide.docking;

import com.kalix.ide.MapPanel;
import java.awt.BorderLayout;

/**
 * A dockable wrapper for the MapPanel component.
 * Extends DockablePanel to provide docking functionality while
 * maintaining all the original MapPanel features.
 */
public class DockableMapPanel extends DockablePanel {

    private MapPanel mapPanel;

    public DockableMapPanel() {
        super(new BorderLayout());
        initializeMapPanel();
    }

    /**
     * Initializes the wrapped MapPanel and adds it to this dockable container.
     */
    private void initializeMapPanel() {
        mapPanel = new MapPanel();
        add(mapPanel, BorderLayout.CENTER);

        // Forward key events from child to parent for docking functionality
        setupKeyEventForwarding();

        // Register services with the docking context
        DockingContext context = DockingContext.getInstance();
        context.registerService("mapPanel", mapPanel);
        context.registerService(MapPanel.class, mapPanel);
    }

    /**
     * Sets up key event forwarding from the child MapPanel to this DockablePanel.
     * This ensures F9 events reach the docking system even when the child has focus.
     */
    private void setupKeyEventForwarding() {
        mapPanel.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                // Forward F9 events to the parent DockablePanel
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_F9) {
                    // Dispatch the event to this DockablePanel's key listeners
                    for (java.awt.event.KeyListener listener : getKeyListeners()) {
                        listener.keyPressed(e);
                    }
                }
            }

            @Override
            public void keyReleased(java.awt.event.KeyEvent e) {
                // Forward F9 events to the parent DockablePanel
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_F9) {
                    // Dispatch the event to this DockablePanel's key listeners
                    for (java.awt.event.KeyListener listener : getKeyListeners()) {
                        listener.keyReleased(e);
                    }
                }
            }
        });

        // Ensure the MapPanel can receive focus for key events
        mapPanel.setFocusable(true);
    }

    /**
     * Returns the wrapped MapPanel instance.
     */
    public MapPanel getMapPanel() {
        return mapPanel;
    }

    /**
     * Delegates method calls to the wrapped MapPanel for convenience.
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

    @Override
    public String toString() {
        return "Map Panel";
    }
}