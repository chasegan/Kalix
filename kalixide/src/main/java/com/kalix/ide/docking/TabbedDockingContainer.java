package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A container that holds multiple DockablePanel instances in a tabbed interface.
 * Used when panels are dropped in the center zone of an occupied docking area.
 */
public class TabbedDockingContainer extends JPanel {

    private JTabbedPane tabbedPane;
    private List<DockablePanel> dockablePanels = new ArrayList<>();

    public TabbedDockingContainer() {
        initializeContainer();
    }

    public TabbedDockingContainer(DockablePanel initialPanel) {
        initializeContainer();
        addDockablePanel(initialPanel);
    }

    /**
     * Initializes the tabbed container.
     */
    private void initializeContainer() {
        setLayout(new BorderLayout());

        // Create a custom JTabbedPane that syncs with our panel list
        tabbedPane = new JTabbedPane() {
            @Override
            public void remove(Component component) {
                super.remove(component);
                syncPanelList();
            }

            @Override
            public void remove(int index) {
                super.remove(index);
                syncPanelList();
            }

            @Override
            public void removeAll() {
                super.removeAll();
                syncPanelList();
            }
        };

        tabbedPane.setTabPlacement(JTabbedPane.TOP);

        add(tabbedPane, BorderLayout.CENTER);
    }

    /**
     * Synchronizes the dockablePanels list with the actual tabbed pane contents.
     */
    private void syncPanelList() {
        // Clear and rebuild the panel list based on actual tab contents
        dockablePanels.clear();
        for (int i = 0; i < tabbedPane.getComponentCount(); i++) {
            Component comp = tabbedPane.getComponentAt(i);
            if (comp instanceof DockablePanel) {
                dockablePanels.add((DockablePanel) comp);
            }
        }

        scheduleCleanupCheck();
    }

    /**
     * Adds a dockable panel as a new tab.
     */
    public void addDockablePanel(DockablePanel panel) {
        if (panel == null) {
            return;
        }

        dockablePanels.add(panel);

        // Generate a simple tab title
        String tabTitle = "Panel " + (dockablePanels.size());

        // Try to find a better title from the panel's content
        String betterTitle = extractTitleFromPanel(panel);
        if (betterTitle != null && !betterTitle.trim().isEmpty()) {
            tabTitle = betterTitle;
        }

        tabbedPane.addTab(tabTitle, panel);

        // Select the newly added tab
        tabbedPane.setSelectedIndex(tabbedPane.getTabCount() - 1);

        // Ensure the panel can receive focus for F9 key events
        panel.requestDockingFocus();
    }

    /**
     * Removes a dockable panel from the tabs.
     */
    public void removeDockablePanel(DockablePanel panel) {
        int index = dockablePanels.indexOf(panel);
        if (index >= 0) {
            dockablePanels.remove(index);
            tabbedPane.removeTabAt(index);

            // Check if cleanup is needed after removal
            scheduleCleanupCheck();
        }
    }

    @Override
    public void remove(Component comp) {
        // If it's a dockable panel being removed, sync our list
        if (comp instanceof DockablePanel) {
            DockablePanel panel = (DockablePanel) comp;
            int index = dockablePanels.indexOf(panel);
            if (index >= 0) {
                dockablePanels.remove(index);
            }
        }

        super.remove(comp);

        // Check if cleanup is needed after removal
        scheduleCleanupCheck();
    }

    /**
     * Returns the number of panels in this container.
     */
    public int getPanelCount() {
        return dockablePanels.size();
    }

    /**
     * Returns true if this container has no panels.
     */
    public boolean isEmpty() {
        return dockablePanels.isEmpty();
    }

    /**
     * Returns the currently selected panel, or null if none selected.
     */
    public DockablePanel getSelectedPanel() {
        int selectedIndex = tabbedPane.getSelectedIndex();
        if (selectedIndex >= 0 && selectedIndex < dockablePanels.size()) {
            return dockablePanels.get(selectedIndex);
        }
        return null;
    }

    /**
     * Returns all panels in this container.
     */
    public List<DockablePanel> getAllPanels() {
        return new ArrayList<>(dockablePanels);
    }

    /**
     * Attempts to extract a meaningful title from the panel's content.
     */
    private String extractTitleFromPanel(DockablePanel panel) {
        // Look for common components that might have useful titles
        Component[] components = panel.getComponents();
        for (Component comp : components) {
            if (comp instanceof JLabel) {
                JLabel label = (JLabel) comp;
                String text = label.getText();
                if (text != null && !text.trim().isEmpty()) {
                    // Clean up HTML tags if present
                    text = text.replaceAll("<[^>]+>", "").trim();
                    if (!text.isEmpty() && text.length() < 50) {
                        return text;
                    }
                }
            }
        }

        // If panel has a border with title
        if (panel.getBorder() instanceof javax.swing.border.TitledBorder) {
            javax.swing.border.TitledBorder titledBorder = (javax.swing.border.TitledBorder) panel.getBorder();
            String title = titledBorder.getTitle();
            if (title != null && !title.trim().isEmpty()) {
                return title;
            }
        }

        return null;
    }

    /**
     * Creates a tabbed container from an existing single panel.
     * Useful when converting a regular docking area to tabbed.
     */
    public static TabbedDockingContainer convertFromSinglePanel(DockablePanel existingPanel) {
        TabbedDockingContainer container = new TabbedDockingContainer();
        container.addDockablePanel(existingPanel);
        return container;
    }

    /**
     * Creates a tabbed container with two panels.
     * Useful when dropping a new panel into an occupied area.
     */
    public static TabbedDockingContainer createWithTwoPanels(DockablePanel existingPanel, DockablePanel newPanel) {
        TabbedDockingContainer container = new TabbedDockingContainer();
        container.addDockablePanel(existingPanel);
        container.addDockablePanel(newPanel);
        return container;
    }

    // Cleanup logic
    private boolean isCleaningUp = false;

    /**
     * Schedules a cleanup check to be performed on the next event dispatch cycle.
     */
    private void scheduleCleanupCheck() {
        if (!isCleaningUp) {
            SwingUtilities.invokeLater(this::checkForCleanup);
        }
    }

    /**
     * Checks if this tabbed container should be cleaned up and performs the cleanup if needed.
     * Called by DockingSplitPane when it's checking for cascade cleanup.
     */
    public void checkForCleanup() {
        if (isCleaningUp) {
            return; // Already cleaning up
        }

        // If we have 1 or fewer panels, clean up
        // 0 panels: remove empty container
        // 1 panel: remove container and promote the single panel
        if (dockablePanels.size() <= 1) {
            performCleanup();
        }
    }

    /**
     * Performs the actual cleanup by promoting the remaining panel to the parent.
     */
    private void performCleanup() {
        isCleaningUp = true;

        try {
            Container parent = getParent();
            if (parent == null) {
                return; // No parent to promote to
            }

            // Get the remaining panel (if any)
            DockablePanel remainingPanel = dockablePanels.isEmpty() ? null : dockablePanels.get(0);

            // Remove this tabbed container from its parent
            parent.remove(this);

            // Add the remaining panel to the parent (if any)
            if (remainingPanel != null) {
                // Remove the panel from this container first
                tabbedPane.removeAll();
                dockablePanels.clear();

                // Add to parent
                if (parent instanceof DockingArea) {
                    // For DockingArea, add to center
                    parent.add(remainingPanel, BorderLayout.CENTER);
                } else {
                    // For other containers, just add
                    parent.add(remainingPanel);
                }

                // Ensure the panel can receive focus for F9 key events
                remainingPanel.requestDockingFocus();
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

    /**
     * Returns whether this tabbed container is currently performing cleanup.
     */
    public boolean isCleaningUp() {
        return isCleaningUp;
    }
}