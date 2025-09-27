package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * A specialized window for housing detached dockable panels.
 * 
 * This window provides a minimal frame around detached panels, allowing them
 * to function independently while maintaining their docking capabilities.
 * The window automatically sizes itself to fit the contained panel and
 * handles cleanup when closed.
 */
public class DockingWindow extends JFrame {
    
    private DockablePanel containedPanel;
    private DockZone dockZone;
    
    /**
     * Creates a new DockingWindow containing the specified panel.
     * @param panel The dockable panel to contain
     */
    public DockingWindow(DockablePanel panel) {
        this.containedPanel = panel;
        
        initializeWindow();
        setupPanel();
        setupDockZone();
    }
    
    /**
     * Initializes the window properties and behavior.
     */
    private void initializeWindow() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setTitle("Docked Panel"); // Default title, can be customized
        
        // Set up window listener for cleanup
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleWindowClosing();
            }
        });
        
        // Make window always on top initially (user can change this)
        setAlwaysOnTop(false);
        
        // Set minimum size to prevent window from becoming too small
        setMinimumSize(new Dimension(200, 150));
    }
    
    /**
     * Sets up the contained panel within the window.
     */
    private void setupPanel() {
        if (containedPanel != null) {
            // Add panel to content pane
            getContentPane().setLayout(new BorderLayout());
            getContentPane().add(containedPanel, BorderLayout.CENTER);
            
            // Size window to fit panel's preferred size
            Dimension panelSize = containedPanel.getPreferredSize();
            if (panelSize.width > 0 && panelSize.height > 0) {
                setSize(panelSize);
            } else {
                // Fallback size if panel doesn't have preferred size
                setSize(400, 300);
            }
            
            // Re-enable docking mode for the panel since it's in a new window
            containedPanel.setDockingMode(false); // Start disabled in new window
        }
    }
    
    /**
     * Sets up a dock zone for this window so other panels can be docked here.
     */
    private void setupDockZone() {
        dockZone = new BasicDockZone(getContentPane()) {
            @Override
            public boolean acceptPanel(DockablePanel panel, Point dropPoint) {
                // For this implementation, we'll create a tabbed pane if multiple panels are dropped
                return handleMultiplePanels(panel, dropPoint);
            }
        };
        
        // Register this window as a drop zone
        DockingManager.getInstance().registerDropZone(dockZone);
    }
    
    /**
     * Handles adding multiple panels to this window by creating a tabbed pane.
     * @param newPanel The new panel being added
     * @param dropPoint The drop position
     * @return true if the panel was successfully added
     */
    private boolean handleMultiplePanels(DockablePanel newPanel, Point dropPoint) {
        Container contentPane = getContentPane();
        
        if (contentPane.getComponentCount() == 1 && 
            !(contentPane.getComponent(0) instanceof JTabbedPane)) {
            
            // Convert to tabbed pane layout
            Component existingComponent = contentPane.getComponent(0);
            contentPane.remove(existingComponent);
            
            JTabbedPane tabbedPane = new JTabbedPane();
            tabbedPane.addTab("Panel 1", existingComponent);
            tabbedPane.addTab("Panel 2", newPanel);
            
            contentPane.add(tabbedPane, BorderLayout.CENTER);
            
        } else if (contentPane.getComponent(0) instanceof JTabbedPane) {
            // Add to existing tabbed pane
            JTabbedPane tabbedPane = (JTabbedPane) contentPane.getComponent(0);
            tabbedPane.addTab("Panel " + (tabbedPane.getTabCount() + 1), newPanel);
        } else {
            // Single panel case
            return dockZone.acceptPanel(newPanel, dropPoint);
        }
        
        // Disable docking mode for the newly added panel
        newPanel.setDockingMode(false);
        
        revalidate();
        repaint();
        
        return true;
    }
    
    /**
     * Handles the window closing event.
     */
    private void handleWindowClosing() {
        // Ask user for confirmation if there are unsaved changes
        // For now, just close the window
        
        // Unregister the dock zone
        if (dockZone != null) {
            DockingManager.getInstance().unregisterDropZone(dockZone);
        }
        
        // Dispose of the window
        dispose();
    }
    
    /**
     * Sets the title of the window.
     * @param title The new window title
     */
    public void setWindowTitle(String title) {
        setTitle(title);
    }
    
    /**
     * Gets the main panel contained in this window.
     * @return The contained dockable panel
     */
    public DockablePanel getContainedPanel() {
        return containedPanel;
    }
    
    /**
     * Checks if this window contains the specified panel.
     * @param panel The panel to check for
     * @return true if the panel is contained in this window
     */
    public boolean containsPanel(DockablePanel panel) {
        return findPanelInContainer(getContentPane(), panel);
    }
    
    /**
     * Recursively searches for a panel within a container.
     * @param container The container to search
     * @param panel The panel to find
     * @return true if the panel is found
     */
    private boolean findPanelInContainer(Container container, DockablePanel panel) {
        for (Component component : container.getComponents()) {
            if (component == panel) {
                return true;
            }
            if (component instanceof Container) {
                if (findPanelInContainer((Container) component, panel)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Brings this window to the front and requests focus.
     */
    public void bringToFront() {
        toFront();
        requestFocus();
        if (containedPanel != null) {
            containedPanel.requestFocusInWindow();
        }
    }
}