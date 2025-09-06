package com.kalix.gui.builders;

import com.kalix.gui.constants.AppConstants;
import com.kalix.gui.editor.EnhancedTextEditor;

import javax.swing.*;
import java.awt.event.ActionListener;

/**
 * Builder class for creating and configuring the application menu bar.
 * Handles all menu creation and organization logic.
 */
public class MenuBarBuilder {
    
    private final MenuBarCallbacks callbacks;
    private final EnhancedTextEditor textEditor;
    private JMenu recentFilesMenu;
    
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
        void showSplashScreen();
        String switchTheme(String theme);
        void flowViz();
        void showAbout();
        void updateStatus(String message);
        
        // New toolbar-specific actions
        void runModel();
        void searchModel();
        void getCliVersion();
        
        // Settings dialog
        void showSettings();
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
        menuBar.add(createEditorMenu());
        menuBar.add(createViewMenu(currentTheme));
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
        JMenu fileMenu = new JMenu("File");
        
        fileMenu.add(createMenuItem("New", e -> callbacks.newModel()));
        fileMenu.add(createMenuItem("Open", e -> callbacks.openModel()));
        fileMenu.addSeparator();
        
        // Recent files submenu (initialized but will be populated externally)
        recentFilesMenu = new JMenu("Recent Files");
        fileMenu.add(recentFilesMenu);
        
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem("Save", e -> callbacks.saveModel()));
        fileMenu.add(createMenuItem("Save As...", e -> callbacks.saveAsModel()));
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem("Exit", e -> callbacks.exitApplication()));
        
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
     * Creates the Editor menu.
     */
    private JMenu createEditorMenu() {
        JMenu editorMenu = new JMenu("Editor");
        
        editorMenu.add(createMenuItem("Settings...", e -> callbacks.showSettings()));
        editorMenu.addSeparator();
        editorMenu.add(createMenuItem("Font...", e -> callbacks.showFontDialog()));
        editorMenu.addSeparator();
        
        // Line wrap checkbox
        JCheckBoxMenuItem lineWrapItem = new JCheckBoxMenuItem("Line Wrap");
        lineWrapItem.setSelected(textEditor.isLineWrap());
        lineWrapItem.addActionListener(e -> textEditor.setLineWrap(lineWrapItem.isSelected()));
        editorMenu.add(lineWrapItem);
        
        return editorMenu;
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
        
        viewMenu.add(createMenuItem("Show Splash Screen", e -> callbacks.showSplashScreen()));
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
}