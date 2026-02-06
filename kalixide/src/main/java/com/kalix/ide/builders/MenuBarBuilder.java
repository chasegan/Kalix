package com.kalix.ide.builders;

import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.managers.KeyboardShortcutManager;
import com.kalix.ide.themes.NodeTheme;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.List;

/**
 * Builder class for creating and configuring the application menu bar.
 * Handles all menu creation and organization logic.
 */
public class MenuBarBuilder {
    
    private final MenuBarCallbacks callbacks;
    private final KeyboardShortcutManager shortcutManager;
    private JMenu fileMenu;
    private int recentFilesSectionStart;  // Index where recent files section begins
    
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
        void setNodeTheme(NodeTheme.Theme theme);
        void flowViz();
        void showAbout();
        void updateStatus(String message);
        
        // New toolbar-specific actions
        void runModelFromMemory();
        void searchModel();
        void showFindReplaceDialog();
        void findNodeOnMap();

        // Run Manager window
        void showRunManager();

        // Optimiser window
        void showOptimisation();

        // Session Manager window
        void showSessionManager();

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

        // AI menu
        void initClaudeMd();
        void initAgentsMd();
    }
    
    /**
     * Creates a new MenuBarBuilder instance.
     * 
     * @param callbacks The callback interface for menu actions
     * @param textEditor The text editor for editor-specific menu items
     */
    public MenuBarBuilder(MenuBarCallbacks callbacks, EnhancedTextEditor textEditor) {
        this.callbacks = callbacks;
        this.shortcutManager = KeyboardShortcutManager.getInstance();
    }
    
    /**
     * Builds and returns the complete menu bar.
     *
     * @return The configured JMenuBar
     */
    public JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        menuBar.add(createFileMenu());
        menuBar.add(createEditMenu());
        menuBar.add(createViewMenu());
        menuBar.add(createRunMenu());
        menuBar.add(createToolsMenu());
        menuBar.add(createAIMenu());
        menuBar.add(createSystemMenu());
        menuBar.add(createHelpMenu());

        return menuBar;
    }
    
    /**
     * Rebuilds the recent files section of the File menu.
     * Clears existing recent files and Exit, then rebuilds cleanly.
     *
     * @param recentFiles List of recent file paths to display
     * @param fileOpenCallback Callback when a recent file is clicked
     */
    public void rebuildRecentFilesSection(List<String> recentFiles, java.util.function.Consumer<String> fileOpenCallback) {
        if (fileMenu == null) return;

        // Remove everything from recentFilesSectionStart to end
        while (fileMenu.getMenuComponentCount() > recentFilesSectionStart) {
            fileMenu.remove(recentFilesSectionStart);
        }

        // Add separator before recent files
        fileMenu.addSeparator();

        // Add recent file items
        for (int i = 0; i < recentFiles.size(); i++) {
            String filePath = recentFiles.get(i);
            String fileName = new File(filePath).getName();
            String displayText = String.format("%d. %s", i + 1, fileName);

            JMenuItem item = new JMenuItem(displayText);
            item.setToolTipText(filePath);
            item.addActionListener(e -> fileOpenCallback.accept(filePath));
            fileMenu.add(item);
        }

        // Add separator before Exit (only if we have recent files)
        if (!recentFiles.isEmpty()) {
            fileMenu.addSeparator();
        }

        // Add Exit at the end
        fileMenu.add(createMenuItem("Exit", e -> callbacks.exitApplication()));
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

        // Mark where the recent files section starts (after Preferences)
        recentFilesSectionStart = fileMenu.getMenuComponentCount();

        // Add initial separator and Exit - will be rebuilt when recent files are loaded
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem("Exit", e -> callbacks.exitApplication()));

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
        editMenu.add(createMenuItem("Find on Map...", e -> callbacks.findNodeOnMap()));

        return editMenu;
    }
    
    
    /**
     * Creates the View menu.
     */
    private JMenu createViewMenu() {
        JMenu viewMenu = new JMenu("View");

        // Zoom to Fit at the top
        viewMenu.add(createMenuItem("Zoom to Fit", e -> callbacks.zoomToFit()));
        viewMenu.addSeparator();

        // Zoom controls
        viewMenu.add(createMenuItem("Zoom In", e -> callbacks.zoomIn()));
        viewMenu.add(createMenuItem("Zoom Out", e -> callbacks.zoomOut()));
        viewMenu.add(createMenuItem("Reset", e -> callbacks.resetZoom()));

        return viewMenu;
    }

    /**
     * Creates the Run menu.
     */
    private JMenu createRunMenu() {
        JMenu runMenu = new JMenu("Run");
        runMenu.add(createMenuItem("Run Model", e -> callbacks.runModelFromMemory()));
        runMenu.addSeparator();
        runMenu.add(createMenuItem("Optimiser", e -> callbacks.showOptimisation()));
        runMenu.add(createMenuItem("Run Manager", e -> callbacks.showRunManager()));
        return runMenu;
    }
    
    /**
     * Creates the Tools menu.
     */
    private JMenu createToolsMenu() {
        JMenu toolsMenu = new JMenu("Tools");
        toolsMenu.add(createMenuItem("FlowViz", e -> callbacks.flowViz()));
        toolsMenu.addSeparator();
        toolsMenu.add(createMenuItem("KalixCLI sessions", e -> callbacks.showSessionManager()));
        return toolsMenu;
    }

    /**
     * Creates the AI menu.
     */
    private JMenu createAIMenu() {
        JMenu aiMenu = new JMenu("AI");

        aiMenu.add(createMenuItem("Init CLAUDE.md", e -> callbacks.initClaudeMd()));
        aiMenu.add(createMenuItem("Init AGENTS.md", e -> callbacks.initAgentsMd()));

        return aiMenu;
    }

    /**
     * Creates the System menu.
     */
    private JMenu createSystemMenu() {
        JMenu systemMenu = new JMenu("System");

        systemMenu.add(createMenuItem("Terminal", e -> callbacks.openTerminalHere()));
        systemMenu.add(createMenuItem("Visual Studio Code", e -> callbacks.openExternalEditor()));
        systemMenu.add(createMenuItem("File Manager", e -> callbacks.openFileManager()));

        return systemMenu;
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