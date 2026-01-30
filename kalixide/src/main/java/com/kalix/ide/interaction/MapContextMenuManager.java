package com.kalix.ide.interaction;

import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelLink;
import com.kalix.ide.MapPanel;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

/**
 * Manages the right-click context menu for the map panel.
 * Builds context-aware menus based on selection state and click location.
 */
public class MapContextMenuManager {

    private final MapPanel mapPanel;
    private final MapInteractionManager interactionManager;
    private final HydrologicalModel model;

    // Track where the context menu was invoked for potential future use (e.g., paste location)
    private Point lastContextMenuLocation;

    public MapContextMenuManager(MapPanel mapPanel, MapInteractionManager interactionManager,
                                  HydrologicalModel model) {
        this.mapPanel = mapPanel;
        this.interactionManager = interactionManager;
        this.model = model;
    }

    /**
     * Show the context menu at the specified location.
     * @param clickPoint The point where the right-click occurred (in screen coordinates)
     * @param e The mouse event that triggered the menu
     */
    public void showContextMenu(Point clickPoint, MouseEvent e) {
        if (model == null) {
            return;
        }

        // Store location for potential use by menu actions (e.g., paste at location)
        lastContextMenuLocation = clickPoint;

        // Determine what's at the click point
        String nodeAtPoint = mapPanel.getNodeAtPoint(clickPoint);
        ModelLink linkAtPoint = mapPanel.getLinkAtPoint(clickPoint);

        // Build the context menu
        JPopupMenu menu = new JPopupMenu();

        // Selection-based actions
        addSelectionActions(menu, nodeAtPoint, linkAtPoint);

        // Show the menu if it has items
        if (menu.getComponentCount() > 0) {
            menu.show(mapPanel, clickPoint.x, clickPoint.y);
        }
    }

    /**
     * Add selection-related actions to the menu.
     */
    private void addSelectionActions(JPopupMenu menu, String nodeAtPoint, ModelLink linkAtPoint) {
        boolean hasSelection = model.getSelectedNodeCount() > 0 || model.getSelectedLinkCount() > 0;

        // Delete Selection
        JMenuItem deleteItem = new JMenuItem("Delete Selection");
        deleteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));
        deleteItem.setEnabled(hasSelection);
        deleteItem.addActionListener(e -> {
            if (interactionManager != null) {
                interactionManager.deleteSelectedElements();
                mapPanel.repaint();
            }
        });
        menu.add(deleteItem);
    }

    /**
     * Get the location where the last context menu was shown.
     * Useful for actions like "Paste here" or "Create node here".
     * @return The last context menu location in screen coordinates
     */
    public Point getLastContextMenuLocation() {
        return lastContextMenuLocation;
    }
}
