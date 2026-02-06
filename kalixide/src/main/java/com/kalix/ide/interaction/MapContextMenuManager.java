package com.kalix.ide.interaction;

import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelLink;
import com.kalix.ide.MapPanel;
import com.kalix.ide.editor.EnhancedTextEditor;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import java.awt.Point;
import java.awt.Toolkit;
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

    // Clipboard manager for cut/copy/paste operations
    private MapClipboardManager clipboardManager;

    // Text editor for rename operations
    private EnhancedTextEditor textEditor;

    // Search manager for find node dialog
    private MapSearchManager mapSearchManager;

    // Track where the context menu was invoked for potential future use (e.g., paste location)
    private Point lastContextMenuLocation;

    public MapContextMenuManager(MapPanel mapPanel, MapInteractionManager interactionManager,
                                  HydrologicalModel model) {
        this.mapPanel = mapPanel;
        this.interactionManager = interactionManager;
        this.model = model;
    }

    /**
     * Set the clipboard manager for cut/copy/paste operations.
     * @param clipboardManager The clipboard manager
     */
    public void setClipboardManager(MapClipboardManager clipboardManager) {
        this.clipboardManager = clipboardManager;
    }

    /**
     * Set the text editor for rename operations.
     * @param textEditor The text editor
     */
    public void setTextEditor(EnhancedTextEditor textEditor) {
        this.textEditor = textEditor;
    }

    /**
     * Set the search manager for the "Find Node" menu item.
     * @param mapSearchManager The map search manager
     */
    public void setMapSearchManager(MapSearchManager mapSearchManager) {
        this.mapSearchManager = mapSearchManager;
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
        boolean hasNodeSelection = model.getSelectedNodeCount() > 0;
        boolean hasSelection = hasNodeSelection || model.getSelectedLinkCount() > 0;
        boolean hasClipboard = clipboardManager != null && clipboardManager.hasClipboardContent();
        int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        // Cut
        JMenuItem cutItem = new JMenuItem("Cut");
        cutItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, shortcutMask));
        cutItem.setEnabled(hasNodeSelection && clipboardManager != null);
        cutItem.addActionListener(e -> {
            if (clipboardManager != null) {
                clipboardManager.cut();
                mapPanel.repaint();
            }
        });
        menu.add(cutItem);

        // Copy
        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcutMask));
        copyItem.setEnabled(hasNodeSelection && clipboardManager != null);
        copyItem.addActionListener(e -> {
            if (clipboardManager != null) {
                clipboardManager.copy();
            }
        });
        menu.add(copyItem);

        // Paste
        JMenuItem pasteItem = new JMenuItem("Paste");
        pasteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcutMask));
        pasteItem.setEnabled(hasClipboard);
        pasteItem.addActionListener(e -> {
            if (clipboardManager != null && lastContextMenuLocation != null) {
                // Convert screen location to world coordinates
                double worldX = (lastContextMenuLocation.x - mapPanel.getPanX()) / mapPanel.getZoomLevel();
                double worldY = (lastContextMenuLocation.y - mapPanel.getPanY()) / mapPanel.getZoomLevel();
                clipboardManager.pasteAtMapLocation(worldX, worldY);
                mapPanel.repaint();
            }
        });
        menu.add(pasteItem);

        menu.addSeparator();

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

        // Rename - only enabled when exactly one node is selected
        boolean singleNodeSelected = model.getSelectedNodeCount() == 1;
        String selectedNodeName = singleNodeSelected ?
            model.getSelectedNodes().iterator().next() : null;

        JMenuItem renameItem = new JMenuItem(singleNodeSelected ?
            "Rename " + selectedNodeName : "Rename");
        renameItem.setEnabled(singleNodeSelected && textEditor != null);
        renameItem.addActionListener(e -> {
            if (textEditor != null && selectedNodeName != null) {
                textEditor.renameNode(selectedNodeName);
                mapPanel.repaint();
            }
        });
        menu.add(renameItem);

        menu.addSeparator();

        // Find Node
        JMenuItem findNodeItem = new JMenuItem("Find on Map...");
        findNodeItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, shortcutMask));
        findNodeItem.setEnabled(mapSearchManager != null);
        findNodeItem.addActionListener(e -> {
            if (mapSearchManager != null) {
                mapSearchManager.showFindDialog();
            }
        });
        menu.add(findNodeItem);

        // Zoom to Fit
        JMenuItem zoomToFitItem = new JMenuItem("Zoom to Fit");
        zoomToFitItem.addActionListener(e -> mapPanel.zoomToFit());
        menu.add(zoomToFitItem);
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
