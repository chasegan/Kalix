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
                logger.info("Mouse entered MapPanel, forwarding to DockablePanel");
                // Forward to parent's mouse listeners
                for (java.awt.event.MouseListener listener : getMouseListeners()) {
                    listener.mouseEntered(e);
                }
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                logger.info("Mouse exited MapPanel, forwarding to DockablePanel");
                // Forward to parent's mouse listeners
                for (java.awt.event.MouseListener listener : getMouseListeners()) {
                    listener.mouseExited(e);
                }
            }
        });
        mapPanel.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                logger.info("Key pressed in MapPanel: {} (code: {})", java.awt.event.KeyEvent.getKeyText(e.getKeyCode()), e.getKeyCode());
                // Forward F9 events to the parent DockablePanel
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_F9) {
                    logger.info("F9 pressed in MapPanel, forwarding to DockablePanel listeners");
                    // Dispatch the event to this DockablePanel's key listeners
                    for (java.awt.event.KeyListener listener : getKeyListeners()) {
                        listener.keyPressed(e);
                    }
                } else {
                    logger.info("Non-F9 key in MapPanel: {}", java.awt.event.KeyEvent.getKeyText(e.getKeyCode()));
                }
            }

            @Override
            public void keyReleased(java.awt.event.KeyEvent e) {
                // Forward F9 events to the parent DockablePanel
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_F9) {
                    logger.info("F9 released in MapPanel, forwarding to DockablePanel listeners");
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