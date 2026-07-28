package com.kalix.ide.interaction;

import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.MapPanel;
import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.icons.MenuIcons;

import com.kalix.ide.editor.commands.NodeTemplateCatalog;

import javax.swing.JMenu;
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

        // Build the context menu
        JPopupMenu menu = new JPopupMenu();

        // Selection-based actions. Note these act on the *existing* selection, not on
        // whatever sits under the click — right-clicking deliberately does not alter
        // the selection, so the menu never silently retargets a multi-node selection.
        addSelectionActions(menu);

        // Show the menu if it has items
        if (menu.getComponentCount() > 0) {
            menu.show(mapPanel, clickPoint.x, clickPoint.y);
        }
    }

    /**
     * Add selection-related actions to the menu.
     */
    private void addSelectionActions(JPopupMenu menu) {
        boolean hasNodeSelection = model.getSelectedNodeCount() > 0;
        boolean hasSelection = hasNodeSelection || model.getSelectedLinkCount() > 0;
        boolean hasClipboard = clipboardManager != null && clipboardManager.hasClipboardContent();
        int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        // Cut
        JMenuItem cutItem = new JMenuItem("Cut");
        cutItem.setIcon(MenuIcons.cut());
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
        copyItem.setIcon(MenuIcons.copy());
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
        pasteItem.setIcon(MenuIcons.paste());
        pasteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcutMask));
        pasteItem.setEnabled(hasClipboard);
        pasteItem.addActionListener(e -> {
            if (clipboardManager != null && lastContextMenuLocation != null) {
                // Convert screen location to world coordinates
                double worldX = mapPanel.toWorldX(lastContextMenuLocation.x);
                double worldY = mapPanel.toWorldY(lastContextMenuLocation.y);
                clipboardManager.pasteAtMapLocation(worldX, worldY);
                mapPanel.repaint();
            }
        });
        menu.add(pasteItem);

        menu.addSeparator();

        // Create block, per context-menu-style §1 block ④. Verb-first per §2.2: the
        // children are the objects of the verb, not values to pick among, so this is a
        // menu item that happens to have a submenu — not a category title under §6.
        JMenu insertNodeTemplateMenu = new JMenu("Insert node");
        insertNodeTemplateMenu.setEnabled(textEditor != null);
        for (NodeTemplateCatalog.NodeTemplate template : NodeTemplateCatalog.templates()) {
            JMenuItem templateItem = new JMenuItem(template.label());
            templateItem.addActionListener(e -> {
                if (textEditor != null && lastContextMenuLocation != null) {
                    // The click sets where the node sits on the map; the selection sets
                    // where its section lands in the text. They are independent.
                    double worldX = mapPanel.toWorldX(lastContextMenuLocation.x);
                    double worldY = mapPanel.toWorldY(lastContextMenuLocation.y);
                    String newName = textEditor.insertNodeTemplate(template.id(), worldX, worldY,
                        model.getSelectedNodes(), model.getSingleSelectedLink());
                    if (newName != null) {
                        mapPanel.repaint();
                        // As if the user had clicked the new node: show its section in the
                        // editor and select it on the map. Selection is deferred one EDT
                        // cycle so the queued re-parse has registered the node first.
                        textEditor.scrollToNode(newName);
                        javax.swing.SwingUtilities.invokeLater(() -> {
                            model.selectNode(newName, false);
                            mapPanel.repaint();
                        });
                    }
                }
            });
            insertNodeTemplateMenu.add(templateItem);
        }
        menu.add(insertNodeTemplateMenu);

        menu.addSeparator();

        // Rename - only enabled when exactly one node is selected
        boolean singleNodeSelected = model.getSelectedNodeCount() == 1;
        String selectedNodeName = singleNodeSelected ?
            model.getSelectedNodes().iterator().next() : null;

        JMenuItem renameItem = new JMenuItem(singleNodeSelected ?
            "Rename \"" + selectedNodeName + "\"" : "Rename");
        renameItem.setEnabled(singleNodeSelected && textEditor != null);
        renameItem.addActionListener(e -> {
            if (textEditor != null && selectedNodeName != null) {
                textEditor.renameNode(selectedNodeName);
                mapPanel.repaint();
            }
        });
        menu.add(renameItem);

        // Delete (destructive — sits at the foot of the modify block)
        JMenuItem deleteItem = new JMenuItem("Delete");
        deleteItem.setIcon(MenuIcons.delete());
        deleteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));
        deleteItem.setEnabled(hasSelection);
        deleteItem.addActionListener(e -> {
            if (interactionManager != null) {
                interactionManager.deleteSelectedElements();
                mapPanel.repaint();
            }
        });
        menu.add(deleteItem);

        menu.addSeparator();

        // Copy location - copies map coordinates of right-click location to clipboard
        JMenuItem copyLocationItem = new JMenuItem("Copy location");
        copyLocationItem.addActionListener(e -> {
            if (lastContextMenuLocation != null) {
                double worldX = mapPanel.toWorldX(lastContextMenuLocation.x);
                double worldY = mapPanel.toWorldY(lastContextMenuLocation.y);
                // Locale.ROOT: the copied text is pasted into model files (dot decimals).
                String locationText = String.format(java.util.Locale.ROOT, "%.2f, %.2f", worldX, worldY);
                java.awt.datatransfer.StringSelection selection =
                    new java.awt.datatransfer.StringSelection(locationText);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
            }
        });
        menu.add(copyLocationItem);

        // Find - the map is the menu's own context, so no "on Map" needed (manifesto §2.3)
        JMenuItem findNodeItem = new JMenuItem("Find…");
        findNodeItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, shortcutMask));
        findNodeItem.setEnabled(mapSearchManager != null);
        findNodeItem.addActionListener(e -> {
            if (mapSearchManager != null) {
                mapSearchManager.showFindDialog();
            }
        });
        menu.add(findNodeItem);

        // Zoom to fit
        JMenuItem zoomToFitItem = new JMenuItem("Zoom to fit");
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
