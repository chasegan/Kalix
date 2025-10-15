package com.kalix.ide.builders;

import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.managers.KeyboardShortcutManager;
import com.kalix.ide.themes.NodeTheme;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import java.awt.event.ActionListener;

/**
 * Builder class for creating and configuring the application menu bar.
 * Handles all menu creation and organization logic.
 */
public class MenuBarBuilder {
    
    private final MenuBarCallbacks callbacks;
    private final EnhancedTextEditor textEditor;
    private final KeyboardShortcutManager shortcutManager;
    private JMenu recentFilesMenu;
    private JMenu fileMenu;
    
    /**
     * Interface defining all callback methods needed for menu and toolbar actions.
     */
    public interface MenuBarCallbacks {
        void newModel();
        void openModel();
        void saveModel();
        void saveAsModel();
        void exitApplication();
        void undoAction();
        void redoAction();
        void cutAction();
        void copyAction();
        void pasteAction();
        void toggleCommentAction();
        void normalizeLineEndings();
        void zoomIn();
        void zoomOut();
        void resetZoom();
        void zoomToFit();
        String switchTheme(String theme);
        void setNodeTheme(NodeTheme.Theme theme);
        void flowViz();
        void showAbout();
        void updateStatus(String message);
        
        // New toolbar-specific actions
        void runModelFromMemory();
        void searchModel();
        void showFindReplaceDialog();
        void getCliVersion();
        
        // Run Manager window
        void showRunManager();
        
        // Website launch
        void openWebsite();
        
        // Preferences dialog
        void showPreferences();

        // Linting toggle
        void toggleLinting();
        boolean isLintingEnabled();
        
        // Appearance menu
        void toggleGridlines(boolean showGridlines);
        boolean isGridlinesVisible();
        void toggleAutoReload(boolean enabled);
        boolean isAutoReloadEnabled();

        // System menu
        void openTerminalHere();

        // External editor
        void openExternalEditor();

        // File manager
        void openFileManager();
    }
    
    /**
     * Creates a new MenuBarBuilder instance.
     * 
     * @param callbacks The callback interface for menu actions
     * @param textEditor The text editor for editor-specific menu items
     */
    public MenuBarBuilder(MenuBarCallbacks callbacks, EnhancedTextEditor textEditor) {
        this.callbacks = callbacks;
        this.textEditor = textEditor;
        this.shortcutManager = KeyboardShortcutManager.getInstance();
    }
    
    /**
     * Builds and returns the complete menu bar.
     * 
     * @param currentTheme The current theme name for theme menu selection
     * @param currentNodeTheme The current node theme for node theme menu selection
     * @return The configured JMenuBar
     */
    public JMenuBar buildMenuBar(String currentTheme, NodeTheme.Theme currentNodeTheme) {
        JMenuBar menuBar = new JMenuBar();
        
        menuBar.add(createFileMenu());
        menuBar.add(createEditMenu());
        menuBar.add(createViewMenu(currentTheme, currentNodeTheme));
        menuBar.add(createRunMenu());
        menuBar.add(createToolsMenu());
        menuBar.add(createAIMenu());
        menuBar.add(createHelpMenu());
        
        return menuBar;
    }
    
    /**
     * Gets the recent files menu for external updates.
     * 
     * @return The recent files menu
     */
    public JMenu getRecentFilesMenu() {
        return recentFilesMenu;
    }
    
    /**
     * Creates the File menu.
     */
    private JMenu createFileMenu() {
        fileMenu = new JMenu("File");
        
        fileMenu.add(createMenuItem("New", e -> callbacks.newModel()));
        fileMenu.add(createMenuItem("Open", e -> callbacks.openModel()));
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem(shortcutManager.getMenuItemWithShortcut("Save", "S"), e -> callbacks.saveModel()));
        fileMenu.add(createMenuItem("Save As...", e -> callbacks.saveAsModel()));
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem("Preferences", e -> callbacks.showPreferences()));
        
        // Recent files will be added here by the proxy menu
        // Exit will be added at the very end by the proxy menu
        
        // Create a proxy menu that delegates to adding items directly to the File menu
        recentFilesMenu = new RecentFilesProxyMenu();
        
        // Initialize the menu with Exit at the bottom
        ((RecentFilesProxyMenu) recentFilesMenu).initializeMenu();
        
        return fileMenu;
    }
    
    /**
     * Creates the Edit menu.
     */
    private JMenu createEditMenu() {
        JMenu editMenu = new JMenu("Edit");
        
        editMenu.add(createMenuItem(shortcutManager.getMenuItemWithShortcut("Undo", "Z"), e -> callbacks.undoAction()));
        editMenu.add(createMenuItem(shortcutManager.getMenuItemWithShortcut("Redo", "Y"), e -> callbacks.redoAction()));
        editMenu.addSeparator();
        editMenu.add(createMenuItem("Cut", e -> callbacks.cutAction()));
        editMenu.add(createMenuItem("Copy", e -> callbacks.copyAction()));
        editMenu.add(createMenuItem("Paste", e -> callbacks.pasteAction()));
        editMenu.addSeparator();
        editMenu.add(createMenuItem(shortcutManager.getMenuItemWithShortcut("Toggle Comment", "/"), e -> callbacks.toggleCommentAction()));
        editMenu.add(createMenuItem("Normalize Line Endings", e -> callbacks.normalizeLineEndings()));
        editMenu.addSeparator();
        editMenu.add(createMenuItem(shortcutManager.getMenuItemWithShortcut("Find...", "F"), e -> callbacks.searchModel()));
        editMenu.add(createMenuItem(shortcutManager.getMenuItemWithShortcut("Find and Replace...", "H"), e -> callbacks.showFindReplaceDialog()));

        return editMenu;
    }
    
    
    /**
     * Creates the View menu.
     */
    private JMenu createViewMenu(String currentTheme, NodeTheme.Theme currentNodeTheme) {
        JMenu viewMenu = new JMenu("View");
        
        // Zoom controls
        viewMenu.add(createMenuItem("Zoom In", e -> callbacks.zoomIn()));
        viewMenu.add(createMenuItem("Zoom Out", e -> callbacks.zoomOut()));
        viewMenu.add(createMenuItem("Reset Zoom", e -> callbacks.resetZoom()));
        viewMenu.add(createMenuItem("Zoom to Fit", e -> callbacks.zoomToFit()));
        
        return viewMenu;
    }

    /**
     * Creates the Run menu.
     */
    private JMenu createRunMenu() {
        JMenu runMenu = new JMenu("Run");
        runMenu.add(createMenuItem("Run Model", e -> callbacks.runModelFromMemory()));
        runMenu.addSeparator();
        runMenu.add(createMenuItem("Run Manager", e -> callbacks.showRunManager()));
        return runMenu;
    }
    
    /**
     * Creates the Data Tools menu.
     */
    private JMenu createToolsMenu() {
        JMenu toolsMenu = new JMenu("Data Tools");
        toolsMenu.add(createMenuItem("FlowViz", e -> callbacks.flowViz()));
        return toolsMenu;
    }

    /**
     * Creates the AI menu.
     */
    private JMenu createAIMenu() {
        JMenu aiMenu = new JMenu("AI");

        // Launch terminal
        aiMenu.add(createMenuItem("Terminal", e -> callbacks.openTerminalHere()));
        aiMenu.add(createMenuItem("External Editor", e -> callbacks.openExternalEditor()));
        aiMenu.add(createMenuItem("File Manager", e -> callbacks.openFileManager()));
        aiMenu.addSeparator();
        aiMenu.add(createMenuItem("Init CLAUDE.md", e -> {}));
        aiMenu.add(createMenuItem("Init AGENTS.md", e -> {}));
        aiMenu.addSeparator();

        // Auto-reload toggle
        JCheckBoxMenuItem autoReloadItem = new JCheckBoxMenuItem("Auto-reload Clean Files", callbacks.isAutoReloadEnabled());
        autoReloadItem.addActionListener(e -> {
            boolean newState = autoReloadItem.isSelected();
            callbacks.toggleAutoReload(newState);
            callbacks.updateStatus(newState ? "Auto-reload enabled" : "Auto-reload disabled");
        });
        aiMenu.add(autoReloadItem);

        return aiMenu;
    }

    
    /**
     * Creates the Help menu.
     */
    private JMenu createHelpMenu() {
        JMenu helpMenu = new JMenu("Help");
        helpMenu.add(createMenuItem("About", e -> callbacks.showAbout()));
        return helpMenu;
    }
    
    /**
     * Helper method to create menu items with action listeners.
     * 
     * @param text The menu item text
     * @param listener The action listener
     * @return The configured JMenuItem
     */
    private JMenuItem createMenuItem(String text, ActionListener listener) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(listener);
        return item;
    }
    
    /**
     * A proxy menu that intercepts RecentFilesManager calls and adds items
     * directly to the File menu instead of a submenu.
     */
    private class RecentFilesProxyMenu extends JMenu {
        private JMenuItem exitMenuItem;
        private int recentFilesStartIndex = -1;
        
        public RecentFilesProxyMenu() {
            super("Recent");
            // Create the Exit menu item that will be managed by this proxy
            exitMenuItem = createMenuItem("Exit", e -> callbacks.exitApplication());
        }
        
        @Override
        public void removeAll() {
            // Remove recent file items and Exit, then re-add Exit at the end
            if (fileMenu != null) {
                // Remove Exit if it exists
                removeExitFromMenu();
                
                // Remove recent files if they were added
                if (recentFilesStartIndex != -1) {
                    int itemCount = fileMenu.getMenuComponentCount();
                    for (int i = itemCount - 1; i >= recentFilesStartIndex; i--) {
                        fileMenu.remove(i);
                    }
                    recentFilesStartIndex = -1;
                }
                
                // Always re-add Exit with separator after clearing
                addExitWithSeparator();
            }
        }
        
        @Override
        public JMenuItem add(JMenuItem item) {
            // Add recent files items before Exit
            if (fileMenu != null) {
                // Remove Exit temporarily if it exists
                removeExitFromMenu();
                
                // Mark the start of recent files section if this is the first item
                if (recentFilesStartIndex == -1) {
                    // Add separator before recent files
                    fileMenu.addSeparator();
                    recentFilesStartIndex = fileMenu.getMenuComponentCount();
                }
                
                // Add the recent file item
                JMenuItem addedItem = fileMenu.add(item);
                
                // Always add Exit at the end
                addExitToMenu();
                
                return addedItem;
            }
            return item;
        }
        
        @Override
        public void addSeparator() {
            // Add separator directly to the File menu as part of recent files section
            // Remove Exit temporarily, add separator, then re-add Exit with separator
            if (fileMenu != null) {
                removeExitFromMenu();
                fileMenu.addSeparator();
                addExitWithSeparator();
            }
        }
        
        private void removeExitFromMenu() {
            if (fileMenu == null) return;
            
            for (int i = fileMenu.getMenuComponentCount() - 1; i >= 0; i--) {
                if (fileMenu.getMenuComponent(i) instanceof JMenuItem) {
                    JMenuItem item = (JMenuItem) fileMenu.getMenuComponent(i);
                    if ("Exit".equals(item.getText())) {
                        fileMenu.remove(i);
                        break;
                    }
                }
            }
        }
        
        private void addExitToMenu() {
            if (fileMenu != null) {
                fileMenu.add(exitMenuItem);
            }
        }
        
        private void addExitWithSeparator() {
            if (fileMenu != null) {
                fileMenu.addSeparator();
                fileMenu.add(exitMenuItem);
            }
        }
        
        public void initializeMenu() {
            // Add Exit to the menu initially with separator
            addExitWithSeparator();
        }
    }
}