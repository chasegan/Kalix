package com.kalix.ide.docking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kalix.ide.MapPanel;
import java.awt.BorderLayout;

/**
 * A dockable wrapper for the MapPanel component.
 * Extends DockablePanel to provide docking functionality while
 * maintaining all the original MapPanel features.
 */
public class DockableMapPanel extends DockablePanel {
    private static final Logger logger = LoggerFactory.getLogger(DockableMapPanel.class);

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
     * Sets up key and mouse event forwarding from the child MapPanel to this DockablePanel.
     * This ensures F9 events and mouse hover events reach the docking system even when the child has focus.
     */
    private void setupKeyEventForwarding() {
        // Set up mouse event forwarding for hover detection
        mapPanel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                // Forward to parent's mouse listeners
                for (java.awt.event.MouseListener listener : getMouseListeners()) {
                    listener.mouseEntered(e);
                }
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                // Forward to parent's mouse listeners
                for (java.awt.event.MouseListener listener : getMouseListeners()) {
                    listener.mouseExited(e);
                }
            }
        });
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


    @Override
    public void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);

        // Force comprehensive layout update
        if (mapPanel != null) {
            // Force the layout manager to recalculate
            doLayout();

            // Explicitly set MapPanel bounds to fill this container
            mapPanel.setBounds(0, 0, width, height);

            // Force MapPanel to update its size
            mapPanel.invalidate();
            mapPanel.revalidate();
            mapPanel.repaint();

            // Also force this container to update
            invalidate();
            revalidate();
            repaint();
        }
    }

    @Override
    public String toString() {
        return "Map Panel";
    }

}