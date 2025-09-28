package com.kalix.ide.docking;

import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
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
    private final MouseStateTracker mouseTracker;
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

        // Update drop zone for hybrid docking
        if (currentDropTarget != null) {
            currentDropTarget.updateDropZone(screenLocation);
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
                // Drop into existing docking area with hybrid logic
                dropIntoAreaHybrid(currentDragPanel, dropTarget);
            } else {
                // Create new floating window
                createFloatingWindow(currentDragPanel, screenLocation);
            }

            // Cancel docking mode on the panel after successful operation
            if (currentDragPanel != null) {
                currentDragPanel.cancelDockingMode();
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
     * Drops the panel into the specified docking area using hybrid logic.
     * Supports both simple drops (empty areas) and zone-based drops (occupied areas).
     */
    private void dropIntoAreaHybrid(DockablePanel panel, DockingArea area) {
        // Get the current parent before moving the panel
        Container oldParent = panel.getParent();

        if (area.isEmpty()) {
            // Simple drop into empty area
            area.addDockablePanel(panel);
        } else {
            // Hybrid drop into occupied area based on current drop zone
            DropZoneDetector.DropZone dropZone = area.getCurrentDropZone();
            handleZoneDrop(panel, area, dropZone);
        }

        // Check if old parent was a DockingWindow that should auto-close
        checkForAutoClose(oldParent);
    }

    /**
     * Handles dropping a panel into a specific zone of an occupied docking area.
     */
    private void handleZoneDrop(DockablePanel newPanel, DockingArea area, DropZoneDetector.DropZone zone) {
        // Get the existing content from the area (could be panel, tabbed container, or split pane)
        Component existingContent = getMainContent(area);
        if (existingContent == null) {
            // Fallback to simple drop if no existing content found
            area.addDockablePanel(newPanel);
            return;
        }

        switch (zone) {
            case CENTER:
                // Handle center drop based on existing content type
                handleCenterDrop(area, existingContent, newPanel);
                break;
            case TOP:
                createSplitPaneWithExistingContent(area, newPanel, existingContent, JSplitPane.VERTICAL_SPLIT);
                break;
            case BOTTOM:
                createSplitPaneWithExistingContent(area, existingContent, newPanel, JSplitPane.VERTICAL_SPLIT);
                break;
            case LEFT:
                createSplitPaneWithExistingContent(area, newPanel, existingContent, JSplitPane.HORIZONTAL_SPLIT);
                break;
            case RIGHT:
                createSplitPaneWithExistingContent(area, existingContent, newPanel, JSplitPane.HORIZONTAL_SPLIT);
                break;
            default:
                // Fallback to simple drop
                area.addDockablePanel(newPanel);
                break;
        }
    }

    /**
     * Handles center drops based on the type of existing content.
     */
    private void handleCenterDrop(DockingArea area, Component existingContent, DockablePanel newPanel) {
        if (existingContent instanceof DockablePanel) {
            // Simple case: create tabbed container with two panels
            createTabbedContainer(area, (DockablePanel) existingContent, newPanel);
        } else if (existingContent instanceof TabbedDockingContainer tabbed) {
            // Add to existing tabbed container
            tabbed.addDockablePanel(newPanel);
        } else {
            // For split panes or other complex content, wrap in tabbed container
            wrapInTabbedContainer(area, existingContent, newPanel);
        }
    }

    /**
     * Wraps existing complex content and new panel in a tabbed container.
     */
    private void wrapInTabbedContainer(DockingArea area, Component existingContent, DockablePanel newPanel) {
        // Remove existing content
        area.remove(existingContent);

        // Create tabbed container
        TabbedDockingContainer tabbedContainer = new TabbedDockingContainer();

        // Add existing content as a wrapper panel
        DockablePanel wrapperPanel = new DockablePanel(new BorderLayout());
        wrapperPanel.add(existingContent, BorderLayout.CENTER);
        tabbedContainer.addDockablePanel(wrapperPanel);

        // Add new panel
        tabbedContainer.addDockablePanel(newPanel);

        // Add tabbed container to area
        area.add(tabbedContainer, BorderLayout.CENTER);
        area.revalidate();
        area.repaint();
    }

    /**
     * Gets the main content component from the docking area (not placeholders).
     */
    private Component getMainContent(DockingArea area) {
        for (Component comp : area.getComponents()) {
            // Skip placeholder components
            if (comp instanceof PlaceholderComponent) {
                continue;
            }
            // Return the first non-placeholder component
            return comp;
        }
        return null;
    }


    /**
     * Creates a tabbed container with the existing and new panels.
     */
    private void createTabbedContainer(DockingArea area, DockablePanel existingPanel, DockablePanel newPanel) {
        // Remove existing panel from area
        area.remove(existingPanel);

        // Create tabbed container with both panels
        TabbedDockingContainer tabbedContainer = TabbedDockingContainer.createWithTwoPanels(existingPanel, newPanel);

        // Add tabbed container to area
        area.add(tabbedContainer, BorderLayout.CENTER);
        area.revalidate();
        area.repaint();
    }

    /**
     * Creates a split pane with existing content and a new panel.
     * For vertical splits: firstComponent goes on top, secondComponent on bottom
     * For horizontal splits: firstComponent goes on left, secondComponent on right
     */
    private void createSplitPaneWithExistingContent(DockingArea area, Component firstComponent, Component secondComponent,
                                                   int orientation) {
        // Remove existing content from area
        area.removeAll();

        // Create docking split pane (auto-cleanup enabled)
        DockingSplitPane splitPane = new DockingSplitPane(orientation);

        // Set components based on orientation
        if (orientation == JSplitPane.VERTICAL_SPLIT) {
            splitPane.setTopComponent(firstComponent);
            splitPane.setBottomComponent(secondComponent);
        } else { // HORIZONTAL_SPLIT
            splitPane.setLeftComponent(firstComponent);
            splitPane.setRightComponent(secondComponent);
        }

        // Add split pane to area
        area.add(splitPane, BorderLayout.CENTER);
        area.revalidate();
        area.repaint();
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
     * Checks if the given container is a DockingWindow that should auto-close,
     * or if any intermediate containers (tabs/splits) need cleanup.
     * This is called after a panel is moved to prevent freezing during auto-close.
     */
    private void checkForAutoClose(Container oldParent) {
        // Use SwingUtilities.invokeLater to avoid issues with event handling
        SwingUtilities.invokeLater(() -> {
            // Walk up the container hierarchy and check each level for cleanup needs
            Container parent = oldParent;
            while (parent != null) {
                // Check for tab container cleanup
                if (parent instanceof TabbedDockingContainer tabContainer) {
                    tabContainer.checkForCleanup();
                }
                // Check for split pane cleanup
                else if (parent instanceof DockingSplitPane splitPane) {
                    splitPane.scheduleCleanupCheck();
                }
                // Check for window auto-close
                else if (parent instanceof DockingWindow window) {
                    if (window.isAutoClose() && window.isEmpty()) {
                        window.closeWindow();
                        unregisterDockingWindow(window);
                    }
                    break; // Stop at window level
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