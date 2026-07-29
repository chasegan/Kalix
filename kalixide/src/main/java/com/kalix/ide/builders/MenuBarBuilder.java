package com.kalix.ide.builders;

import com.kalix.ide.constants.AppConstants;
import com.kalix.ide.constants.AppShortcut;
import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.themes.NodeTheme;

import javax.swing.Action;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.text.DefaultEditorKit;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builder class for creating and configuring the application menu bar.
 * Handles all menu creation and organization logic.
 */
public class MenuBarBuilder {
    
    private final MenuBarCallbacks callbacks;
    private JMenu fileMenu;
    private JMenu recentFilesSubmenu;
    private JMenu recentFoldersSubMenu;
    
    /**
     * Interface defining all callback methods needed for menu and toolbar actions.
     */
    public interface MenuBarCallbacks {
        void newModel();
        void openModel();
        void openFolder();
        void saveModel();
        void saveAsModel();
        void saveAllModels();
        void exitApplication();
        void undoAction();
        void redoAction();
        boolean canUndo();
        boolean canRedo();
        void toggleCommentAction();
        void normalizeLineEndings();
        void zoomIn();
        void zoomOut();
        void resetZoom();
        void zoomToFit();
        void toggleFileTree();
        boolean isFileTreeVisible();
        void toggleShowHiddenFiles(boolean show);
        boolean isShowHiddenFiles();
        void toggleMap();
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
        void toggleLabels(boolean showLabels);
        boolean isLabelsVisible();
        void toggleAutoReload(boolean enabled);
        boolean isAutoReloadEnabled();

        // System menu
        void copyModelPath();
        void openTerminalHere();

        // External editor
        void openExternalEditor();

        // File manager
        void openFileManager();

        // AI menu
        void initClaudeMd();
        void initAgentsMd();

        // Parameter Sheet
        void showParameterSheet();

        // Navigation history
        void navigateBack();
        void navigateForward();
        boolean canNavigateBack();
        boolean canNavigateForward();
    }
    
    /**
     * Creates a new MenuBarBuilder instance.
     * 
     * @param callbacks The callback interface for menu actions
     * @param textEditor The text editor for editor-specific menu items
     */
    public MenuBarBuilder(MenuBarCallbacks callbacks, EnhancedTextEditor textEditor) {
        this.callbacks = callbacks;
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
     * Helper method for {@link #rebuildRecentFiles(List, Consumer)} and {@link #rebuildRecentFolders(List, Consumer)},
     * referring to a folder and file both as a generic file on the operating system.
     *
     * @param recentFiles      List of recent file or folder paths
     * @param fileOpenCallback Callback to open a file or folder
     * @param submenu          The submenu to rebuild
     */
    private void rebuildRecentFilesHelper(List<String> recentFiles, Consumer<String> fileOpenCallback, JMenu submenu,
                                          String emptyMessage){
        if (submenu == null) return;

        // Remove all existing menu items
        submenu.removeAll();

        // If empty, add disabled placeholder
        if (recentFiles.isEmpty()) {
            JMenuItem emptyItem = new JMenuItem(emptyMessage);
            emptyItem.setEnabled(false);
            submenu.add(emptyItem);
        } else {
            // Add recent file items
            for (int i = 0; i < recentFiles.size(); i++) {
                String fPath = recentFiles.get(i);
                String fName = new File(fPath).getName();
                String displayText = String.format("%d. %s", i + 1, fName);
                JMenuItem item = new JMenuItem(displayText);
                item.setToolTipText(fPath);
                item.addActionListener(e -> fileOpenCallback.accept(fPath));
                submenu.add(item);
            }
        }
    }

    /**
     * Rebuild the recent files popup menu.
     *
     * @param recentFiles List of recent file paths
     * @param fileOpenCallback Callback to open a file
     */
    public void rebuildRecentFiles(List<String> recentFiles,  Consumer<String> fileOpenCallback) {
        rebuildRecentFilesHelper(recentFiles, fileOpenCallback, recentFilesSubmenu,
                AppConstants.MENU_NO_RECENT_FILES);
    }

    /**
     * Rebuild the recent folders popup menu.
     *
     * @param recentFolders List of recent folder paths
     * @param folderOpenCallback Callback to open a folder
     */
    public void rebuildRecentFolders(List<String> recentFolders,  Consumer<String> folderOpenCallback) {
        rebuildRecentFilesHelper(recentFolders, folderOpenCallback, recentFoldersSubMenu,
                AppConstants.MENU_NO_RECENT_FOLDERS);
    }

    /**
     * Creates the File menu.
     */
    private JMenu createFileMenu() {
        fileMenu = new JMenu("File");

        fileMenu.add(createMenuItem("New", AppShortcut.NEW_MODEL, e -> callbacks.newModel()));
        fileMenu.add(createMenuItem("Open Model...", AppShortcut.OPEN_MODEL, e -> callbacks.openModel()));
        fileMenu.add(createMenuItem("Open Folder...", AppShortcut.OPEN_FOLDER,
                e -> callbacks.openFolder()));

        fileMenu.addSeparator();
        recentFilesSubmenu = new JMenu("Recent files");
        fileMenu.add(recentFilesSubmenu);
        recentFoldersSubMenu = new JMenu("Recent folders");
        fileMenu.add(recentFoldersSubMenu);

        fileMenu.addSeparator();
        fileMenu.add(createMenuItem("Save", AppShortcut.SAVE_MODEL, e -> callbacks.saveModel()));
        fileMenu.add(createMenuItem("Save As...", AppShortcut.SAVE_MODEL_AS,
                e -> callbacks.saveAsModel()));
        fileMenu.add(createMenuItem("Save All", e -> callbacks.saveAllModels()));
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem("Preferences", AppShortcut.PREFERENCES,
                e -> callbacks.showPreferences()));

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

        JMenuItem undoItem = createMenuItem("Undo", AppShortcut.UNDO, e -> callbacks.undoAction());
        JMenuItem redoItem = createMenuItem("Redo", AppShortcut.REDO, e -> callbacks.redoAction());
        editMenu.add(undoItem);
        editMenu.add(redoItem);
        // Grey out Undo/Redo when there's nothing to undo/redo. Refreshed each time the
        // menu opens, since the active document (and thus its undo stack) can change.
        editMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                undoItem.setEnabled(callbacks.canUndo());
                redoItem.setEnabled(callbacks.canRedo());
            }

            @Override
            public void menuDeselected(MenuEvent e) { }

            @Override
            public void menuCanceled(MenuEvent e) { }
        });
        editMenu.addSeparator();
        editMenu.add(createTextActionItem("Cut", new DefaultEditorKit.CutAction(), AppShortcut.CUT));
        editMenu.add(createTextActionItem("Copy", new DefaultEditorKit.CopyAction(), AppShortcut.COPY));
        editMenu.add(createTextActionItem("Paste", new DefaultEditorKit.PasteAction(), AppShortcut.PASTE));
        editMenu.addSeparator();
        editMenu.add(createMenuItem("Toggle Comment", AppShortcut.TOGGLE_COMMENT, e -> callbacks.toggleCommentAction()));
        editMenu.add(createMenuItem("Normalize Line Endings", e -> callbacks.normalizeLineEndings()));
        editMenu.addSeparator();
        editMenu.add(createMenuItem("Find...", AppShortcut.FIND, e -> callbacks.searchModel()));
        editMenu.add(createMenuItem("Find and Replace...", AppShortcut.FIND_AND_REPLACE, e -> callbacks.showFindReplaceDialog()));
        editMenu.add(createMenuItem("Find on Map...", e -> callbacks.findNodeOnMap()));

        return editMenu;
    }
    
    
    /**
     * Creates the View menu.
     */
    private JMenu createViewMenu() {
        JMenu viewMenu = new JMenu("View");

        // Panel visibility toggles at the top
        viewMenu.add(createMenuItem("Toggle File Tree", AppShortcut.TOGGLE_FILE_TREE, e -> callbacks.toggleFileTree()));
        viewMenu.add(createMenuItem("Toggle Map", e -> callbacks.toggleMap()));

        // Tree content toggle: reflect the live state each time the menu opens, since it can also be
        // changed from the tree's right-click menu.
        JCheckBoxMenuItem showHiddenItem = new JCheckBoxMenuItem("Show Hidden Files");
        showHiddenItem.addActionListener(e -> callbacks.toggleShowHiddenFiles(showHiddenItem.isSelected()));
        viewMenu.add(showHiddenItem);
        viewMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                showHiddenItem.setSelected(callbacks.isShowHiddenFiles());
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
        viewMenu.addSeparator();

        // Zoom to Fit
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
        runMenu.add(createMenuItem("Run Model", AppShortcut.RUN_MODEL, e -> callbacks.runModelFromMemory()));
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
        toolsMenu.add(createMenuItem("Parameter Sheet", e -> callbacks.showParameterSheet()));
        toolsMenu.addSeparator();
        toolsMenu.add(createMenuItem("FlowViz", e -> callbacks.flowViz()));
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

        systemMenu.add(createMenuItem("Copy Model Path", e -> callbacks.copyModelPath()));
        systemMenu.addSeparator();
        systemMenu.add(createMenuItem("Terminal", AppShortcut.TERMINAL,
                e -> callbacks.openTerminalHere()));
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

    /**
     * Creates a menu item whose accelerator comes from the given {@link AppShortcut} —
     * the single source of truth shared with the toolbar tooltips, so the accelerator
     * shown here and the hint shown there cannot disagree. The look-and-feel renders it
     * as a native accelerator hint, right-aligned and styled consistently.
     *
     * <p>When a text component has focus and shares the same keystroke in its
     * own input map, the text component handles the keystroke first; the
     * accelerator only fires as a fallback when focus is elsewhere. No double
     * execution.</p>
     */
    private JMenuItem createMenuItem(String text, AppShortcut shortcut, ActionListener listener) {
        JMenuItem item = new JMenuItem(text);
        item.setAccelerator(shortcut.keyStroke());
        item.addActionListener(listener);
        return item;
    }

    /**
     * Creates an Edit-menu item backed by a standard text-editing action
     * ({@link DefaultEditorKit#cutAction} etc.). These actions act on whichever
     * text component most recently held focus - the idiomatic Swing way to wire
     * Cut/Copy/Paste menu items with no manual routing.
     */
    private JMenuItem createTextActionItem(String label, Action action, AppShortcut shortcut) {
        JMenuItem item = new JMenuItem(action);
        item.setText(label);
        item.setAccelerator(shortcut.keyStroke());
        return item;
    }

}