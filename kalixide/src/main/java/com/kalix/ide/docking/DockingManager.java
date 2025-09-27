package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.kalix.ide.docking.DockingConstants.*;

/**
 * Singleton manager that coordinates docking operations across the application.
 * Handles drag and drop operations, window management, and drop zone detection.
 */
public class DockingManager {
    private static DockingManager instance;

    // Current drag operation state
    private DockablePanel currentDragPanel;
    private DragPreview dragPreview;
    private Point dragStartLocation;
    private boolean isDragging = false;

    // Mouse state tracking
    private MouseStateTracker mouseTracker;
    private Point lastDragPosition;

    // Registered docking windows and areas
    private final List<DockingWindow> dockingWindows = new CopyOnWriteArrayList<>();
    private final List<DockingArea> dockingAreas = new CopyOnWriteArrayList<>();

    // Drop zone highlighting
    private DockingArea currentDropTarget;

    private DockingManager() {
        // Private constructor for singleton
        mouseTracker = new MouseStateTracker();
    }

    public static DockingManager getInstance() {
        if (instance == null) {
            instance = new DockingManager();
        }
        return instance;
    }

    /**
     * Registers a docking window with the manager.
     */
    public void registerDockingWindow(DockingWindow window) {
        dockingWindows.add(window);
    }

    /**
     * Unregisters a docking window from the manager.
     */
    public void unregisterDockingWindow(DockingWindow window) {
        dockingWindows.remove(window);
    }

    /**
     * Registers a docking area with the manager.
     */
    public void registerDockingArea(DockingArea area) {
        dockingAreas.add(area);
    }

    /**
     * Unregisters a docking area from the manager.
     */
    public void unregisterDockingArea(DockingArea area) {
        dockingAreas.remove(area);
    }

    /**
     * Starts a drag operation for the specified panel.
     */
    public synchronized void startDragOperation(DockablePanel panel, Point screenLocation) {
        if (isDragging) {
            return; // Already dragging
        }

        currentDragPanel = panel;
        dragStartLocation = new Point(screenLocation);
        lastDragPosition = new Point(screenLocation);
        isDragging = true;

        // Create drag preview
        createDragPreview(panel, screenLocation);

        // Start monitoring for mouse button release
        mouseTracker.startMonitoring(screenLocation, () -> endDragOperation(lastDragPosition));

        // Don't remove panel from parent during drag - keep it in place
        // The drag preview will show what's being dragged
    }

    /**
     * Updates the drag operation with a new screen location.
     */
    public void updateDragOperation(Point screenLocation) {
        if (!isDragging || dragPreview == null) {
            return;
        }

        // Update last drag position
        lastDragPosition = new Point(screenLocation);
        mouseTracker.updateDragPosition(screenLocation);

        // Update drag preview position
        dragPreview.updatePosition(screenLocation);

        // Find potential drop target
        DockingArea newDropTarget = findDropTargetAt(screenLocation);

        // Update drop target highlighting
        if (newDropTarget != currentDropTarget) {
            if (currentDropTarget != null) {
                currentDropTarget.setHighlighted(false);
            }
            currentDropTarget = newDropTarget;
            if (currentDropTarget != null) {
                currentDropTarget.setHighlighted(true);
            }
        }
    }

    /**
     * Ends the drag operation and handles the drop.
     */
    public synchronized void endDragOperation(Point screenLocation) {
        if (!isDragging) {
            return;
        }

        try {
            // Find drop target
            DockingArea dropTarget = findDropTargetAt(screenLocation);

            if (dropTarget != null) {
                // Drop into existing docking area
                dropIntoArea(currentDragPanel, dropTarget);
            } else {
                // Create new floating window
                createFloatingWindow(currentDragPanel, screenLocation);
            }

            // Clear drop target highlighting
            if (currentDropTarget != null) {
                currentDropTarget.setHighlighted(false);
                currentDropTarget = null;
            }

        } catch (Exception e) {
            // Log error but continue cleanup
            e.printStackTrace();
        } finally {
            // Clean up drag state
            if (dragPreview != null) {
                dragPreview.dispose();
                dragPreview = null;
            }

            // Stop mouse state tracking
            mouseTracker.stopMonitoring();

            isDragging = false;
            currentDragPanel = null;
            dragStartLocation = null;
            lastDragPosition = null;
        }
    }

    /**
     * Creates a visual drag preview for the dragged panel.
     */
    private void createDragPreview(DockablePanel panel, Point screenLocation) {
        dragPreview = new DragPreview(panel);
        dragPreview.show(screenLocation);
    }


    /**
     * Finds the docking area at the specified screen location.
     */
    private DockingArea findDropTargetAt(Point screenLocation) {
        for (DockingArea area : dockingAreas) {
            if (area.isValidDropTarget() && area.containsScreenPoint(screenLocation)) {
                return area;
            }
        }
        return null;
    }

    /**
     * Drops the panel into the specified docking area.
     */
    private void dropIntoArea(DockablePanel panel, DockingArea area) {
        // Get the current parent before moving the panel
        Container oldParent = panel.getParent();

        // Add to new area (this will automatically remove from old parent)
        area.addDockablePanel(panel);

        // Check if old parent was a DockingWindow that should auto-close
        checkForAutoClose(oldParent);
    }

    /**
     * Creates a new floating window for the panel.
     */
    private void createFloatingWindow(DockablePanel panel, Point screenLocation) {
        // Get the current parent before moving the panel
        Container oldParent = panel.getParent();

        // Get the current size of the panel to size the new window appropriately
        Dimension panelSize = panel.getSize();

        // Create auto-closing window for drag-created floating windows
        DockingWindow window = DockingWindow.createAutoClosingWithPanel(panel);

        // Size the window based on the panel size, adding some padding for window decorations
        if (panelSize.width > 0 && panelSize.height > 0) {
            // Add padding for window borders and title bar
            window.setSize(panelSize.width + Dimensions.WINDOW_PADDING_WIDTH,
                          panelSize.height + Dimensions.WINDOW_PADDING_HEIGHT);
        } else {
            // Fallback to pack if panel size is not available
            window.pack();
        }

        // Position the window near the drop location
        window.setLocation(screenLocation.x - 50, screenLocation.y - 30);
        window.setVisible(true);

        registerDockingWindow(window);

        // Check if old parent was a DockingWindow that should auto-close
        checkForAutoClose(oldParent);
    }

    /**
     * Checks if the given container is a DockingWindow that should auto-close.
     * This is called after a panel is moved to prevent freezing during auto-close.
     */
    private void checkForAutoClose(Container oldParent) {
        // Use SwingUtilities.invokeLater to avoid issues with event handling
        SwingUtilities.invokeLater(() -> {
            // Walk up the container hierarchy to find a DockingWindow
            Container parent = oldParent;
            while (parent != null) {
                if (parent instanceof DockingWindow) {
                    DockingWindow window = (DockingWindow) parent;
                    if (window.isAutoClose() && window.isEmpty()) {
                        window.closeWindow();
                        unregisterDockingWindow(window);
                    }
                    break;
                }
                parent = parent.getParent();
            }
        });
    }

    /**
     * Returns whether a drag operation is currently in progress.
     */
    public boolean isDragging() {
        return isDragging;
    }

}