package com.kalix.ide;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kalix.ide.builders.MenuBarBuilder;
import com.kalix.ide.builders.ToolBarBuilder;
import com.kalix.ide.cli.ProcessExecutor;
import com.kalix.ide.components.AutoHidingProgressBar;
import com.kalix.ide.constants.AppConstants;
import com.kalix.ide.dialogs.PreferencesDialog;
import com.kalix.ide.linter.SchemaManager;
import com.kalix.ide.docking.DockableMapPanel;
import com.kalix.ide.docking.DockablePanel;
import com.kalix.ide.docking.DockableTextEditor;
import com.kalix.ide.docking.DockingArea;
import com.kalix.ide.docking.DockingContext;
import com.kalix.ide.docking.DockingManager;
import com.kalix.ide.docking.DockingSplitPane;
import com.kalix.ide.docking.DropZoneDetector;
import com.kalix.ide.docking.PlaceholderComponent;
import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.handlers.FileDropHandler;
import com.kalix.ide.managers.ThemeManager;
import com.kalix.ide.managers.RecentFilesManager;
import com.kalix.ide.managers.FileOperationsManager;
import com.kalix.ide.managers.VersionChecker;
import com.kalix.ide.managers.TitleBarManager;
import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.managers.FileWatcherManager;
import com.kalix.ide.managers.IconManager;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelChangeEvent;
import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.themes.NodeTheme;
import com.kalix.ide.utils.DialogUtils;
import com.kalix.ide.utils.TerminalLauncher;
import com.kalix.ide.windows.RunManager;
import com.kalix.ide.windows.OptimisationWindow;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.KeyStroke;
import java.io.File;
import java.util.prefs.Preferences;

/**
 * Main GUI class for the Kalix Hydrologic Modeling application.
 * 
 * This class serves as the primary window and coordinator for the application,
 * managing the overall layout and coordinating between different components
 * such as the map panel and text editor. It delegates specific functionality
 * to specialized manager classes for better organization and maintainability.
 * 
 * Key features:
 * - Split-pane layout with map visualization and text editing
 * - Comprehensive menu system with theme switching
 * - Recent files management
 * - Drag and drop file support
 * - Font customization
 * - Multiple UI themes
 * 
 * @author Kalix Development Team
 * @version 1.0
 */
public class KalixIDE extends JFrame implements MenuBarBuilder.MenuBarCallbacks {
    private static final Logger logger = LoggerFactory.getLogger(KalixIDE.class);

    // Core UI components
    private MapPanel mapPanel;
    private EnhancedTextEditor textEditor;
    private DockableMapPanel dockableMapPanel;
    private DockableTextEditor dockableTextEditor;
    private DockingArea mainDockingArea;
    private JLabel statusLabel;
    private AutoHidingProgressBar progressBar;
    private JToolBar toolBar;
    
    // Manager classes for specialized functionality
    private ThemeManager themeManager;
    private RecentFilesManager recentFilesManager;
    private FileOperationsManager fileOperations;
    private FileDropHandler fileDropHandler;
    private VersionChecker versionChecker;
    private TitleBarManager titleBarManager;
    private ProcessExecutor processExecutor;
    private StdioTaskManager stdioTaskManager;
    private FileWatcherManager fileWatcherManager;
    private SchemaManager schemaManager;

    // Data model
    private HydrologicalModel hydrologicalModel;
    
    // Application state
    private Preferences prefs;
    private int previousNodeCount = 0;

    /**
     * Creates a new KalixIDE instance.
     * Initializes all components, managers, and sets up the user interface.
     */
    public KalixIDE() {
        initializeApplication();
    }
    
    /**
     * Initializes the application by setting up all managers and components.
     */
    private void initializeApplication() {
        // Initialize preferences
        prefs = Preferences.userNodeForPackage(KalixIDE.class);
        
        // Set up basic window properties
        setupWindow();
        
        // Initialize all managers
        initializeManagers();
        
        // Initialize UI components
        initializeComponents();
        
        // Set up layout and interactions
        setupLayout();
        setupMenuBar();
        setupDragAndDrop();
        setupWindowListeners();
        setupGlobalKeyBindings();
        
        // Note: Split pane divider position loading removed - using simple grid layout now

        setVisible(true);

        // Load last opened file if available and exists (after all UI initialization is complete)
        loadLastOpenedFile();
    }
    
    /**
     * Sets up basic window properties.
     */
    private void setupWindow() {
        IconManager.SetIcon(this);
        setTitle(AppConstants.APP_NAME);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(AppConstants.DEFAULT_WINDOW_WIDTH, AppConstants.DEFAULT_WINDOW_HEIGHT);
        setLocationRelativeTo(null);
    }
    
    /**
     * Initializes all manager classes.
     */
    private void initializeManagers() {
        // Theme manager
        themeManager = new ThemeManager(prefs, this);
        
        // Title bar manager
        titleBarManager = new TitleBarManager(this);
        
        // Process executor for CLI operations
        processExecutor = new ProcessExecutor();

        // File watcher manager
        fileWatcherManager = new FileWatcherManager(this::handleFileReload);

        // Schema manager for linting
        schemaManager = new SchemaManager();
        schemaManager.initialize();

        // Initialize data model with change listener
        hydrologicalModel = new HydrologicalModel();
        hydrologicalModel.addChangeListener(this::onModelChanged);
        
        // File operations manager (initialized after components)
        
        // Recent files manager
        recentFilesManager = new RecentFilesManager(
            prefs,
            this::loadModelFile,
            () -> updateStatus(AppConstants.STATUS_RECENT_FILES_CLEARED)
        );
    }

    /**
     * Initializes the docking context with common services and actions.
     */
    private void initializeDockingContext() {
        DockingContext context = DockingContext.getInstance();

        // Register common actions that docked panels might want to access
        context.registerAction("zoom_in", new AbstractAction("Zoom In") {
            @Override
            public void actionPerformed(ActionEvent e) {
                zoomIn();
            }
        });

        context.registerAction("zoom_out", new AbstractAction("Zoom Out") {
            @Override
            public void actionPerformed(ActionEvent e) {
                zoomOut();
            }
        });

        context.registerAction("save", new AbstractAction("Save") {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveModel();
            }
        });

        // Register status update service
        context.registerService("statusUpdater", (Runnable) () ->
            updateStatus("Status updated from docked panel"));
    }

    /**
     * Initializes all UI components.
     */
    private void initializeComponents() {
        // Initialize docking context for panel communication
        initializeDockingContext();

        // Initialize dockable components
        dockableMapPanel = new DockableMapPanel();
        dockableTextEditor = new DockableTextEditor();

        // Get references to the underlying components for existing functionality
        mapPanel = dockableMapPanel.getMapPanel();
        textEditor = dockableTextEditor.getTextEditor();

        // Initialize linter for text editor
        textEditor.initializeLinter(schemaManager);

        // Set up linter base directory supplier to use current file's directory
        textEditor.setLinterBaseDirectorySupplier(() -> {
            if (fileOperations != null) {
                return fileOperations.getCurrentWorkingDirectory();
            }
            return null;
        });

        // Set up RunManager base directory supplier for file dialogs
        RunManager.setBaseDirectorySupplier(() -> {
            if (fileOperations != null) {
                return fileOperations.getCurrentWorkingDirectory();
            }
            return null;
        });

        // Load saved node theme
        String savedNodeTheme = PreferenceManager.getFileString(PreferenceKeys.UI_NODE_THEME, AppConstants.DEFAULT_NODE_THEME);
        NodeTheme.Theme nodeTheme = NodeTheme.themeFromString(savedNodeTheme);
        mapPanel.setNodeTheme(nodeTheme);

        // Load saved gridlines preference
        boolean showGridlines = PreferenceManager.getFileBoolean(PreferenceKeys.MAP_SHOW_GRIDLINES, true); // Default to true
        mapPanel.setShowGridlines(showGridlines);
        textEditor.setText(AppConstants.DEFAULT_MODEL_TEXT);
        
        statusLabel = new JLabel(AppConstants.STATUS_READY);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(
            AppConstants.STATUS_LABEL_BORDER_V, AppConstants.STATUS_LABEL_BORDER_H,
            AppConstants.STATUS_LABEL_BORDER_V, AppConstants.STATUS_LABEL_BORDER_H
        ));
        
        progressBar = new AutoHidingProgressBar();
        
        // Complete manager initialization now that components exist
        fileOperations = new FileOperationsManager(
            this, textEditor, mapPanel,
            this::updateStatus,
            recentFilesManager::addRecentFile,
            () -> {
                // Update title bar
                titleBarManager.updateTitle(textEditor.isDirty(), fileOperations::getCurrentFile);
                // Update file watcher
                fileWatcherManager.watchFile(fileOperations.getCurrentFile());
            },
            () -> updateModelFromText(true) // Model update callback for when files are loaded with auto-zoom
        );
        
        fileDropHandler = new FileDropHandler(fileOperations, this::updateStatus);
        versionChecker = new VersionChecker(this::updateStatus);
        
        // Initialize STDIO task manager
        stdioTaskManager = new StdioTaskManager(
            processExecutor,
            this::updateStatus,
            progressBar,
            this,
            fileOperations::getCurrentWorkingDirectory
        );
        
        
        // Set up component listeners
        textEditor.setDirtyStateListener(isDirty -> titleBarManager.updateTitle(isDirty, fileOperations::getCurrentFile));
        textEditor.setFileDropHandler(fileOperations::loadModelFile);
        
        // Set up model parsing from text changes
        textEditor.addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updateModelFromText();
            }
            
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateModelFromText();
            }
            
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updateModelFromText();
            }
        });
        
        // Connect map panel to data model
        mapPanel.setModel(hydrologicalModel);
        
        // Set up bidirectional text synchronization
        mapPanel.setupTextSynchronization(textEditor);
        
        // Register theme-aware components with theme manager
        themeManager.registerThemeAwareComponents(mapPanel, textEditor);
        
        // Initial parse of default text
        updateModelFromText();
    }

    /**
     * Sets up the main application layout using a single docking area.
     */
    private void setupLayout() {
        setLayout(new BorderLayout());

        // Add toolbar at top
        ToolBarBuilder toolBarBuilder = new ToolBarBuilder(this);
        toolBar = toolBarBuilder.buildToolBar();
        add(toolBar, BorderLayout.NORTH);

        // Create a single main docking area
        mainDockingArea = new DockingArea("Main Workspace");

        // Initialize with both panels using a custom split layout
        SwingUtilities.invokeLater(() -> {
            initializeDockingLayout();
        });

        add(mainDockingArea, BorderLayout.CENTER);

        // Add status bar at bottom
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(statusLabel, BorderLayout.WEST);
        statusPanel.add(progressBar, BorderLayout.EAST);
        statusPanel.setBorder(BorderFactory.createLoweredBevelBorder());
        add(statusPanel, BorderLayout.SOUTH);
    }

    /**
     * Sets up the application menu bar using MenuBarBuilder.
     */
    private void setupMenuBar() {
        MenuBarBuilder menuBuilder = new MenuBarBuilder(this, textEditor);
        JMenuBar menuBar = menuBuilder.buildMenuBar(themeManager.getCurrentTheme(), mapPanel.getCurrentNodeTheme());
        setJMenuBar(menuBar);

        // Update recent files menu
        recentFilesManager.updateRecentFilesMenu(menuBuilder.getRecentFilesMenu());
    }

    /**
     * Updates the menu bar to reflect current state changes.
     */
    private void updateMenuBar() {
        setupMenuBar();
    }
    
    /**
     * Sets up drag and drop functionality using FileDropHandler.
     */
    private void setupDragAndDrop() {
        fileDropHandler.setupDragAndDrop(this);
    }
    
    /**
     * Sets up window event listeners including resize listener for dynamic title bar updates.
     */
    private void setupWindowListeners() {
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // Update title bar when window is resized to ensure path still fits
                titleBarManager.updateTitle(textEditor.isDirty(), fileOperations::getCurrentFile);
            }
        });

        // Handle window closing to ensure proper cleanup
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                exitApplication();
            }
        });
    }
    
    /**
     * Sets up global key bindings for undo/redo that work regardless of component focus.
     */
    private void setupGlobalKeyBindings() {
        JRootPane rootPane = getRootPane();
        InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = rootPane.getActionMap();
        
        // Global Ctrl+Z for undo
        KeyStroke ctrlZ = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(ctrlZ, "global-undo");
        actionMap.put("global-undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (textEditor.canUndo()) {
                    textEditor.undo();
                    updateStatus(AppConstants.STATUS_UNDO);
                } else {
                    updateStatus(AppConstants.STATUS_NOTHING_TO_UNDO);
                }
            }
        });
        
        // Global Ctrl+Y for redo
        KeyStroke ctrlY = KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(ctrlY, "global-redo");
        actionMap.put("global-redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (textEditor.canRedo()) {
                    textEditor.redo();
                    updateStatus(AppConstants.STATUS_REDO);
                } else {
                    updateStatus(AppConstants.STATUS_NOTHING_TO_REDO);
                }
            }
        });
        
        // Alternative Ctrl+Shift+Z for redo
        KeyStroke ctrlShiftZ = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        inputMap.put(ctrlShiftZ, "global-redo-alt");
        actionMap.put("global-redo-alt", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (textEditor.canRedo()) {
                    textEditor.redo();
                    updateStatus(AppConstants.STATUS_REDO);
                } else {
                    updateStatus(AppConstants.STATUS_NOTHING_TO_REDO);
                }
            }
        });

        // Global Ctrl+S/Cmd+S for save
        KeyStroke ctrlS = KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK);
        KeyStroke cmdS = KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.META_DOWN_MASK);
        inputMap.put(ctrlS, "global-save");
        inputMap.put(cmdS, "global-save");
        actionMap.put("global-save", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveModel();
                updateStatus("Model saved");
            }
        });
    }
    
    /**
     * Updates the status label with the given message.
     * 
     * @param message The status message to display
     */
    public void updateStatus(String message) {
        statusLabel.setText(message);
    }
    
    /**
     * Handles model change events and updates status bar.
     */
    private void onModelChanged(ModelChangeEvent event) {
        SwingUtilities.invokeLater(() -> {
            var stats = hydrologicalModel.getStatistics();
            int currentNodeCount = stats.getNodeCount();
            String baseMessage = String.format("Model: %d nodes, %d links", 
                currentNodeCount, stats.getLinkCount());
            
            // Auto-zoom to fit when transitioning from 0 to >0 nodes (text editing case)
            if (previousNodeCount == 0 && currentNodeCount > 0) {
                mapPanel.zoomToFit();
            }
            previousNodeCount = currentNodeCount;
            
            // Add affected counts if there were changes
            if (event.getAffectedNodeCount() > 0 || event.getAffectedLinkCount() > 0) {
                String changeMessage = String.format(" (%d nodes, %d links modified)",
                    event.getAffectedNodeCount(), event.getAffectedLinkCount());
                updateStatus(baseMessage + changeMessage);
            } else {
                updateStatus(baseMessage);
            }
        });
    }
    
    /**
     * Updates the data model from the current text editor content.
     * Uses incremental parsing for better performance.
     * Called whenever the text changes.
     */
    private void updateModelFromText() {
        updateModelFromText(false);
    }

    private void updateModelFromText(boolean autoZoomToFit) {
        SwingUtilities.invokeLater(() -> {
            try {
                String text = textEditor.getText();
                if (text != null) {
                    // Check if we're currently updating text from model changes to prevent infinite loops
                    // We need to access the TextCoordinateUpdater to check this flag
                    // For now, we'll always parse - the TextCoordinateUpdater uses programmatic update flag
                    hydrologicalModel.parseFromIniTextIncremental(text);

                    // Auto-zoom to fit after parsing if requested (for file loads)
                    if (autoZoomToFit) {
                        mapPanel.zoomToFit();
                    }
                }
            } catch (Exception e) {
                // Log parsing errors but don't disrupt the UI
                logger.warn("Error parsing model from text: {}", e.getMessage());
            }
        });
    }
    
    /**
     * Convenience method for loading model files from file paths.
     * Used by recent files manager callback.
     * 
     * @param filePath The absolute path to the file to load
     */
    private void loadModelFile(String filePath) {
        fileOperations.loadModelFile(filePath);
    }
    
    
    
    /**
     * Initializes the docking layout by starting with the text editor and programmatically
     * docking the map panel to the left using the zone-based docking system.
     */
    private void initializeDockingLayout() {
        // Start with the text editor in the docking area
        mainDockingArea.addDockablePanel(dockableTextEditor);

        // Programmatically dock the map panel to the left zone
        // This uses the existing docking system logic to create a proper split pane
        DockingManager dockingManager = DockingManager.getInstance();

        // Simulate dropping the map panel into the LEFT zone of the text editor area
        // This will trigger the zone-based docking logic to create a horizontal split
        simulateZoneDrop(dockingManager, dockableMapPanel, mainDockingArea,
                        DropZoneDetector.DropZone.LEFT);
    }

    /**
     * Simulates a zone-based drop operation using the existing docking system logic.
     */
    private void simulateZoneDrop(DockingManager dockingManager, DockablePanel panel,
                                 DockingArea area, DropZoneDetector.DropZone zone) {
        // We need to access the private handleZoneDrop method
        // For now, let's implement the LEFT zone logic directly
        if (zone == DropZoneDetector.DropZone.LEFT) {
            // Get the existing content (should be the text editor)
            Component existingContent = null;
            for (Component comp : area.getComponents()) {
                if (!(comp instanceof PlaceholderComponent)) {
                    existingContent = comp;
                    break;
                }
            }

            if (existingContent != null) {
                // Remove all components without triggering empty state check (avoids placeholder re-addition)
                area.removeAllQuietly();

                // Create horizontal split pane with existing content on left, map panel on right
                DockingSplitPane splitPane = new DockingSplitPane(JSplitPane.HORIZONTAL_SPLIT);
                splitPane.setLeftComponent(existingContent);
                splitPane.setRightComponent(panel);

                // Set the standard divider location and resize weight
                splitPane.setDividerLocation(AppConstants.DEFAULT_SPLIT_PANE_DIVIDER_LOCATION);
                splitPane.setResizeWeight(AppConstants.DEFAULT_SPLIT_PANE_RESIZE_WEIGHT);

                // Add split pane to area
                area.add(splitPane, BorderLayout.CENTER);
                area.revalidate();
                area.repaint();
            }
        }
    }

    //
    // MenuBarCallbacks interface implementation
    //
    
    /**
     * Creates a new model by clearing the current content.
     * This action will prompt the user to save any unsaved changes.
     */
    @Override
    public void newModel() {
        fileOperations.newModel();
    }

    /**
     * Opens a model file using a file chooser dialog.
     * Supported formats include .ini files.
     */
    @Override
    public void openModel() {
        fileOperations.openModel();
    }
    
    /**
     * Saves the current model to its existing file location.
     * If no file is associated, prompts for a save location.
     */
    @Override
    public void saveModel() {
        fileOperations.saveModel();
    }

    /**
     * Saves the current model to a new file location.
     * Always prompts the user to choose a save location.
     */
    @Override
    public void saveAsModel() {
        fileOperations.saveAsModel();
    }

    /**
     * Exits the application gracefully.
     * This will terminate the Java Virtual Machine.
     */
    @Override
    public void exitApplication() {
        // Check if we should prompt to save unsaved changes
        boolean promptOnExit = PreferenceManager.getFileBoolean(PreferenceKeys.FILE_PROMPT_SAVE_ON_EXIT, true);

        if (promptOnExit && textEditor.isDirty()) {
            // Show save confirmation dialog
            String fileName = fileOperations.getCurrentFile() != null ?
                fileOperations.getCurrentFile().getName() : "Untitled";

            int choice = JOptionPane.showConfirmDialog(
                this,
                "You have unsaved changes in \"" + fileName + "\".\n\nDo you want to save your changes before closing?",
                "Unsaved Changes",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
            );

            switch (choice) {
                case JOptionPane.YES_OPTION:
                    // Save the file first
                    try {
                        if (fileOperations.getCurrentFile() != null) {
                            fileOperations.saveModel();
                        } else {
                            // No current file, need to save as
                            fileOperations.saveAsModel();
                            // Check if save succeeded by seeing if we now have a current file
                            if (fileOperations.getCurrentFile() == null) {
                                // User cancelled save as dialog or save failed
                                return;
                            }
                        }
                    } catch (Exception e) {
                        JOptionPane.showMessageDialog(
                            this,
                            "Failed to save file: " + e.getMessage(),
                            "Save Error",
                            JOptionPane.ERROR_MESSAGE
                        );
                        return;
                    }
                    break;
                case JOptionPane.NO_OPTION:
                    // Don't save, just exit
                    break;
                case JOptionPane.CANCEL_OPTION:
                default:
                    // Cancel exit
                    return;
            }
        }

        // Clean up resources before exiting
        // First shutdown CLI sessions to avoid ProcessExecutor waiting
        if (stdioTaskManager != null) {
            stdioTaskManager.getSessionManager().shutdown();
        }
        if (fileWatcherManager != null) {
            fileWatcherManager.shutdown();
        }
        if (processExecutor != null) {
            processExecutor.shutdown();
        }
        System.exit(0);
    }

    /**
     * Performs an undo operation in the text editor.
     * Updates the status bar with the result of the operation.
     */
    @Override
    public void undoAction() {
        if (textEditor.canUndo()) {
            textEditor.undo();
            updateStatus(AppConstants.STATUS_UNDO);
        } else {
            updateStatus(AppConstants.STATUS_NOTHING_TO_UNDO);
        }
    }

    /**
     * Performs a redo operation in the text editor.
     * Updates the status bar with the result of the operation.
     */
    @Override
    public void redoAction() {
        if (textEditor.canRedo()) {
            textEditor.redo();
            updateStatus(AppConstants.STATUS_REDO);
        } else {
            updateStatus(AppConstants.STATUS_NOTHING_TO_REDO);
        }
    }

    @Override
    public void cutAction() {
        textEditor.cut();
        updateStatus(AppConstants.STATUS_CUT);
    }

    @Override
    public void copyAction() {
        textEditor.copy();
        updateStatus(AppConstants.STATUS_COPY);
    }

    @Override
    public void pasteAction() {
        textEditor.paste();
        updateStatus(AppConstants.STATUS_PASTE);
    }

    @Override
    public void toggleCommentAction() {
        textEditor.toggleComment();
    }

    @Override
    public void normalizeLineEndings() {
        textEditor.normalizeLineEndings();
        updateStatus("Line endings normalized to Unix format (LF)");
    }

    @Override
    public void zoomIn() {
        mapPanel.zoomIn();
        updateStatus(AppConstants.STATUS_ZOOMED_IN);
    }

    @Override
    public void zoomOut() {
        mapPanel.zoomOut();
        updateStatus(AppConstants.STATUS_ZOOMED_OUT);
    }

    @Override
    public void resetZoom() {
        mapPanel.resetZoom();
        updateStatus(AppConstants.STATUS_ZOOM_RESET);
    }
    
    @Override
    public void zoomToFit() {
        mapPanel.zoomToFit();
        updateStatus("Zoomed to fit all nodes");
    }

    @Override
    public void flowViz() {
        com.kalix.ide.flowviz.FlowVizWindow.createNewWindow();
    }


    @Override
    public void showAbout() {
        DialogUtils.showInfo(this,
            AppConstants.APP_NAME + "\nVersion " + AppConstants.APP_VERSION + "\n\n" + AppConstants.APP_DESCRIPTION,
            "About Kalix IDE");
    }
    
    @Override
    public void openWebsite() {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    desktop.browse(java.net.URI.create(AppConstants.APP_WEBSITE_URL));
                    updateStatus("Opening Kalix development website");
                } else {
                    updateStatus("Browser not supported - website: " + AppConstants.APP_WEBSITE_URL);
                }
            } else {
                updateStatus("Desktop not supported - website: " + AppConstants.APP_WEBSITE_URL);
            }
        } catch (Exception e) {
            updateStatus("Error opening website: " + e.getMessage());
        }
    }
    
    
    @Override
    public void showPreferences() {
        // Create callback to handle preference changes
        PreferencesDialog.PreferenceChangeCallback callback = new PreferencesDialog.PreferenceChangeCallback() {
            @Override
            public void onAutoReloadChanged(boolean enabled) {
                // Use the existing method to properly update file watching
                toggleAutoReload(enabled);

                // Update menu bar to reflect the change
                updateMenuBar();
            }

            @Override
            public void onFlowVizPreferencesChanged() {
                // Update all open FlowViz windows with new preferences
                for (com.kalix.ide.flowviz.FlowVizWindow window : com.kalix.ide.flowviz.FlowVizWindow.getOpenWindows()) {
                    window.reloadPreferences();
                }
            }

            @Override
            public void onMapPreferencesChanged() {
                // Update map display with new preferences
                boolean showGridlines = PreferenceManager.getFileBoolean(PreferenceKeys.MAP_SHOW_GRIDLINES, true);
                toggleGridlines(showGridlines);

                // Update node theme
                String nodeThemeName = PreferenceManager.getFileString(PreferenceKeys.UI_NODE_THEME, AppConstants.DEFAULT_NODE_THEME);
                com.kalix.ide.themes.NodeTheme.Theme nodeTheme = com.kalix.ide.themes.NodeTheme.themeFromString(nodeThemeName);
                setNodeTheme(nodeTheme);
            }

            @Override
            public void onSystemActionRequested(String action) {
                if ("clearAppData".equals(action)) {
                    clearAppData();
                }
            }
        };

        PreferencesDialog preferencesDialog = new PreferencesDialog(this, themeManager, textEditor, schemaManager, callback);
        boolean preferencesChanged = preferencesDialog.showDialog();

        if (preferencesChanged) {
            updateStatus("Preferences updated");
        }
    }
    
    @Override
    public String switchTheme(String theme) {
        return themeManager.switchTheme(theme);
    }
    
    @Override
    public void setNodeTheme(NodeTheme.Theme theme) {
        mapPanel.setNodeTheme(theme);
        // Save the preference
        PreferenceManager.setFileString(PreferenceKeys.UI_NODE_THEME, NodeTheme.themeToString(theme));
    }
    
    public void clearAppData() {
        // Show confirmation dialog
        int result = JOptionPane.showConfirmDialog(
            this,
            "This will clear all Kalix IDE application data including:\n\n" +
            "• Theme preferences\n" +
            "• Node theme preferences\n" +
            "• Recent files list\n" +
            "• Window position and size settings\n" +
            "• Split pane divider positions\n" +
            "• All other saved preferences\n\n" +
            "Are you sure you want to continue?\n\n" +
            "Note: The application will restart after clearing data.",
            "Clear App Data",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        
        if (result == JOptionPane.YES_OPTION) {
            try {
                // Clear all preferences
                prefs.clear();
                
                // Clear recent files
                if (recentFilesManager != null) {
                    recentFilesManager.clearRecentFiles();
                }
                
                updateStatus("App data cleared. Application will restart...");
                
                // Schedule restart after a brief delay to show the status message
                SwingUtilities.invokeLater(() -> {
                    try {
                        Thread.sleep(1000); // Give user time to see the message
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    
                    // Exit the application - user will need to restart manually
                    // This is safer than trying to restart programmatically
                    JOptionPane.showMessageDialog(
                        this,
                        "App data has been cleared.\nPlease restart Kalix IDE to continue.",
                        "Restart Required",
                        JOptionPane.INFORMATION_MESSAGE
                    );
                    
                    System.exit(0);
                });
                
            } catch (Exception e) {
                updateStatus("Error clearing app data: " + e.getMessage());
                JOptionPane.showMessageDialog(
                    this,
                    "An error occurred while clearing app data:\n" + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                );
            }
        }
    }

    public void locatePreferenceFile() {
        try {
            String prefsPath = PreferenceManager.getPreferenceFilePath();
            File prefsFile = new File(prefsPath);
            File parentDir = prefsFile.getParentFile();

            // Use the directory containing the preference file (or would contain it)
            File targetDir = parentDir != null ? parentDir : new File(".");

            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(targetDir);
                    updateStatus("Opened folder: " + targetDir.getAbsolutePath());
                } else {
                    showLocationFallback(targetDir);
                }
            } else {
                showLocationFallback(targetDir);
            }
        } catch (Exception e) {
            updateStatus("Error locating preference file: " + e.getMessage());
            JOptionPane.showMessageDialog(
                this,
                "Could not open the folder containing the preference file.\n" +
                "Preference file location: " + PreferenceManager.getPreferenceFilePath(),
                "Cannot Open Folder",
                JOptionPane.INFORMATION_MESSAGE
            );
        }
    }

    /**
     * Fallback method to show preference file location when desktop operations aren't supported.
     */
    private void showLocationFallback(File targetDir) {
        JOptionPane.showMessageDialog(
            this,
            "Preference file is located in:\n" + targetDir.getAbsolutePath() + "\n\n" +
            "File name: kalix_prefs.json\n\n" +
            "Your system doesn't support automatically opening folders.\n" +
            "Please navigate to this location manually.",
            "Preference File Location",
            JOptionPane.INFORMATION_MESSAGE
        );
        updateStatus("Preference file location: " + targetDir.getAbsolutePath());
    }
    
    
    @Override
    public void searchModel() {
        textEditor.getSearchManager().showFindDialog();
    }
    
    @Override
    public void showFindReplaceDialog() {
        textEditor.getSearchManager().showFindReplaceDialog();
        updateStatus("Find and Replace dialog opened");
    }
    
    @Override
    public void getCliVersion() {
        versionChecker.checkVersionWithStatusUpdate();
    }
    
    
    @Override
    public void showRunManager() {
        RunManager.showRunManager(this, stdioTaskManager, this::updateStatus);
    }

    @Override
    public void showOptimisation() {
        OptimisationWindow.showOptimisationWindow(this, stdioTaskManager, this::updateStatus,
            progressBar,
            () -> {
                // Working directory supplier
                File currentFile = fileOperations.getCurrentFile();
                return currentFile != null ? currentFile.getParentFile() : null;
            },
            () -> {
                // Model text supplier
                return textEditor.getText();
            });
    }


    @Override
    public void runModelFromMemory() {
        String modelText = textEditor.getText();
        if (modelText == null || modelText.trim().isEmpty()) {
            updateStatus("Error: No model content to run");
            return;
        }
        
        updateStatus("Starting model run...");
        
        // Run model from memory and handle the session
        stdioTaskManager.runModelFromMemory(modelText)
            .thenAccept(sessionKey -> {
                SwingUtilities.invokeLater(() -> {
                    updateStatus("Model session started: " + sessionKey);

                    // Automatically open Run Manager if not already open
                    if (!RunManager.isWindowOpen()) {
                        RunManager.showRunManager(this, stdioTaskManager, this::updateStatus);
                    }

                    // Select the newly created run after a brief delay to allow Run Manager to refresh
                    Timer selectionTimer = new Timer(500, e -> {
                        RunManager.selectRunIfOpen(sessionKey);
                        ((Timer) e.getSource()).stop();
                    });
                    selectionTimer.setRepeats(false);
                    selectionTimer.start();
                });
            })
            .exceptionally(throwable -> {
                SwingUtilities.invokeLater(() -> {
                    updateStatus("Error starting model session: " + throwable.getMessage());
                });
                return null;
            });
    }
    
    @Override
    public void toggleGridlines(boolean showGridlines) {
        mapPanel.setShowGridlines(showGridlines);
        // Save preference
        PreferenceManager.setFileBoolean(PreferenceKeys.MAP_SHOW_GRIDLINES, showGridlines);
    }
    
    @Override
    public boolean isGridlinesVisible() {
        return mapPanel.isShowGridlines();
    }

    @Override
    public void toggleLinting() {
        boolean currentState = schemaManager.isLintingEnabled();
        boolean newState = !currentState;

        // Update schema manager preferences
        schemaManager.updatePreferences(
            newState,
            null, // Keep current schema path
            schemaManager.getDisabledRules()
        );

        updateStatus(newState ? "Linting enabled" : "Linting disabled");
    }

    @Override
    public boolean isLintingEnabled() {
        return schemaManager.isLintingEnabled();
    }

    @Override
    public void toggleAutoReload(boolean enabled) {
        fileWatcherManager.setAutoReloadEnabled(enabled);
    }

    @Override
    public boolean isAutoReloadEnabled() {
        return fileWatcherManager.isAutoReloadEnabled();
    }

    @Override
    public void openTerminalHere() {
        File targetDirectory = null;

        // Get the directory of the currently loaded file
        File currentFile = fileOperations.getCurrentFile();
        if (currentFile != null) {
            targetDirectory = currentFile.getParentFile();
        }

        try {
            TerminalLauncher.openTerminalAt(targetDirectory);

            // Show success message
            String dirPath = targetDirectory != null ? targetDirectory.getAbsolutePath() : System.getProperty("user.home");
            updateStatus("Terminal opened at: " + dirPath);

        } catch (Exception e) {
            String message = "Failed to open terminal: " + e.getMessage();
            updateStatus(message);
            logger.error("Error opening terminal", e);

            // Show error dialog
            JOptionPane.showMessageDialog(
                this,
                message,
                "Terminal Error",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    @Override
    public void openFileManager() {
        File targetDirectory = null;

        // Get the directory of the currently loaded file
        File currentFile = fileOperations.getCurrentFile();
        if (currentFile != null) {
            targetDirectory = currentFile.getParentFile();
        }

        try {
            com.kalix.ide.utils.FileManagerLauncher.openFileManagerAt(targetDirectory);

            // Show success message
            String dirPath = targetDirectory != null ? targetDirectory.getAbsolutePath() : System.getProperty("user.home");
            updateStatus("File manager opened at: " + dirPath);

        } catch (Exception e) {
            String message = "Failed to open file manager: " + e.getMessage();
            updateStatus(message);
            logger.error("Error opening file manager", e);

            // Show error dialog
            JOptionPane.showMessageDialog(
                this,
                message,
                "File Manager Error",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    @Override
    public void openExternalEditor() {
        File currentFile = fileOperations.getCurrentFile();

        if (currentFile == null) {
            updateStatus("No file currently open to edit externally");
            return;
        }

        // Get the external editor command from preferences
        String commandTemplate = PreferenceManager.getFileString(
            PreferenceKeys.FILE_EXTERNAL_EDITOR_COMMAND,
            "code <folder_path> <file_path>"
        );

        if (commandTemplate.trim().isEmpty()) {
            updateStatus("External editor command not configured in preferences");
            return;
        }

        try {
            // Replace placeholders with actual paths
            String folderPath = currentFile.getParentFile().getAbsolutePath();
            String filePath = currentFile.getAbsolutePath();

            String command = commandTemplate
                .replace("<folder_path>", folderPath)
                .replace("<file_path>", filePath);

            // Split command into parts for ProcessBuilder
            String[] commandParts = command.split("\\s+");

            ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
            processBuilder.directory(currentFile.getParentFile());

            Process process = processBuilder.start();

            updateStatus("External editor launched: " + currentFile.getName());
            logger.info("External editor command executed: {}", command);

        } catch (Exception e) {
            String message = "Failed to launch external editor: " + e.getMessage();
            updateStatus(message);
            logger.error("Error launching external editor", e);

            // Show error dialog with helpful information
            JOptionPane.showMessageDialog(
                this,
                message + "\n\n" +
                "Please check your external editor command in File → Preferences → File.\n" +
                "Current command: " + commandTemplate,
                "External Editor Error",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    /**
     * Handles file reload when external changes are detected.
     * Only reloads if the file is clean (no unsaved changes).
     *
     * @param file The file that has changed
     */
    private void handleFileReload(File file) {
        // Only reload if the file is clean (not dirty)
        if (!textEditor.isDirty()) {
            try {
                fileOperations.loadModelFile(file);
                updateStatus("File reloaded: " + file.getName());
            } catch (Exception e) {
                updateStatus("Failed to reload file: " + e.getMessage());
                logger.error("Error reloading file: {}", file, e);
            }
        } else {
            // File is dirty, don't auto-reload but notify user
            updateStatus("File changed externally, but has unsaved changes - not reloaded");
        }
    }

    /**
     * Updates the toolbar with theme-appropriate icon colors.
     * Called when the theme changes.
     */
    public void updateToolBar() {
        if (toolBar != null) {
            // Remove the old toolbar
            remove(toolBar);
            
            // Create a new toolbar with updated theme colors
            ToolBarBuilder toolBarBuilder = new ToolBarBuilder(this);
            toolBar = toolBarBuilder.buildToolBar();
            add(toolBar, BorderLayout.NORTH);
            
            // Revalidate the layout
            revalidate();
            repaint();
        }
    }

    /**
     * Attempts to load the last opened file from OS preferences.
     * If the file doesn't exist or no preference is set, falls back to default behavior.
     */
    private void loadLastOpenedFile() {
        String lastFilePath = PreferenceManager.getOsString(PreferenceKeys.LAST_OPENED_FILE, "");

        if (!lastFilePath.isEmpty()) {
            File lastFile = new File(lastFilePath);
            if (lastFile.exists() && lastFile.isFile() && fileOperations.isKalixModelFile(lastFile)) {
                // File exists and is valid, load it
                fileOperations.loadModelFile(lastFile);
                return;
            }
        }

        // No last file, file doesn't exist, or invalid file type
        // Fall back to default behavior (new model with default text is already set)
    }

    /**
     * Main entry point for the Kalix IDE application.
     * 
     * @param args command line arguments (not used)
     */
    public static void main(String[] args) {
        // Configure system properties for better macOS integration
        ThemeManager.configureSystemProperties();
        
        SwingUtilities.invokeLater(() -> {
            try {
                // Initialize theme from preferences
                Preferences prefs = Preferences.userNodeForPackage(KalixIDE.class);
                ThemeManager tempThemeManager = new ThemeManager(prefs, null);
                tempThemeManager.initializeLookAndFeel();
                
            } catch (UnsupportedLookAndFeelException e) {
                logger.error("Failed to initialize FlatLaf look and feel: {}", e.getMessage(), e);
            }
            
            // Create the main application window
            new KalixIDE();
        });
    }
}