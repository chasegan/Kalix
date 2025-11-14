package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;

/**
 * A specialized JSplitPane created by the docking system.
 * This class automatically cleans itself up when it has fewer than 2 components,
 * promoting the remaining component to its parent container.
 *
 * This distinguishes docking-created split panes from user-created ones,
 * ensuring we only auto-remove containers we created.
 */
public class DockingSplitPane extends JSplitPane implements ComponentListener {

    private boolean isCleaningUp = false;

    public DockingSplitPane(int orientation) {
        super(orientation);
        setupCleanupLogic();
    }

    /**
     * Sets up the cleanup logic that monitors component changes.
     */
    private void setupCleanupLogic() {
        // Set default properties for docking split panes
        setDividerLocation(0.5);
        setResizeWeight(0.5);

        // Add component listener to detect when components are removed
        addComponentListener(this);
    }

    @Override
    public void setLeftComponent(Component comp) {
        super.setLeftComponent(comp);
        scheduleCleanupCheck();
    }

    @Override
    public void setRightComponent(Component comp) {
        super.setRightComponent(comp);
        scheduleCleanupCheck();
    }

    @Override
    public void setTopComponent(Component comp) {
        super.setTopComponent(comp);
        scheduleCleanupCheck();
    }

    @Override
    public void setBottomComponent(Component comp) {
        super.setBottomComponent(comp);
        scheduleCleanupCheck();
    }

    @Override
    public void remove(Component comp) {
        super.remove(comp);
        scheduleCleanupCheck();
    }

    @Override
    public void removeAll() {
        super.removeAll();
        scheduleCleanupCheck();
    }

    /**
     * Schedules a cleanup check to be performed on the next event dispatch cycle.
     * This avoids issues with cleanup happening during component manipulation.
     */
    public void scheduleCleanupCheck() {
        if (!isCleaningUp) {
            SwingUtilities.invokeLater(this::checkForCleanup);
        }
    }

    /**
     * Checks if this split pane should be cleaned up and performs the cleanup if needed.
     */
    private void checkForCleanup() {
        Component left = getLeftComponent();
        Component right = getRightComponent();

        if (isCleaningUp) {
            return; // Already cleaning up
        }

        // Count non-null components
        int componentCount = 0;
        Component remainingComponent = null;

        if (left != null) {
            componentCount++;
            remainingComponent = left;
        }
        if (right != null) {
            componentCount++;
            remainingComponent = right;
        }

        // If we have fewer than 2 components, clean up
        if (componentCount < 2) {
            performCleanup(remainingComponent);
        }
    }

    /**
     * Performs the actual cleanup by promoting the remaining component to the parent.
     */
    private void performCleanup(Component remainingComponent) {
        isCleaningUp = true;

        try {
            Container parent = getParent();
            if (parent == null) {
                return; // No parent to promote to
            }

            // Remove this split pane from its parent
            parent.remove(this);

            // Add the remaining component to the parent (if any)
            if (remainingComponent != null) {
                // Remove the component from this split pane first
                if (remainingComponent == getLeftComponent()) {
                    setLeftComponent(null);
                } else if (remainingComponent == getRightComponent()) {
                    setRightComponent(null);
                }

                // Add to parent
                if (parent instanceof DockingArea) {
                    // For DockingArea, add to center
                    parent.add(remainingComponent, BorderLayout.CENTER);
                } else {
                    // For other containers, just add
                    parent.add(remainingComponent);
                }
            }

            // Trigger parent layout update
            parent.revalidate();
            parent.repaint();

            // Check if parent also needs cleanup (cascade cleanup)
            if (parent instanceof DockingSplitPane) {
                ((DockingSplitPane) parent).scheduleCleanupCheck();
            } else if (parent instanceof TabbedDockingContainer) {
                ((TabbedDockingContainer) parent).checkForCleanup();
            }

        } finally {
            isCleaningUp = false;
        }
    }

    // ComponentListener implementation (for future use if needed)
    @Override
    public void componentResized(ComponentEvent e) {}

    @Override
    public void componentMoved(ComponentEvent e) {}

    @Override
    public void componentShown(ComponentEvent e) {}

    @Override
    public void componentHidden(ComponentEvent e) {}
}