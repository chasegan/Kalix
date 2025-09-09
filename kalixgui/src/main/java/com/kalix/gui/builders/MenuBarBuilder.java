package com.kalix.gui.builders;

import com.kalix.gui.constants.AppConstants;
import com.kalix.gui.editor.EnhancedTextEditor;

import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import java.awt.event.ActionListener;

/**
 * Builder class for creating and configuring the application menu bar.
 * Handles all menu creation and organization logic.
 */
public class MenuBarBuilder {
    
    private final MenuBarCallbacks callbacks;
    private final EnhancedTextEditor textEditor;
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
        void showFontDialog();
        void zoomIn();
        void zoomOut();
        void resetZoom();
        String switchTheme(String theme);
        void flowViz();
        void showAbout();
        void updateStatus(String message);
        
        // New toolbar-specific actions
        void runModelFromMemory();
        void searchModel();
        void getCliVersion();
        
        // Sessions window
        void showSessionsWindow();
        
        // Website launch
        void openWebsite();
        
        // Preferences dialog
        void showPreferences();
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
    }
    
    /**
     * Builds and returns the complete menu bar.
     * 
     * @param currentTheme The current theme name for theme menu selection
     * @return The configured JMenuBar
     */
    public JMenuBar buildMenuBar(String currentTheme) {
        JMenuBar menuBar = new JMenuBar();
        
        menuBar.add(createFileMenu());
        menuBar.add(createEditMenu());
        menuBar.add(createViewMenu(currentTheme));
        menuBar.add(createRunMenu());
        menuBar.add(createGraphMenu());
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
        fileMenu.add(createMenuItem("Save", e -> callbacks.saveModel()));
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
        
        editMenu.add(createMenuItem("Undo", e -> callbacks.undoAction()));
        editMenu.add(createMenuItem("Redo", e -> callbacks.redoAction()));
        editMenu.addSeparator();
        editMenu.add(createMenuItem("Cut", e -> callbacks.cutAction()));
        editMenu.add(createMenuItem("Copy", e -> callbacks.copyAction()));
        editMenu.add(createMenuItem("Paste", e -> callbacks.pasteAction()));
        
        return editMenu;
    }
    
    
    /**
     * Creates the View menu.
     */
    private JMenu createViewMenu(String currentTheme) {
        JMenu viewMenu = new JMenu("View");
        
        // Zoom controls
        viewMenu.add(createMenuItem("Zoom In", e -> callbacks.zoomIn()));
        viewMenu.add(createMenuItem("Zoom Out", e -> callbacks.zoomOut()));
        viewMenu.add(createMenuItem("Reset Zoom", e -> callbacks.resetZoom()));
        viewMenu.addSeparator();
        
        // Font settings
        viewMenu.add(createMenuItem("Font...", e -> callbacks.showFontDialog()));
        viewMenu.addSeparator();
        
        // Theme submenu
        JMenu themeMenu = createThemeMenu(currentTheme);
        viewMenu.add(themeMenu);
        
        return viewMenu;
    }
    
    /**
     * Creates the theme submenu.
     */
    private JMenu createThemeMenu(String currentTheme) {
        JMenu themeMenu = new JMenu("Theme");
        ButtonGroup themeGroup = new ButtonGroup();
        
        for (String theme : AppConstants.AVAILABLE_THEMES) {
            JRadioButtonMenuItem themeItem = new JRadioButtonMenuItem(theme, theme.equals(currentTheme));
            themeItem.addActionListener(e -> {
                String statusMessage = callbacks.switchTheme(theme);
                callbacks.updateStatus(statusMessage);
            });
            themeGroup.add(themeItem);
            themeMenu.add(themeItem);
        }
        
        return themeMenu;
    }
    
    /**
     * Creates the Run menu.
     */
    private JMenu createRunMenu() {
        JMenu runMenu = new JMenu("Run");
        runMenu.add(createMenuItem("Run Model", e -> callbacks.runModelFromMemory()));
        runMenu.addSeparator();
        runMenu.add(createMenuItem("Sessions Window", e -> callbacks.showSessionsWindow()));
        return runMenu;
    }
    
    /**
     * Creates the Graph menu.
     */
    private JMenu createGraphMenu() {
        JMenu graphMenu = new JMenu("Graph");
        graphMenu.add(createMenuItem("FlowViz", e -> callbacks.flowViz()));
        return graphMenu;
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
            // Add separator to the File menu (but before Exit)
            if (fileMenu != null) {
                removeExitFromMenu();
                fileMenu.addSeparator();
                addExitToMenu();
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
                fileMenu.addSeparator();
                fileMenu.add(exitMenuItem);
            }
        }
        
        public void initializeMenu() {
            // Add Exit to the menu initially
            addExitToMenu();
        }
    }
}