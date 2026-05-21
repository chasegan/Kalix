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

        // The map fills this panel, so forward its hover events up so
        // docking-mode highlighting still works.
        forwardHoverEvents(mapPanel);

        // Register services with the docking context
        DockingContext context = DockingContext.getInstance();
        context.registerService("mapPanel", mapPanel);
        context.registerService(MapPanel.class, mapPanel);
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